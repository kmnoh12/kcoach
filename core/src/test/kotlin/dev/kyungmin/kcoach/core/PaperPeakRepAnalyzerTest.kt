package dev.kyungmin.kcoach.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos

class PaperPeakRepAnalyzerTest {
    @Test fun threeCompleteCyclesProduceThreeOrderedStartTurnAndReturnBoundaries() {
        val records = (0..600).map { index ->
            val t = index * 0.02
            val value = if (t in 1.0..10.0) 4 * (1 - cos(2 * PI * (t - 1) / 3)) else 0.0
            SensorCaptureRecord(SensorSample(1_000_000_000 + index * 20_000_000L, value.toFloat(), 0f, 9.8f, 0f, 0f, 0f))
        }
        val result = PaperPeakRepAnalyzer.analyze(SensorCaptureDocument(2, 50.0, records), "dumbbell_curl")
        assertEquals(3, result.estimatedCount)
        assertEquals("ACCEL_X", result.axis?.name)
        assertEquals(3, result.cycles.size)
        assertTrue(result.cycles.all { it.startNs < it.turnaroundNs && it.turnaroundNs < it.endNs })
        assertTrue(result.cycles.zipWithNext().all { (a, b) -> a.endNs <= b.startNs })
    }

    @Test fun aHalfCycleIsNotCompletedAndManualExercisesNeverReceiveAnAutomaticCount() {
        val records = (0..125).map { index ->
            val t = index * 0.02
            val value = if (t >= 1.0) 4 * (1 - cos(2 * PI * (t - 1) / 3)) else 0.0
            SensorCaptureRecord(SensorSample(1_000_000_000 + index * 20_000_000L, value.toFloat(), 0f, 9.8f, 0f, 0f, 0f))
        }
        val document = SensorCaptureDocument(2, 50.0, records)
        assertEquals(0, PaperPeakRepAnalyzer.analyze(document, "dumbbell_curl").estimatedCount)
        val manual = PaperPeakRepAnalyzer.analyze(document, "leg_press")
        assertNull(manual.estimatedCount)
        assertEquals("manual_required", manual.rejectionReason)
    }
}
