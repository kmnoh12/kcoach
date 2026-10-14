package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Feedback timestamps must be collected on the watch after the corresponding output is submitted. */
data class NativePatternCase(val heldOut: HeldOutPatternSet, val trace: PatternTrialTrace?,
    val rawWatchClockId: String, val baselineWatchCount: Int?, val feedbackNs: List<Long>? = null,
    val baselineRuntime: PatternRuntimeProfile? = null, val baselineRuntimeUsed: Boolean = false,
    val baselineRuntimeInterrupted: Boolean = false, val baselineSnapshotVerified: Boolean = false,
    val baselineSampleCalls: Int? = null)

data class NativePatternValidationRow(val captureId: String, val rawSha256: String, val referenceDigest: String,
    val reviewRevision: Long, val dayId: String, val exerciseId: String, val expected: Int, val predicted: Int?,
    val baseline: Int?, val traceHash: String?, val exclusionReason: String?, val latencyMs: List<Double>,
    val boundaryErrorsMs: List<Double>, val processingP95UpperNs: Long?)

data class NativePatternValidationReport(val candidateHash: String, val rows: List<NativePatternValidationRow>,
    val totalSets: Int, val totalReps: Int, val validDays: Int, val predictedSets: Int, val exactSets: Int,
    val withinOneSets: Int, val mae: Double?, val latencyP95Ms: Double?, val boundaryMaeMs: Double?,
    val failures: List<String>, val previousHash: String? = null) {
    val approved: Boolean get() = failures.isEmpty()
}

/** Uses actual trace outputs, never offline replay as proof of device timing or use. */
object PatternNativeValidation {
    fun evaluate(profile: ReviewedPatternProfile, cases: List<NativePatternCase>,
        unscored: List<UnscoredPatternCapture> = emptyList(),
        previousRuntime: PatternRuntimeProfile? = null): Result<NativePatternValidationReport> = runCatching {
        require(previousRuntime == null || previousRuntime.compatibility == profile.compatibility)
        val runtime = PatternRuntimeProfile.from(profile)
        val ids = cases.map { it.heldOut.set.captureId } + unscored.map { it.captureId }
        require(ids.size <= 500 && ids.distinct().size == ids.size)
        require(cases.map { it.heldOut.set.rawSha256 }.distinct().size == cases.size) { "Duplicate native validation original" }
        val failures = linkedSetOf<String>()
        if (unscored.any { it.expected == null }) failures += "reference_not_reviewed"
        val rows = cases.map { case ->
            val set = case.heldOut.set; val count = case.heldOut.countReference
            require(set.compatibility == profile.compatibility &&
                ExerciseMeasurementContracts.resolve(set.exerciseId).recipe == profile.compatibility.recipe && (case.baselineWatchCount == null || case.baselineWatchCount >= 0))
            val raw = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, set.document.records,
                set.document.sampleRateRequestedHz, set.document.formatVersion) }.toByteArray()
            require(SensorCaptureFormat.sha256(raw) == set.rawSha256)
            val overlap = profile.sources.any { it.captureId == set.captureId || it.rawSha256 == set.rawSha256 }
            if (overlap) failures += "training_overlap"
            require(set.references.all { it.captureId == set.captureId && it.setId == count.setId && it.attemptId == count.attemptId })
            require(set.dayId.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
            val refsGood = PatternValidationInputs.boundariesEligible(case.heldOut)
            val baselineReason = when {
                !case.baselineSnapshotVerified -> "baseline_snapshot_unverified"
                case.baselineRuntime != previousRuntime -> "baseline_snapshot_mismatch"
                case.baselineRuntimeInterrupted -> "baseline_interrupted"
                case.baselineRuntimeUsed != (previousRuntime != null) -> "baseline_usage_mismatch"
                previousRuntime != null && case.baselineSampleCalls != set.document.sampleCount -> "baseline_samples_incomplete"
                previousRuntime != null && PatternValidationInputs.signalUsable(set.document) &&
                    PatternValidationInputs.replay(set, previousRuntime).size != case.baselineWatchCount -> "baseline_replay_mismatch"
                else -> null
            }
            val trace = case.trace
            val reason = when {
                overlap -> "training_overlap"
                !PatternValidationInputs.signalUsable(set.document) -> "invalid_or_incomplete_signal"
                trace == null -> "native_trace_missing"
                trace.runtime != runtime || trace.sessionId != set.captureId || trace.setId != count.setId || trace.attemptId != count.attemptId -> "trace_identity_mismatch"
                trace.watchClockId != case.rawWatchClockId || !trace.epochVerified -> "clock_not_verified"
                trace.source != PatternTrialSource.WATCH_SENSOR_EVENT -> "emulated_source"
                trace.interrupted -> "native_trace_interrupted"
                trace.sampleCalls != set.document.sampleCount.toLong() || trace.sampleCalls == 0L -> "native_trace_incomplete"
                trace.events.any { it.cycle.departurePhase != ExerciseMeasurementContracts.resolve(set.exerciseId).departurePhase ||
                    it.cycle.startNs < set.document.startMonotonicNs!! || it.sourceEmissionNs > set.document.endMonotonicNs!! } -> "event_outside_raw"
                else -> null
            }
            val replayMismatch = if (reason == null) {
                val detector = ReviewedPatternDetector(set.exerciseId, set.compatibility, runtime)
                val replay = set.document.records.mapNotNull { detector.add(it.sample) }
                replay.size != trace!!.events.size || replay.zip(trace.events).any { (a, b) ->
                    a.cycle != b.cycle || a.emittedAtNs != b.sourceEmissionNs || abs(a.distance - b.distance) > 1e-6
                }
            } else false
            val finalReason = reason ?: "native_replay_mismatch".takeIf { replayMismatch }
            if (baselineReason != null) failures += baselineReason
            val predicted = if (finalReason == null) trace!!.events.size else null
            val matching = if (refsGood && predicted == count.reps) trace!!.events.zip(set.references) else emptyList()
            val feedback = case.feedbackNs
            val measuredFeedback = finalReason == null && feedback != null && feedback.size == trace!!.events.size &&
                feedback.zip(trace.events).all { (at, event) -> at >= event.processedAtNs }
            if (finalReason == null && !measuredFeedback) failures += "feedback_not_measured"
            val latency = if (measuredFeedback && matching.isNotEmpty()) feedback!!.zip(set.references).map { (at, ref) ->
                (at - ref.cycle.endNs) / 1_000_000.0
            } else emptyList()
            if (latency.any { it < -RepReference.MAX_LABEL_ALIGNMENT_UNCERTAINTY_NS / 1_000_000.0 }) failures += "premature_feedback"
            val errors = matching.flatMap { (event, ref) -> listOf(abs(event.cycle.startNs - ref.cycle.startNs),
                abs(event.cycle.turnaroundNs - ref.cycle.turnaroundNs), abs(event.cycle.endNs - ref.cycle.endNs)).map { it / 1_000_000.0 } }
            NativePatternValidationRow(set.captureId, set.rawSha256, ReviewedPatternBuilder.referenceDigest(set.references),
                set.references.firstOrNull()?.revision ?: 0L, set.dayId, set.exerciseId, count.reps, predicted,
                case.baselineWatchCount.takeIf { baselineReason == null }, trace?.let { SensorCaptureFormat.sha256(PatternTrialTraceCodec.encode(it).toByteArray(Charsets.UTF_8)) },
                finalReason, latency, errors, trace?.processingP95UpperNs)
        }
        val total = rows.size + unscored.size
        val reps = rows.sumOf { it.expected } + unscored.mapNotNull { it.expected }.sum()
        val days = rows.filter { it.exclusionReason == null }.map { it.dayId }.distinct().size
        val predicted = rows.count { it.predicted != null }
        val exact = rows.count { it.predicted == it.expected }
        val withinOne = rows.count { it.predicted != null && abs(it.predicted - it.expected) <= 1 }
        val errors = rows.mapNotNull { row -> row.predicted?.let { abs(it - row.expected).toDouble() } }
        val mae = errors.takeIf { it.isNotEmpty() }?.average()
        val latencies = rows.flatMap { it.latencyMs }.sorted()
        val p95 = latencies.takeIf { it.isNotEmpty() }?.let { it[((it.size * 95 + 99) / 100 - 1).coerceAtLeast(0)] }
        val boundaryCategories = (0..2).mapNotNull { position -> rows.flatMap { row ->
            row.boundaryErrorsMs.filterIndexed { index, _ -> index % 3 == position }
        }.takeIf { it.isNotEmpty() }?.average() }
        val boundary = boundaryCategories.maxOrNull()
        if (total < 20) failures += "insufficient_sets"
        if (days < 3) failures += "insufficient_days"
        if (reps < 150) failures += "insufficient_reps"
        if (total == 0 || predicted.toDouble() / total < .90) failures += "coverage_below_90_percent"
        if (total == 0 || exact.toDouble() / total < .90) failures += "exact_below_90_percent"
        if (total == 0 || withinOne.toDouble() / total < .95) failures += "within_one_below_95_percent"
        if (mae == null || mae > .30) failures += "mae_above_0_3"
        if (p95 == null || p95 > 500.0) failures += "latency_not_validated"
        if (latencies.size < (reps * 90 + 99) / 100) failures += "insufficient_feedback_samples"
        if (boundary == null || boundary > 150.0) failures += "boundaries_not_validated"
        fun penalty(row: NativePatternValidationRow, value: Int?) = value?.let { abs(it - row.expected) } ?: row.expected + 1
        if (rows.isEmpty() || rows.sumOf { penalty(it, it.predicted) } >= rows.sumOf { penalty(it, it.baseline) }) failures += "no_strict_improvement"
        if (rows.any { it.exclusionReason == null && it.baseline == null }) failures += "baseline_not_recorded"
        if (rows.groupBy { it.exerciseId }.values.any { group -> group.sumOf { penalty(it, it.predicted) } > group.sumOf { penalty(it, it.baseline) } })
            failures += "condition_regression"
        if (rows.isEmpty()) failures += "feedback_not_measured"
        NativePatternValidationReport(runtime.candidateHash, rows, total, reps, days, predicted, exact, withinOne,
            mae, p95, boundary, failures.toList(), previousRuntime?.candidateHash)
    }
}
