package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import kotlin.math.abs

data class PatternRegressionRow(val captureId: String, val rawSha256: String, val referenceDigest: String,
    val countReference: SetCountReference, val exerciseId: String, val candidateCount: Int?, val previousCount: Int?,
    val candidateBoundaryErrorsMs: List<Double>, val previousBoundaryErrorsMs: List<Double>, val refusalReason: String?)

data class PatternRegressionReport(val candidateHash: String, val previousHash: String?,
    val rows: List<PatternRegressionRow>, val failures: List<String>) {
    val passed: Boolean get() = failures.isEmpty()
    // Historical material may join a later training version; it remains a regression guard, never fresh accuracy evidence.
    val independentAccuracyEvidence: Boolean get() = false
}

/** Evaluates both frozen engines on the same historical originals without changing either engine. */
object PatternRegressionValidation {
    fun compare(candidate: PatternRuntimeProfile, previous: PatternRuntimeProfile?,
        sets: List<HeldOutPatternSet>): Result<PatternRegressionReport> = runCatching {
        require(previous == null || previous.compatibility == candidate.compatibility)
        require(sets.size <= 500 && sets.map { it.set.captureId }.distinct().size == sets.size)
        val failures = linkedSetOf<String>()
        if (sets.isEmpty()) failures += "regression_dataset_missing"
        val rows = sets.map { held ->
            val set = held.set; val count = held.countReference
            require(set.compatibility == candidate.compatibility &&
                ExerciseMeasurementContracts.resolve(set.exerciseId).recipe == candidate.compatibility.recipe)
            require(set.references.all { it.captureId == set.captureId && it.setId == count.setId && it.attemptId == count.attemptId })
            val raw = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, set.document.records,
                set.document.sampleRateRequestedHz, set.document.formatVersion) }.toByteArray()
            require(SensorCaptureFormat.sha256(raw) == set.rawSha256) { "Fixed regression original changed" }
            val records = set.document.records
            val usable = records.size in 10..30_000 && set.document.sampleRateRequestedHz.isFinite() &&
                set.document.sampleRateRequestedHz > 0 && set.document.validateTimestamps().isEmpty() &&
                records.all { record -> record.sample.sensorAccuracy != 0 && SignalAxis.entries.all { it.value(record.sample).isFinite() } } &&
                records.zipWithNext().all { (a, b) -> b.sample.timestampNs - a.sample.timestampNs <= 60_000_000 }
            fun replay(profile: PatternRuntimeProfile): List<RepCycle> {
                val detector = ReviewedPatternDetector(set.exerciseId, set.compatibility, profile)
                return records.mapNotNull { detector.add(it.sample)?.cycle }
            }
            val nextCycles = if (usable) replay(candidate) else null
            val previousCycles = if (!usable) null else if (previous != null) replay(previous) else
                PhoneRepAnalyzer.analyze(set.document, held.baselineProfile).events.map {
                    RepCycle(it.startTimestampNs, it.turnaroundTimestampNs, it.endTimestampNs,
                        ExerciseMeasurementContracts.resolve(set.exerciseId).departurePhase)
                }
            val previousCount = if (!usable) null else if (previous != null) previousCycles!!.size else
                PhoneRepAnalyzer.analyze(set.document, held.baselineProfile).result.phoneReanalyzedReps
            fun errors(cycles: List<RepCycle>?): List<Double> =
                if (cycles?.size != count.reps || set.references.size != count.reps || set.references.any { !it.eligibleForLearning }) emptyList()
                else cycles.zip(set.references).flatMap { (cycle, ref) -> listOf(abs(cycle.startNs - ref.cycle.startNs),
                    abs(cycle.turnaroundNs - ref.cycle.turnaroundNs), abs(cycle.endNs - ref.cycle.endNs)).map { it / 1_000_000.0 } }
            if (!usable || previousCount == null) failures += "regression_inputs_unavailable"
            PatternRegressionRow(set.captureId, set.rawSha256, ReviewedPatternBuilder.referenceDigest(set.references), count,
                set.exerciseId, nextCycles?.size, previousCount, errors(nextCycles), errors(previousCycles),
                if (!usable) "invalid_or_incomplete_signal" else if (previousCount == null) "baseline_unavailable" else null)
        }
        fun error(row: PatternRegressionRow, value: Int?) = value?.let { abs(it - row.countReference.reps) } ?: row.countReference.reps + 1
        if (rows.sumOf { error(it, it.candidateCount) } > rows.sumOf { error(it, it.previousCount) }) failures += "regression_count_error"
        if (rows.groupBy { it.exerciseId }.values.any { group ->
            group.sumOf { error(it, it.candidateCount) } > group.sumOf { error(it, it.previousCount) }
        }) failures += "regression_condition_error"
        val matchedRows = rows.filter { it.previousBoundaryErrorsMs.isNotEmpty() }
        if (matchedRows.any { it.candidateBoundaryErrorsMs.isEmpty() }) failures += "regression_boundaries_unavailable"
        if ((0..2).any { category ->
            val old = matchedRows.flatMap { it.previousBoundaryErrorsMs.filterIndexed { index, _ -> index % 3 == category } }
            val next = matchedRows.flatMap { it.candidateBoundaryErrorsMs.filterIndexed { index, _ -> index % 3 == category } }
            old.isNotEmpty() && next.size == old.size && next.average() > old.average() + 1e-9
        }) failures += "regression_boundary_error"
        PatternRegressionReport(candidate.candidateHash, previous?.candidateHash, rows, failures.toList())
    }
}
