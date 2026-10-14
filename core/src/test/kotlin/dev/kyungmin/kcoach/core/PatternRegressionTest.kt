package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PatternRegressionTest {
    @Test fun aFrozenComparisonRejectsAWorsePatternButDoesNotPretendToBeIndependentAccuracyEvidence() {
        val key = PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch", "watch-native-si-v1")
        val records = (0..470).map { i ->
            val t = 1_000_000_000L + i * 20_000_000L
            val p = if (i <= 450) (i % 150) / 150.0 else 0.0
            SensorCaptureRecord(SensorSample(t, (4 * (1 - cos(2 * PI * p))).toFloat(), 0f, 9.8f,
                (1.2 * sin(2 * PI * p)).toFloat(), 0f, 0f, 3), accelerationTimestampNs = t,
                gyroscopeTimestampNs = t, receivedElapsedNs = t + 1_000_000)
        }
        val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val refs = (0..2).map { i -> RepReference("fixed", "set", "attempt",
            RepCycle(1_000_000_000L + i * 3_000_000_000, 2_500_000_000L + i * 3_000_000_000,
                4_000_000_000L + i * 3_000_000_000, MusclePhase.CONCENTRIC), ReferenceSource.REVIEWED_VIDEO,
            5_000_000, true, true, 100, revision = 1) }
        val set = ReviewedPatternSet("fixed", SensorCaptureFormat.sha256(bytes), "dumbbell_curl", key, "2026-10-14",
            SensorCaptureDocument(2, 50.0, records), refs)
        val previous = PatternRuntimeProfile.from(ReviewedPatternBuilder.build(listOf(set), "previous", 1).getOrThrow())
        val cases = listOf(HeldOutPatternSet(set, SetCountReference("set", "attempt", 3, 100)))
        val same = PatternRegressionValidation.compare(previous, previous, cases).getOrThrow()
        assertTrue(same.passed)
        assertFalse(same.independentAccuracyEvidence)
        assertEquals(3, same.rows.single().candidateCount)
        val broken = previous.copy(id = "broken", version = 2, template = List(33) { List(6) { 0.0 } }, maximumDistance = .05)
        val worse = PatternRegressionValidation.compare(broken, previous, cases).getOrThrow()
        assertFalse(worse.passed)
        assertTrue("regression_count_error" in worse.failures)
        assertEquals(0, worse.rows.single().candidateCount)
        assertEquals(3, worse.rows.single().previousCount)
        assertFalse(PatternRegressionValidation.compare(previous, previous, emptyList()).getOrThrow().passed)
        val badRecords = records.map { it.copy(accelerationAccuracy = 0) }
        val badBytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, badRecords, 50.0) }.toByteArray()
        val bad = set.copy(document = set.document.copy(records = badRecords), rawSha256 = SensorCaptureFormat.sha256(badBytes))
        val faulty = PatternRegressionValidation.compare(previous, previous,
            listOf(HeldOutPatternSet(bad, SetCountReference("set", "attempt", 0, 100)))).getOrThrow()
        assertFalse(faulty.passed)
        assertNull(faulty.rows.single().candidateCount)
        assertNull(faulty.rows.single().previousCount)
    }
}
