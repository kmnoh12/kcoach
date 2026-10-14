package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.*
import kotlin.test.*

class ReviewedMotionBaselineTest {
    private val key = PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch", "watch-native-si-v1")
    private fun training(gyroEnabled: Boolean = true): ReviewedPatternSet {
        val records = (0..150).map { i ->
            val t = 1_000_000_000L + i * 20_000_000L
            val phase = i % 50 / 50.0 * 2 * PI
            val level = minOf(i / 50 + 1, 3)
            SensorCaptureRecord(SensorSample(t, (4 * (1 - cos(phase))).toFloat(), 0f, 9.8f,
                if (gyroEnabled) (level * sin(phase)).toFloat() else 0f, 0f, 0f),
                accelerationTimestampNs = t, gyroscopeTimestampNs = t, receivedElapsedNs = t + 1_000_000)
        }
        val document = SensorCaptureDocument(2, 50.0, records)
        val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val references = (0..2).map { i -> RepReference("capture", "set", "attempt", RepCycle(1_000_000_000L + i * 1_000_000_000,
            1_500_000_000L + i * 1_000_000_000, 2_000_000_000L + i * 1_000_000_000, MusclePhase.CONCENTRIC),
            ReferenceSource.REVIEWED_VIDEO, 5_000_000, true, true, 100, revision = 1) }
        return ReviewedPatternSet("capture", SensorCaptureFormat.sha256(bytes), "dumbbell_curl", key, "2026-10-14", document, references)
    }
    @Test fun verifiedOriginalCyclesProduceMedianAndMadInRadiansAndCurrentRatioSurvivesEncoding() {
        val set = training(); val profile = ReviewedPatternBuilder.build(listOf(set), "pattern", 1).getOrThrow()
        val runtime = PatternRuntimeProfile.from(profile)
        val baseline = ReviewedMotionBaseline.derive(profile, runtime, listOf(set)).getOrThrow()
        assertEquals(3, baseline.reviewedRepCount)
        assertEquals(runtime.candidateHash, baseline.candidateHash)
        assertTrue(baseline.medianAngularTravelRad > 1.2 && baseline.medianAngularTravelRad < 1.3)
        assertTrue(baseline.medianAbsoluteDeviationRad > .6 && baseline.medianAbsoluteDeviationRad < .7)
        val current = ObservedMotionMetrics("PERSONAL_WRIST_CANDIDATE", listOf(ObservedRepMotion(0, 300_000_000,
            1_000_000_000, 300.0, 700.0, null, null, null, baseline.medianAngularTravelRad * .5, baseline.medianAngularTravelRad * .5)))
        val comparison = assertNotNull(ReviewedMotionBaseline.compare(current, baseline, runtime))
        assertEquals(.5, comparison.medianCurrentToBaselineRatio)
        val saved = current.copy(wristMotionComparison = comparison)
        assertEquals(saved, ObservedMotionMetricsCodec.decode(ObservedMotionMetricsCodec.encode(saved)))
        assertNull(ReviewedMotionBaseline.compare(current.copy(source = "GENERIC_WRIST_CANDIDATE"), baseline, runtime))
        assertNull(ReviewedMotionBaseline.compare(current, baseline.copy(candidateHash = "0".repeat(64)), runtime))
        assertNull(ReviewedMotionBaseline.compare(current, baseline.copy(compatibility = key.copy(side = "RIGHT")), runtime))
    }
    @Test fun missingEditedRawReviewedLabelsConditionsAndRuntimeAllRefuseBaseline() {
        val set = training(); val profile = ReviewedPatternBuilder.build(listOf(set), "pattern", 1).getOrThrow()
        val runtime = PatternRuntimeProfile.from(profile)
        fun refused(changed: ReviewedPatternSet) = assertTrue(ReviewedMotionBaseline.derive(profile, runtime, listOf(changed)).isFailure)
        refused(set.copy(rawSha256 = "0".repeat(64)))
        refused(set.copy(document = set.document.copy(records = set.document.records.map { it.copy(sample = it.sample.copy(gyroscopeX = 0f)) })))
        refused(set.copy(references = set.references.map { it.copy(revision = 2) }))
        refused(set.copy(references = set.references.map { it.copy(source = ReferenceSource.CAMERA_CANDIDATE) }))
        refused(set.copy(compatibility = key.copy(side = "RIGHT")))
        refused(set.copy(dayId = "2026-10-15"))
        assertTrue(ReviewedMotionBaseline.derive(profile, runtime, emptyList()).isFailure)
        assertTrue(ReviewedMotionBaseline.derive(profile, runtime.copy(candidateHash = "0".repeat(64)), listOf(set)).isFailure)
    }
    @Test fun zeroGyroMotionDoesNotFabricateAMovementPercentageAndLegacyMetricsStillDecode() {
        val set = training(false); val profile = ReviewedPatternBuilder.build(listOf(set), "pattern", 1).getOrThrow()
        assertTrue(ReviewedMotionBaseline.derive(profile, PatternRuntimeProfile.from(profile), listOf(set)).isFailure)
        val metrics = ObservedMotionMetrics("GENERIC_WRIST_CANDIDATE", listOf(ObservedRepMotion(0, 300_000_000,
            1_000_000_000, 300.0, 700.0, null, null, null, 1.0, 1.0)))
        val version2 = Base64.getDecoder().decode(ObservedMotionMetricsCodec.encode(metrics))
        val legacy = version2.copyOf(version2.size - 1).apply { this[3] = 1 }
        assertEquals(metrics, ObservedMotionMetricsCodec.decode(Base64.getEncoder().encodeToString(legacy)))
    }
}
