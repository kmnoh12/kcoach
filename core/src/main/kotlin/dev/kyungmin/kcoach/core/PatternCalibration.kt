package dev.kyungmin.kcoach.core

import kotlin.math.abs

data class PatternParameterChange(val key: String, val value: Double, val unit: String)
data class PatternParameterLimit(val key: String, val unit: String, val minimum: Double, val maximum: Double, val current: Double)

/** The model may adjust a frozen engine's acceptance gates, never templates, labels or executable code. */
object PatternCalibrationParameters {
    fun limits(profile: ReviewedPatternProfile): List<PatternParameterLimit> = listOf(
        PatternParameterLimit("maximum_distance", "normalized_dtw", .05, .8, profile.maximumDistance),
        PatternParameterLimit("minimum_duration_ms", "ms", 350.0, profile.medianDurationNs / 1e6, profile.minimumDurationNs / 1e6),
        PatternParameterLimit("maximum_duration_ms", "ms", profile.medianDurationNs / 1e6, 12_000.0, profile.maximumDurationNs / 1e6),
    )
    fun apply(base: ReviewedPatternProfile, changes: List<PatternParameterChange>, id: String, version: Long): ReviewedPatternProfile {
        require(base.engineId == "reviewed-six-axis-dtw-v1" && changes.size in 1..3 && changes.map { it.key }.distinct().size == changes.size)
        val limits = limits(base).associateBy { it.key }
        changes.forEach { change ->
            val limit = limits[change.key] ?: error("Unsupported engine parameter")
            require(change.unit == limit.unit && change.value.isFinite() && change.value in limit.minimum..limit.maximum)
            if (change.unit == "ms") require(change.value * 1e6 == (change.value * 1e6).toLong().toDouble())
        }
        val values = changes.associate { it.key to it.value }
        return base.copy(id = id, version = version,
            maximumDistance = values["maximum_distance"] ?: base.maximumDistance,
            minimumDurationNs = values["minimum_duration_ms"]?.let { (it * 1e6).toLong() } ?: base.minimumDurationNs,
            maximumDurationNs = values["maximum_duration_ms"]?.let { (it * 1e6).toLong() } ?: base.maximumDurationNs)
    }
}

data class PatternCalibrationReplayRow(val captureId: String, val expected: Int?, val estimated: Int,
    val startBoundaryMaeMs: Double?, val turnBoundaryMaeMs: Double?, val endBoundaryMaeMs: Double?,
    val sourceEmissionDelayMs: List<Double>)
data class PatternCalibrationReplay(val profileId: String, val rows: List<PatternCalibrationReplayRow>) {
    val countMae: Double? get() = rows.mapNotNull { row -> row.expected?.let { abs(row.estimated - it).toDouble() } }
        .takeIf { it.isNotEmpty() }?.average()
}

/** Tuning diagnostics on the existing frozen training originals. Never independent adoption evidence. */
object PatternCalibrationReplayer {
    fun replay(profile: ReviewedPatternProfile, sets: List<ReviewedPatternSet>, counts: Map<String, Int?>,
        checkpoint: () -> Unit = {}): PatternCalibrationReplay {
        require(sets.isNotEmpty() && sets.size <= 6 && sets.all { set -> profile.sources.any {
            it.captureId == set.captureId && it.rawSha256 == set.rawSha256 &&
                it.reviewRevision == set.references.firstOrNull { ref -> ref.eligibleForLearning }?.revision &&
                it.referenceDigest == ReviewedPatternBuilder.referenceDigest(set.references.filter { ref -> ref.eligibleForLearning })
        } && set.compatibility == profile.compatibility })
        val rows = sets.map { set ->
            checkpoint()
            val raw = java.io.ByteArrayOutputStream().apply {
                SensorCaptureFormat.write(this, set.document.records, set.document.sampleRateRequestedHz, set.document.formatVersion)
            }.toByteArray()
            require(SensorCaptureFormat.sha256(raw) == set.rawSha256) { "Training original changed" }
            require(set.document.validateTimestamps().isEmpty() && set.document.records.size in 10..12_000)
            require(set.document.records.zipWithNext().all { (a, b) -> b.sample.timestampNs - a.sample.timestampNs <= 60_000_000 })
            require(set.document.records.all { it.sample.sensorAccuracy != 0 && SignalAxis.entries.all { axis -> axis.value(it.sample).isFinite() } })
            val detector = ReviewedPatternDetector(set.exerciseId, set.compatibility, profile)
            val detections = set.document.records.mapNotNull { record -> checkpoint(); detector.add(record.sample) }
            val references = set.references.filter { it.eligibleForLearning }
            val count = counts[set.captureId]?.also { require(it >= 0) }
            val completeBoundaries = count == references.size && references.size == detections.size && count != null && count > 0
            fun error(value: (RepCycle) -> Long): Double? = if (!completeBoundaries) null else
                references.zip(detections).map { (reference, detection) -> abs(value(reference.cycle) - value(detection.cycle)) / 1e6 }.average()
            PatternCalibrationReplayRow(set.captureId, count, detections.size, error { it.startNs }, error { it.turnaroundNs }, error { it.endNs },
                detections.map { (it.emittedAtNs - it.cycle.endNs) / 1e6 })
        }
        checkpoint()
        return PatternCalibrationReplay(profile.id, rows)
    }
}
