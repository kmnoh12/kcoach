package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class PatternCalibrationTest {
    private fun training(): ReviewedPatternSet {
        val key = PatternCompatibility("local", MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch", "watch-native-si-v1")
        val records = (0..450).map { i ->
            val time = 1_000_000_000L + i * 20_000_000L; val phase = (i % 150) / 150.0
            SensorCaptureRecord(SensorSample(time, (4 * (1 - cos(2 * PI * phase))).toFloat(), 0f, 9.8f,
                (1.2 * sin(2 * PI * phase)).toFloat(), 0f, 0f), accelerationTimestampNs = time,
                gyroscopeTimestampNs = time, receivedElapsedNs = time + 1_000_000)
        }
        val raw = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val refs = (0..2).map { i -> RepReference("training", "set", "attempt",
            RepCycle(1_000_000_000L + i * 3_000_000_000L, 2_500_000_000L + i * 3_000_000_000L,
                4_000_000_000L + i * 3_000_000_000L, MusclePhase.CONCENTRIC), ReferenceSource.REVIEWED_VIDEO,
            5_000_000, true, true, 100, revision = 1) }
        return ReviewedPatternSet("training", SensorCaptureFormat.sha256(raw), "dumbbell_curl", key, "2026-10-14", SensorCaptureDocument(2, 50.0, records), refs)
    }
    @Test fun onlyAllowedUnitsAndGatesCanChangeAndTheTrainingModelAndSourcesRemainFrozen() {
        val set = training(); val base = ReviewedPatternBuilder.build(listOf(set), "base", 1).getOrThrow()
        val changed = PatternCalibrationParameters.apply(base, listOf(PatternParameterChange("maximum_distance", .3, "normalized_dtw"),
            PatternParameterChange("minimum_duration_ms", 2000.0, "ms"), PatternParameterChange("maximum_duration_ms", 4500.0, "ms")), "new", 2)
        assertEquals(.3, changed.maximumDistance); assertEquals(2_000_000_000, changed.minimumDurationNs)
        assertEquals(base.template, changed.template); assertEquals(base.scales, changed.scales); assertEquals(base.activeAxes, changed.activeAxes)
        assertEquals(base.sources, changed.sources); assertEquals(base.compatibility, changed.compatibility)
        assertEquals(changed, ReviewedPatternCodec.decode(ReviewedPatternCodec.encode(changed)))
    }
    @Test fun wrongUnitsUnknownKeysDuplicatesAndOutOfBoundsProposalsAreRejected() {
        val base = ReviewedPatternBuilder.build(listOf(training()), "base", 1).getOrThrow()
        for (change in listOf(PatternParameterChange("template", .1, "normalized_dtw"), PatternParameterChange("maximum_distance", .1, "%"),
            PatternParameterChange("maximum_distance", .9, "normalized_dtw"), PatternParameterChange("maximum_distance", Double.NaN, "normalized_dtw"),
            PatternParameterChange("minimum_duration_ms", 100.0, "ms"), PatternParameterChange("minimum_duration_ms", 4000.0, "ms"),
            PatternParameterChange("maximum_duration_ms", 2000.0, "ms"), PatternParameterChange("maximum_duration_ms", 12001.0, "ms"))) {
            assertFails { PatternCalibrationParameters.apply(base, listOf(change), "new", 2) }
        }
        val change = PatternParameterChange("maximum_distance", .3, "normalized_dtw")
        assertFails { PatternCalibrationParameters.apply(base, listOf(change, change), "new", 2) }
        assertFails { PatternCalibrationParameters.apply(base, listOf(PatternParameterChange("minimum_duration_ms", 3000.0, "ms"),
            PatternParameterChange("maximum_duration_ms", 3000.0, "ms")), "new", 2) }
    }
    @Test fun causalReplayUsesUnpaddedOriginalsAndCannotBecomeIndependentAdoptionEvidence() {
        val set = training(); val base = ReviewedPatternBuilder.build(listOf(set), "base", 1).getOrThrow()
        val replay = PatternCalibrationReplayer.replay(base, listOf(set), mapOf(set.captureId to 3))
        assertEquals(2, replay.rows.single().estimated) // The final causal emission has no invented trailing samples.
        assertNull(replay.rows.single().startBoundaryMaeMs)
        assertEquals(1.0, replay.countMae)
        val held = ReviewedPatternValidation.evaluate(base, listOf(HeldOutPatternSet(set, SetCountReference("set", "attempt", 3, 100)))).getOrThrow()
        assertFalse(held.countGatePassed); assertTrue("training_overlap" in held.failures)
    }
    @Test fun changedOriginalOrLabelsCannotBeReplayedAsTheFrozenTrainingSource() {
        val set = training(); val base = ReviewedPatternBuilder.build(listOf(set), "base", 1).getOrThrow()
        assertFails { PatternCalibrationReplayer.replay(base, listOf(set.copy(document = set.document.copy(records = set.document.records.dropLast(1)))), mapOf("training" to 3)) }
        assertFails { PatternCalibrationReplayer.replay(base, listOf(set.copy(references = set.references.map { it.copy(revision = 2) })), mapOf("training" to 3)) }
        assertFails { PatternCalibrationReplayer.replay(base, listOf(set.copy(captureId = "held-out")), mapOf("held-out" to 3)) }
    }
    @Test fun expensiveReplayIsCooperativelyCancellable() {
        val set = training(); val base = ReviewedPatternBuilder.build(listOf(set), "base", 1).getOrThrow()
        var checks = 0
        assertFails { PatternCalibrationReplayer.replay(base, listOf(set), mapOf("training" to 3)) { if (++checks > 20) error("cancelled") } }
        assertEquals(21, checks)
    }
}
