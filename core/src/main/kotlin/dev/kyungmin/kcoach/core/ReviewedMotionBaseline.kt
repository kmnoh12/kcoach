package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Integrated gyroscope magnitude on human-reviewed cycles; never a joint angle or spatial path. */
data class ReviewedWristMotionBaseline(val candidateHash: String, val compatibility: PatternCompatibility,
    val reviewedRepCount: Int, val medianAngularTravelRad: Double, val medianAbsoluteDeviationRad: Double) {
    init {
        require(candidateHash.matches(Regex("[0-9a-f]{64}")) && reviewedRepCount in 3..4_000)
        require(medianAngularTravelRad.isFinite() && medianAngularTravelRad > 1e-6 &&
            medianAbsoluteDeviationRad.isFinite() && medianAbsoluteDeviationRad >= 0)
    }
}
data class WristMotionComparison(val candidateHash: String, val reviewedRepCount: Int,
    val baselineMedianAngularTravelRad: Double, val baselineMedianAbsoluteDeviationRad: Double,
    val medianCurrentToBaselineRatio: Double, val repetitionRatios: List<Double>) {
    init {
        require(candidateHash.matches(Regex("[0-9a-f]{64}")) && reviewedRepCount in 3..4_000)
        require(baselineMedianAngularTravelRad.isFinite() && baselineMedianAngularTravelRad > 1e-6 &&
            baselineMedianAbsoluteDeviationRad.isFinite() && baselineMedianAbsoluteDeviationRad >= 0)
        require(repetitionRatios.size in 1..600 && repetitionRatios.all { it.isFinite() && it >= 0 } &&
            medianCurrentToBaselineRatio.isFinite() && medianCurrentToBaselineRatio >= 0)
    }
}
object ReviewedMotionBaseline {
    fun derive(profile: ReviewedPatternProfile, runtime: PatternRuntimeProfile,
        sets: List<ReviewedPatternSet>): Result<ReviewedWristMotionBaseline> = runCatching {
        require(PatternRuntimeProfile.from(profile) == runtime) { "Runtime and frozen reviewed candidate differ" }
        require(sets.size == profile.sources.size && sets.map { it.captureId }.distinct().size == sets.size &&
            sets.map { it.captureId }.toSet() == profile.sources.map { it.captureId }.toSet()) { "Missing or duplicate baseline sources" }
        val travel = profile.sources.flatMap { source ->
            val set = sets.single { it.captureId == source.captureId }
            val refs = set.references.filter { it.eligibleForLearning }
            require(set.compatibility == runtime.compatibility && set.rawSha256 == source.rawSha256 &&
                set.exerciseId == source.exerciseId && set.dayId == source.dayId &&
                refs.isNotEmpty() && refs.all { it.captureId == set.captureId && it.revision == source.reviewRevision &&
                    it.cycle.departurePhase == ExerciseMeasurementContracts.resolve(set.exerciseId).departurePhase && it.cycle.pauses.isEmpty() } &&
                ReviewedPatternBuilder.referenceDigest(refs) == source.referenceDigest) { "Reviewed motion sources or labels changed" }
            val raw = ByteArrayOutputStream().apply {
                SensorCaptureFormat.write(this, set.document.records, set.document.sampleRateRequestedHz, set.document.formatVersion)
            }.toByteArray()
            require(SensorCaptureFormat.sha256(raw) == source.rawSha256) { "Baseline raw hash changed" }
            require(refs.all { PatternSignal.covered(set.document.records, it.cycle.startNs, it.cycle.endNs, originalClocks = true) })
            // These sources establish angular travel, not independently reviewed absence of pauses.
            requireNotNull(ObservedMotionAnalyzer.analyze(set.document, refs.map { it.cycle }, "REVIEWED_WRIST_REFERENCE"))
                .reps.map { it.angularTravelRad }
        }
        require(travel.size == profile.trainingRepCount) { "Baseline reviewed repetition count changed" }
        val center = median(travel)
        ReviewedWristMotionBaseline(runtime.candidateHash, runtime.compatibility, travel.size, center,
            median(travel.map { abs(it - center) }))
    }
    fun compare(metrics: ObservedMotionMetrics, baseline: ReviewedWristMotionBaseline,
        runtime: PatternRuntimeProfile): WristMotionComparison? {
        if (metrics.source != "PERSONAL_WRIST_CANDIDATE" || baseline.candidateHash != runtime.candidateHash ||
            baseline.compatibility != runtime.compatibility) return null
        val ratios = metrics.reps.map { it.angularTravelRad / baseline.medianAngularTravelRad }
        return WristMotionComparison(runtime.candidateHash, baseline.reviewedRepCount, baseline.medianAngularTravelRad,
            baseline.medianAbsoluteDeviationRad, median(ratios), ratios)
    }
    private fun median(values: List<Double>): Double = values.sorted().let {
        if (it.size % 2 == 0) (it[it.size / 2 - 1] + it[it.size / 2]) / 2 else it[it.size / 2]
    }
}
