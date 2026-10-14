package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sqrt

/** Physical mounting and a specified exercise variant never inherit a target from another key. */
data class StartWristDirectionKey(val context: StartWristContext, val deviceId: String,
    val coordinates: String = "watch-native-si-v1") {
    init {
        require(context.completeForDirectionValidation) { "Mount and specified variant must be confirmed" }
        require(deviceId.matches(Regex("[A-Za-z0-9._-]{1,160}")) && coordinates == "watch-native-si-v1")
    }
}

enum class StartDirectionLabel { CORRECT_DIRECTION, OPPOSITE_DIRECTION, EVERYDAY_MOVEMENT }
enum class StartDirectionReviewSource { HUMAN_VIDEO, HUMAN_IN_PERSON }

/** A human labels the original interval. Neither a quiet sample nor a detector output supplies the label. */
data class ReviewedStartDirectionInterval(val captureId: String, val rawSha256: String, val dayId: String,
    val key: StartWristDirectionKey, val document: SensorCaptureDocument, val startNs: Long, val endNs: Long,
    val label: StartDirectionLabel, val reviewSource: StartDirectionReviewSource?, val reviewRevision: Long,
    val reviewedAtMs: Long) {
    init {
        require(captureId.matches(Regex("[A-Za-z0-9._-]{1,160}")))
        require(rawSha256.matches(Regex("[0-9a-f]{64}")))
        require(LocalDate.parse(dayId).toString() == dayId)
        require(startNs > 0 && endNs > startNs && endNs - startNs >= 2_000_000_000L)
        require(reviewRevision > 0 && reviewedAtMs > 0 && reviewSource != null) { "Human direction review required" }
    }

    fun records(): List<SensorCaptureRecord> {
        require(document.formatVersion == 2 && document.validateTimestamps().isEmpty())
        val bytes = ByteArrayOutputStream().apply {
            SensorCaptureFormat.write(this, document.records, document.sampleRateRequestedHz, document.formatVersion)
        }.toByteArray()
        require(SensorCaptureFormat.sha256(bytes) == rawSha256) { "Reviewed raw sensor bytes changed" }
        require(PatternSignal.covered(document.records, startNs, endNs, originalClocks = true)) {
            "Reviewed direction interval has missing original clocks or sensor coverage"
        }
        val selected = document.records.filter { it.sample.timestampNs in startNs..endNs }
        require(selected.all { it.sample.sensorAccuracy in 1..3 && it.accelerationAccuracy in 1..3 }) {
            "Both accelerometer and gyroscope quality must be known for learning a direction"
        }
        return selected
    }

    fun source(): StartDirectionSource = StartDirectionSource(captureId, rawSha256, dayId, startNs, endNs, label,
        reviewRevision, SensorCaptureFormat.sha256(listOf(captureId, rawSha256, dayId, key,
            startNs, endNs, label, reviewSource, reviewRevision, reviewedAtMs).joinToString("|").toByteArray()))
}

data class StartDirectionSource(val captureId: String, val rawSha256: String, val dayId: String,
    val startNs: Long, val endNs: Long, val label: StartDirectionLabel, val reviewRevision: Long, val reviewDigest: String)

/** A fitted target remains a candidate until separate positive, opposite, and everyday captures are replayed. */
data class ReviewedStartDirectionCandidate(val id: String, val version: Long, val key: StartWristDirectionKey,
    val target: StartWristGravityTarget, val angularMarginRad: Double, val sources: List<StartDirectionSource>) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,100}")) && version > 0)
        require(angularMarginRad.isFinite() && angularMarginRad > 0 && angularMarginRad < PI / 4)
        require(sources.isNotEmpty() && sources.size <= 500)
    }
    val gateConfig: StartPoseGateConfig get() = directionConfig(key).copy(expectedGravity = target)
    val fingerprint: String get() = SensorCaptureFormat.sha256(listOf("reviewed-start-gravity-v1", id, version, key, target, gateConfig, angularMarginRad,
        sources.sortedWith(compareBy<StartDirectionSource> { it.captureId }.thenBy { it.startNs })).joinToString("|").toByteArray())
}

object ReviewedStartDirectionBuilder {
    /** Margin is an explicit experiment parameter, never an exercise-default Watch8 angle. */
    fun build(intervals: List<ReviewedStartDirectionInterval>, id: String, version: Long,
        angularMarginRad: Double): Result<ReviewedStartDirectionCandidate> = runCatching {
        checkIntervals(intervals)
        require(angularMarginRad.isFinite() && angularMarginRad > 0 && angularMarginRad < PI / 4)
        require(intervals.any { it.label == StartDirectionLabel.CORRECT_DIRECTION } &&
            intervals.any { it.label == StartDirectionLabel.OPPOSITE_DIRECTION }) { "Correct and opposite training controls required" }
        require(intervals.none { it.label == StartDirectionLabel.EVERYDAY_MOVEMENT }) { "Everyday controls are held out for validation" }
        val observed = intervals.associateWith { interval ->
            interval.records().also { records ->
                val gate = StartPoseStabilityGate(directionConfig(interval.key))
                var last: StartPoseGateObservation? = null
                records.forEach { r ->
                    last = gate.observe(r.sample, r.accelerationTimestampNs, r.accelerationAccuracy)
                    require(last!!.candidate && last!!.stable) { "Training directions must be stationary, fully observed holds" }
                }
                require(last?.ready == true) { "Training direction needs a full continuous two-second hold" }
            }.map { gravity(it.sample) }
        }
        val positives = observed.filterKeys { it.label == StartDirectionLabel.CORRECT_DIRECTION }.values.flatten()
        val mean = (0..2).map { axis -> positives.sumOf { it[axis] } / positives.size }
        val norm = sqrt(mean.sumOf { it * it })
        require(norm > 0.001) { "Correct directions do not form one coherent target" }
        val center = mean.map { it / norm }
        fun angle(vector: List<Double>): Double = acos(center.indices.sumOf { center[it] * vector[it] }.coerceIn(-1.0, 1.0))
        val radius = positives.maxOf(::angle) + angularMarginRad
        require(radius < PI / 2 && observed.filterKeys { it.label == StartDirectionLabel.OPPOSITE_DIRECTION }
            .values.flatten().all { angle(it) > radius }) { "Correct and opposite training directions are not separable" }
        ReviewedStartDirectionCandidate(id, version, intervals.first().key,
            StartWristGravityTarget(center[0], center[1], center[2], radius), angularMarginRad, intervals.map { it.source() })
    }
}

data class StartDirectionReplayRow(val source: StartDirectionSource, val ready: Boolean,
    val firstReadyAfterMs: Long?)
data class StartDirectionValidationReport(val candidateFingerprint: String, val rows: List<StartDirectionReplayRow>,
    val failures: Set<String>) {
    val passed: Boolean get() = rows.isNotEmpty() && failures.isEmpty()
}

object ReviewedStartDirectionValidation {
    /** Replays the actual preparation gate causally, without padding a hold or calling it a body-posture verdict. */
    fun evaluate(candidate: ReviewedStartDirectionCandidate,
        intervals: List<ReviewedStartDirectionInterval>): Result<StartDirectionValidationReport> = runCatching {
        checkIntervals(intervals)
        require(intervals.all { it.key == candidate.key }) { "Direction validation changed mounting, user, exercise, or device" }
        val failures = linkedSetOf<String>()
        if (intervals.any { interval -> candidate.sources.any { it.captureId == interval.captureId || it.rawSha256 == interval.rawSha256 } })
            failures += "training_overlap"
        StartDirectionLabel.entries.forEach { label -> if (intervals.none { it.label == label }) failures += "missing_${label.name.lowercase()}" }
        val rows = intervals.map { interval ->
            val records = interval.records()
            val gate = StartPoseStabilityGate(candidate.gateConfig)
            var firstReady: Long? = null
            records.forEach { r ->
                if (gate.observe(r.sample, r.accelerationTimestampNs, r.accelerationAccuracy).ready && firstReady == null)
                    firstReady = r.sample.timestampNs
            }
            if (interval.label == StartDirectionLabel.CORRECT_DIRECTION && firstReady == null) failures += "correct_direction_rejected"
            if (interval.label != StartDirectionLabel.CORRECT_DIRECTION && firstReady != null) failures += "negative_direction_accepted"
            StartDirectionReplayRow(interval.source(), firstReady != null, firstReady?.let { (it - interval.startNs) / 1_000_000 })
        }
        StartDirectionValidationReport(candidate.fingerprint, rows, failures)
    }
}

private fun checkIntervals(intervals: List<ReviewedStartDirectionInterval>) {
    require(intervals.isNotEmpty() && intervals.size <= 500)
    require(intervals.all { it.key == intervals.first().key }) { "Mixed start direction conditions" }
    intervals.groupBy { it.captureId }.values.forEach { sameCapture ->
        require(sameCapture.map { it.rawSha256 }.distinct().size == 1)
        require(sameCapture.map { it.document }.distinct().size == 1 && sameCapture.map { it.dayId }.distinct().size == 1)
        require(sameCapture.sortedBy { it.startNs }.zipWithNext().all { (a, b) -> a.endNs < b.startNs }) { "Duplicate or overlapping reviewed intervals" }
    }
    require(intervals.groupBy { it.rawSha256 }.values.all { sameRaw -> sameRaw.map { it.captureId }.distinct().size == 1 }) {
        "Duplicated raw sensor bytes under another capture ID"
    }
}

private fun gravity(sample: SensorSample): List<Double> {
    val vector = listOf(sample.accelerationX.toDouble(), sample.accelerationY.toDouble(), sample.accelerationZ.toDouble())
    val norm = sqrt(vector.sumOf { it * it })
    require(norm.isFinite() && norm > 0.001)
    return vector.map { it / norm }
}

private fun directionConfig(key: StartWristDirectionKey): StartPoseGateConfig =
    StartPoseGateConfig.forProfile(ExerciseTrackingProfiles.resolve(key.context.exerciseId)).copy(
        requireSetupMotion = false, maxSampleGapMs = 60, maxAccelerationAgeMs = 40)
