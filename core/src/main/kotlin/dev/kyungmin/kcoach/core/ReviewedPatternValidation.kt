package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import kotlin.math.abs

data class HeldOutPatternSet(val set: ReviewedPatternSet, val countReference: SetCountReference,
    val baselineProfile: ExerciseTrackingProfile = ExerciseTrackingProfiles.resolve(set.exerciseId))

data class UnscoredPatternCapture(val captureId: String, val dayId: String, val exerciseId: String,
    val expected: Int?, val reason: String) {
    init { require(captureId.isNotBlank() && reason.isNotBlank() && (expected == null || expected >= 0)) }
}

data class PatternValidationRow(val captureId: String, val rawSha256: String, val reviewRevision: Long,
    val dayId: String, val exerciseId: String, val expected: Int, val baselineCount: Int?, val candidateCount: Int?,
    val exclusionReason: String?, val startErrorsMs: List<Double>, val turnErrorsMs: List<Double>, val endErrorsMs: List<Double>,
    val paperPeakCount: Int? = null, val causalCount: Int? = null, val causalSourceEmissionDelaysMs: List<Double> = emptyList())

data class PatternValidationReport(val profileId: String, val profileVersion: Long, val rows: List<PatternValidationRow>,
    val totalSets: Int, val totalReps: Int, val distinctDays: Int, val predictedSets: Int,
    val exactSets: Int, val withinOneSets: Int, val rawMae: Double?, val baselineRawMae: Double?,
    val startBoundaryMaeMs: Double?, val turnaroundMaeMs: Double?, val endBoundaryMaeMs: Double?,
    val failures: List<String>, val unscoredCaptures: List<UnscoredPatternCapture> = emptyList(),
    val previousHash: String? = null) {
    val unknownReferenceSets: Int get() = unscoredCaptures.count { it.expected == null }
    val paperRawMae: Double? get() = rows.mapNotNull { row -> row.paperPeakCount?.let { abs(it - row.expected).toDouble() } }
        .takeIf { it.isNotEmpty() }?.average()
    val causalRawMae: Double? get() = rows.mapNotNull { row -> row.causalCount?.let { abs(it - row.expected).toDouble() } }
        .takeIf { it.isNotEmpty() }?.average()
    val causalExactSets: Int get() = rows.count { it.causalCount == it.expected }
    val coverage: Double get() = if (totalSets == 0) 0.0 else predictedSets.toDouble() / totalSets
    val countGatePassed: Boolean get() = failures.isEmpty()
    // This comparison executes only the offline phone candidate. It provides no causal
    // watch latency, native runtime or applied-profile ACK evidence.
    val watchAdoptionReady: Boolean get() = false
}

/** Independent count/boundary comparison. Does not tune the frozen profile or modify workout records. */
object ReviewedPatternValidation {
    fun evaluate(profile: ReviewedPatternProfile, sets: List<HeldOutPatternSet>,
        unscored: List<UnscoredPatternCapture> = emptyList(),
        previousRuntime: PatternRuntimeProfile? = null): Result<PatternValidationReport> = runCatching {
        require(previousRuntime == null || previousRuntime.compatibility == profile.compatibility)
        val ids = sets.map { it.set.captureId } + unscored.map { it.captureId }
        require(ids.size <= 500 && ids.distinct().size == ids.size)
        require(sets.map { it.set.rawSha256 }.distinct().size == sets.size) { "Duplicate validation original" }
        val failures = linkedSetOf<String>()
        if (unscored.any { it.expected == null }) failures += "validation_inputs_unavailable"
        val sources = profile.sources
        val rows = sets.map { heldOut ->
            val set = heldOut.set
            val truth = heldOut.countReference
            require(set.compatibility == profile.compatibility &&
                ExerciseMeasurementContracts.resolve(set.exerciseId).recipe == profile.compatibility.recipe && set.references.all {
                it.captureId == set.captureId && it.setId == truth.setId && it.attemptId == truth.attemptId
            })
            require(set.dayId.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
            val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, set.document.records,
                set.document.sampleRateRequestedHz, set.document.formatVersion) }.toByteArray()
            require(SensorCaptureFormat.sha256(bytes) == set.rawSha256) { "Validation raw hash changed" }
            val overlap = sources.any { it.captureId == set.captureId || it.rawSha256 == set.rawSha256 }
            if (overlap) failures += "training_overlap"
            val referencesGood = PatternValidationInputs.boundariesEligible(heldOut)
            // Human count truth is valid without reviewed boundaries, including an actual zero.
            val reason = when { overlap -> "training_overlap"; !PatternValidationInputs.signalUsable(set.document) -> "invalid_or_incomplete_signal"; else -> null }
            val analysis = if (reason == null) ReviewedPatternAnalyzer.analyze(set.document, set.exerciseId, set.compatibility, profile) else null
            val baseline = if (reason != null) null else if (previousRuntime != null) {
                PatternValidationInputs.replay(set, previousRuntime).size
            } else PhoneRepAnalyzer.analyze(set.document, heldOut.baselineProfile).result.phoneReanalyzedReps
            val candidate = analysis?.estimatedCount?.takeIf { analysis.rejectionReason == null }
            val paper = if (reason == null) PaperPeakRepAnalyzer.analyze(set.document, set.exerciseId) else null
            val causal = if (reason == null) {
                val detector = ReviewedPatternDetector(set.exerciseId, set.compatibility, profile)
                // Only delivered source records. No imaginary post-set rest, future sample or finish timestamp.
                set.document.records.mapNotNull { detector.add(it.sample) }
            } else null
            val paired = if (referencesGood && candidate == truth.reps) analysis!!.cycles.zip(set.references.map { it.cycle }) else emptyList()
            PatternValidationRow(set.captureId, set.rawSha256, set.references.firstOrNull()?.revision ?: 0,
                set.dayId, set.exerciseId, truth.reps, baseline, candidate, reason ?: analysis?.rejectionReason,
                paired.map { (a, b) -> abs(a.startNs - b.startNs) / 1_000_000.0 },
                paired.map { (a, b) -> abs(a.turnaroundNs - b.turnaroundNs) / 1_000_000.0 },
                paired.map { (a, b) -> abs(a.endNs - b.endNs) / 1_000_000.0 },
                paper?.estimatedCount?.takeIf { paper.rejectionReason == null }, causal?.size,
                if (referencesGood && causal?.size == truth.reps) causal.zip(set.references).map { (detection, reference) ->
                    (detection.emittedAtNs - reference.cycle.endNs) / 1_000_000.0
                } else emptyList())
        }
        fun mae(values: List<Double>) = values.takeIf { it.isNotEmpty() }?.average()
        val size = rows.size + unscored.size
        val reps = rows.sumOf { it.expected } + unscored.mapNotNull { it.expected }.sum()
        val days = rows.filter { it.exclusionReason == null }.map { it.dayId }.distinct().size
        val predicted = rows.count { it.candidateCount != null }
        val exact = rows.count { it.candidateCount == it.expected }
        val withinOne = rows.count { it.candidateCount != null && abs(it.candidateCount - it.expected) <= 1 }
        val rawMae = mae(rows.mapNotNull { row -> row.candidateCount?.let { abs(it - row.expected).toDouble() } })
        val baselineMae = mae(rows.mapNotNull { row -> row.baselineCount?.let { abs(it - row.expected).toDouble() } })
        if (size < 20) failures += "insufficient_sets"
        if (days < 3) failures += "insufficient_days"
        if (reps < 150) failures += "insufficient_reps"
        if (size == 0 || predicted.toDouble() / size < 0.90) failures += "coverage_below_90_percent"
        if (size == 0 || exact.toDouble() / size < 0.90) failures += "exact_below_90_percent"
        if (size == 0 || withinOne.toDouble() / size < 0.95) failures += "within_one_below_95_percent"
        if (rawMae == null || rawMae > 0.30) failures += "mae_above_0_3"
        // Missing predictions cost expected+1 for the improvement decision only. This
        // explicit refusal penalty is not reported as measured raw MAE or hidden as zero.
        fun penalty(row: PatternValidationRow, count: Int?) = count?.let { abs(it - row.expected) } ?: (row.expected + 1)
        if (rows.any { penalty(it, it.candidateCount) > penalty(it, it.baselineCount) ||
                penalty(it, it.causalCount) > penalty(it, it.baselineCount) }) failures += "capture_regression"
        val candidatePenalty = rows.sumOf { penalty(it, it.candidateCount) }
        val baselinePenalty = rows.sumOf { penalty(it, it.baselineCount) }
        if (size == 0 || candidatePenalty >= baselinePenalty) failures += "no_strict_improvement"
        if (rows.groupBy { it.exerciseId }.values.any { group ->
            group.sumOf { penalty(it, it.candidateCount) } > group.sumOf { penalty(it, it.baselineCount) }
        }) failures += "condition_regression"
        val startMae = mae(rows.flatMap { it.startErrorsMs })
        val turnMae = mae(rows.flatMap { it.turnErrorsMs })
        val endMae = mae(rows.flatMap { it.endErrorsMs })
        if (listOf(startMae, turnMae, endMae).any { it == null || it > 150.0 }) failures += "boundaries_not_validated"
        PatternValidationReport(profile.id, profile.version, rows, size, reps, days, predicted, exact, withinOne,
            rawMae, baselineMae, startMae, turnMae, endMae, failures.toList(), unscored, previousRuntime?.candidateHash)
    }
}

/** Count truth and boundary eligibility are separate; invalid raw input must never masquerade as zero. */
internal object PatternValidationInputs {
    fun signalUsable(document: SensorCaptureDocument): Boolean = document.records.size in 10..30_000 &&
        document.sampleRateRequestedHz.isFinite() && document.sampleRateRequestedHz > 0 &&
        document.validateTimestamps().isEmpty() && document.records.all { record ->
            record.sample.sensorAccuracy != 0 && !SensorRecordQuality.hasAccelerationFault(record) &&
                SignalAxis.entries.all { it.value(record.sample).isFinite() }
        } && document.records.zipWithNext().all { (a, b) -> b.sample.timestampNs - a.sample.timestampNs <= 60_000_000 }

    fun boundariesEligible(held: HeldOutPatternSet): Boolean {
        val set = held.set; val count = held.countReference
        return count.reps > 0 && set.references.size == count.reps && set.references.all {
            it.captureId == set.captureId && it.setId == count.setId && it.attemptId == count.attemptId &&
                it.eligibleForLearning && it.revision > 0 &&
                it.cycle.departurePhase == ExerciseMeasurementContracts.resolve(set.exerciseId).departurePhase &&
                PatternSignal.covered(set.document.records, it.cycle.startNs, it.cycle.endNs, true)
        } && set.references.map { it.revision }.distinct().size == 1 &&
            set.references.zipWithNext().all { (a, b) -> a.cycle.endNs <= b.cycle.startNs }
    }

    fun replay(set: ReviewedPatternSet, runtime: PatternRuntimeProfile): List<RepCycle> {
        val detector = ReviewedPatternDetector(set.exerciseId, set.compatibility, runtime)
        return set.document.records.mapNotNull { detector.add(it.sample)?.cycle }
    }
}
