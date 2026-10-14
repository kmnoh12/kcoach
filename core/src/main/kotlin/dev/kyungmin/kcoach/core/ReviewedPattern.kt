package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Device identity is stable across boots; clock identity is deliberately not a profile key. */
data class PatternCompatibility(val localUserId: String, val recipe: MeasurementRecipe, val side: String,
    val placement: String, val deviceId: String, val coordinates: String) {
    init {
        require(localUserId.matches(Regex("[A-Za-z0-9._-]{1,160}")) && deviceId.matches(Regex("[A-Za-z0-9._-]{1,160}")) && coordinates == "watch-native-si-v1")
        require(side in setOf("LEFT", "RIGHT") && placement in setOf("OUTSIDE", "INSIDE"))
        require(recipe !in setOf(MeasurementRecipe.MANUAL, MeasurementRecipe.SQUAT))
    }
}

data class ReviewedPatternSet(val captureId: String, val rawSha256: String, val exerciseId: String,
    val compatibility: PatternCompatibility, val dayId: String, val document: SensorCaptureDocument,
    val references: List<RepReference>)

data class PatternSource(val captureId: String, val rawSha256: String, val reviewRevision: Long,
    val dayId: String, val exerciseId: String, val referenceDigest: String) {
    init {
        require(captureId.matches(Regex("[A-Za-z0-9._-]{1,160}")) && exerciseId.length in 1..160)
        require(rawSha256.matches(Regex("[0-9a-f]{64}")) && referenceDigest.matches(Regex("[0-9a-f]{64}")))
        require(reviewRevision > 0 && dayId.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
    }
}

data class ReviewedPatternProfile(val id: String, val version: Long, val compatibility: PatternCompatibility,
    val sources: List<PatternSource>, val exerciseConditions: Set<String>, val trainingRepCount: Int,
    val template: List<List<Double>>, val scales: List<Double>, val activeAxes: List<Int>,
    val medianDurationNs: Long, val minimumDurationNs: Long, val maximumDurationNs: Long,
    val maximumDistance: Double, val trainingDispersion: Double,
    val engineId: String = "reviewed-six-axis-dtw-v1") {
    init {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,100}")) && version > 0 && trainingRepCount >= 3)
        require(sources.isNotEmpty() && sources.map { it.captureId }.distinct().size == sources.size)
        require(sources.size <= 500 && sources.map { it.rawSha256 }.distinct().size == sources.size &&
            exerciseConditions.isNotEmpty() && exerciseConditions == sources.map { it.exerciseId }.toSet() && trainingRepCount <= 4_000)
        require(template.size == 33 && template.all { it.size == 6 && it.all(Double::isFinite) })
        require(scales.size == 6 && scales.all { it.isFinite() && it > 0 })
        require(activeAxes.isNotEmpty() && activeAxes.distinct() == activeAxes && activeAxes.all { it in 0..5 })
        require(minimumDurationNs >= 350_000_000 && maximumDurationNs <= 12_000_000_000 &&
            minimumDurationNs < maximumDurationNs && medianDurationNs in minimumDurationNs..maximumDurationNs)
        require(maximumDistance.isFinite() && maximumDistance in 0.05..0.8 && trainingDispersion.isFinite() && trainingDispersion >= 0)
    }
}

/** Fits human-reviewed intervals, never a counter's own detections. Output is a candidate, not an adopted profile. */
object ReviewedPatternBuilder {
    fun build(sets: List<ReviewedPatternSet>, profileId: String, version: Long): Result<ReviewedPatternProfile> = runCatching {
        require(sets.isNotEmpty() && sets.size <= 500 && sets.map { it.captureId }.distinct().size == sets.size &&
            sets.map { it.rawSha256 }.distinct().size == sets.size) { "Duplicate training capture or raw bytes" }
        val key = sets.first().compatibility
        require(sets.all { it.compatibility == key }) { "Mixed user/recipe/hand/placement/device coordinates" }
        val examples = mutableListOf<Pair<RepCycle, List<List<Double>>>>()
        val sources = mutableListOf<PatternSource>()
        sets.sortedBy { it.captureId }.forEach { set ->
            require(ExerciseTrackingProfiles.resolve(set.exerciseId).mode != TrackingMode.MANUAL_REQUIRED &&
                ExerciseMeasurementContracts.resolve(set.exerciseId).recipe == key.recipe)
            require(set.dayId.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
            require(set.document.validateTimestamps().isEmpty())
            val raw = ByteArrayOutputStream().apply {
                SensorCaptureFormat.write(this, set.document.records, set.document.sampleRateRequestedHz, set.document.formatVersion)
            }.toByteArray()
            require(SensorCaptureFormat.sha256(raw) == set.rawSha256) { "Training raw hash changed" }
            val refs = set.references.filter { it.eligibleForLearning }
            require(refs.all { it.captureId == set.captureId && it.revision > 0 &&
                it.cycle.departurePhase == ExerciseMeasurementContracts.resolve(set.exerciseId).departurePhase })
            require(refs.map { it.revision }.distinct().size <= 1 && refs.map { it.setId to it.attemptId }.distinct().size <= 1)
            require(refs.zipWithNext().all { (a, b) -> a.cycle.endNs <= b.cycle.startNs })
            refs.forEach { reference ->
                val cycle = reference.cycle
                require(cycle.endNs - cycle.startNs in 350_000_000..12_000_000_000L && cycle.pauses.isEmpty()) {
                    "Paused repetitions need a dedicated template and remain excluded from this candidate"
                }
                require(PatternSignal.covered(set.document.records, cycle.startNs, cycle.endNs, originalClocks = true))
                examples += cycle to PatternSignal.frames(set.document.records, cycle.startNs, cycle.turnaroundNs, cycle.endNs)
            }
            if (refs.isNotEmpty()) sources += PatternSource(set.captureId, set.rawSha256, refs.first().revision,
                set.dayId, set.exerciseId, referenceDigest(refs))
        }
        require(examples.size in 3..4_000) { "At least three reviewed, aligned, fully observed repetitions are required" }
        val scales = (0..5).map { axis ->
            maxOf(if (axis < 3) 0.3 else 0.12,
                median(examples.map { (_, frames) -> sqrt(frames.sumOf { it[axis] * it[axis] } / frames.size) }))
        }
        val activeAxes = (0..5).filter { axis ->
            median(examples.map { (_, frames) -> frames.maxOf { it[axis] } - frames.minOf { it[axis] } }) >=
                if (axis < 3) 0.4 else 0.15
        }
        require(activeAxes.isNotEmpty()) { "Reviewed wrist motion is below observable amplitude" }
        val normalized = examples.map { (_, frames) -> frames.map { frame -> frame.mapIndexed { axis, value -> value / scales[axis] } } }
        val center = (0..32).map { index -> (0..5).map { axis -> median(normalized.map { it[index][axis] }) } }
        val distances = normalized.map { BoundedPatternDtw.compare(center, it, activeAxes).distance }
        val dispersion = median(distances)
        val durations = examples.map { it.first.endNs - it.first.startNs }
        val duration = durations.sorted()[durations.size / 2]
        ReviewedPatternProfile(profileId, version, key, sources, sources.map { it.exerciseId }.toSet(), examples.size,
            center, scales, activeAxes, duration,
            (durations.min() * 0.60).toLong().coerceAtLeast(350_000_000),
            (durations.max() * 1.60).toLong().coerceAtMost(12_000_000_000),
            (distances.sorted()[(distances.size - 1) * 9 / 10] * 2 + 0.12).coerceIn(0.12, 0.8), dispersion)
    }

    fun referenceDigest(references: List<RepReference>): String = SensorCaptureFormat.sha256(references.joinToString("\n") {
        listOf(it.captureId, it.setId, it.attemptId, it.revision, it.cycle.startNs, it.cycle.turnaroundNs,
            it.cycle.endNs, it.cycle.departurePhase, it.cycle.pauses, it.source, it.alignmentUncertaintyNs,
            it.alignmentVerified, it.sensorCoverageComplete, it.reviewedAtMs, it.exclusionReason).joinToString("|")
    }.toByteArray(Charsets.UTF_8))

    private fun median(values: List<Double>): Double = values.sorted().let { it[it.size / 2] }
}

internal object PatternSignal {
    fun covered(records: List<SensorCaptureRecord>, start: Long, end: Long, originalClocks: Boolean): Boolean {
        if (records.isEmpty() || records.first().sample.timestampNs > start || records.last().sample.timestampNs < end) return false
        val selected = records.filter { it.sample.timestampNs in start..end }
        if (selected.size < 10 || selected.first().sample.timestampNs - start > 60_000_000 || end - selected.last().sample.timestampNs > 60_000_000) return false
        if (records.zipWithNext().any { (a, b) -> b.sample.timestampNs > start && a.sample.timestampNs < end &&
                b.sample.timestampNs - a.sample.timestampNs > 60_000_000 }) return false
        if (selected.any { it.sample.sensorAccuracy == 0 || SensorRecordQuality.hasAccelerationFault(it) }) return false
        return !originalClocks || selected.all { r -> r.accelerationTimestampNs != null &&
            r.gyroscopeTimestampNs == r.sample.timestampNs && r.receivedElapsedNs != null &&
            r.receivedElapsedNs >= r.sample.timestampNs && r.sample.timestampNs - r.accelerationTimestampNs in 0..40_000_000 }
    }

    /** Nearest observed sample only; no missing interval is interpolated into a new observation. */
    fun frames(records: List<SensorCaptureRecord>, start: Long, turn: Long?, end: Long): List<List<Double>> {
        val points = records.filter { it.sample.timestampNs in start..end }
        require(points.isNotEmpty())
        val times = if (turn == null) (0..32).map { start + (end - start) * it / 32 }
            else (0..16).map { start + (turn - start) * it / 16 } + (1..16).map { turn + (end - turn) * it / 16 }
        val values = times.map { t ->
            val insertion = points.binarySearch { it.sample.timestampNs.compareTo(t) }
            val index = if (insertion >= 0) insertion else {
                val next = (-insertion - 1).coerceAtMost(points.lastIndex)
                val prev = (next - 1).coerceAtLeast(0)
                if (abs(points[next].sample.timestampNs - t) < abs(points[prev].sample.timestampNs - t)) next else prev
            }
            SignalAxis.entries.map { it.value(points[index].sample) }
        }
        val origin = values.first()
        return values.map { frame -> frame.mapIndexed { axis, v -> v - origin[axis] } }
    }
}

/** The path maps the template turnaround to observed candidate time rather than halving duration. */
internal object BoundedPatternDtw {
    data class Match(val distance: Double, val turnIndex: Int)
    fun compare(template: List<List<Double>>, candidate: List<List<Double>>, axes: List<Int>): Match {
        val n = template.size
        val costs = Array(n) { DoubleArray(n) { Double.POSITIVE_INFINITY } }
        val previous = Array(n) { IntArray(n) { -1 } }
        val lengths = Array(n) { IntArray(n) }
        for (i in 0 until n) for (j in maxOf(0, i - 8)..minOf(n - 1, i + 8)) {
            val local = axes.sumOf { axis -> val d = template[i][axis] - candidate[j][axis]; d * d } / axes.size
            if (i == 0 && j == 0) { costs[i][j] = local; lengths[i][j] = 1; continue }
            // The watch evaluates many windows. Preserve diagonal/up/left tie order without
            // allocating a list and three Pair objects for each dynamic-programming cell.
            var a = if (i > 0) i - 1 else i
            var b = if (j > 0) j - 1 else j
            if (i > 0 && costs[i - 1][j] < costs[a][b]) { a = i - 1; b = j }
            if (j > 0 && costs[i][j - 1] < costs[a][b]) { a = i; b = j - 1 }
            costs[i][j] = local + costs[a][b]; lengths[i][j] = lengths[a][b] + 1
            previous[i][j] = a * n + b
        }
        var i = n - 1; var j = n - 1
        val turn = mutableListOf<Int>()
        while (i >= 0 && j >= 0) {
            if (i == 16) turn += j
            val p = previous[i][j]
            if (p < 0) break
            i = p / n; j = p % n
        }
        return Match(sqrt(costs[n - 1][n - 1] / lengths[n - 1][n - 1]), turn.sorted().let { if (it.isEmpty()) 16 else it[it.size / 2] })
    }
}
