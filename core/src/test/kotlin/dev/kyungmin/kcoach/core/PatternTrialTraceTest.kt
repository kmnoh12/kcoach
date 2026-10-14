package dev.kyungmin.kcoach.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PatternTrialTraceTest {
    @Test fun aTracePreservesObservedEmissionTimeAndCannotReplaceItWithTheSensorBoundary() {
        val runtime = PatternRuntimeProfile("candidate", 1, "a".repeat(64),
            PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch-A", "watch-native-si-v1"),
            List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0), 3_000_000_000, 1_800_000_000, 4_800_000_000, .2)
        val histogram = List(64) { if (it == 22) 20L else 0L }
        val event = PatternTrialEvent(RepCycle(1_000_000_000, 2_000_000_000, 4_000_000_000, MusclePhase.CONCENTRIC),
            4_120_000_000, 4_140_000_000, .05)
        val trace = PatternTrialTrace(runtime, "session", "set", "attempt", "clock", true,
            PatternTrialSource.EMULATED, 20, histogram, listOf(event), false)
        val encoded = PatternTrialTraceCodec.encode(trace)
        assertTrue(encoded.length < 65_000)
        assertEquals(trace, PatternTrialTraceCodec.decode(encoded))
        assertEquals(4_140_000_000, PatternTrialTraceCodec.decode(encoded).events.single().processedAtNs)
        assertEquals(4_194_303L, trace.processingP95UpperNs)
        assertThrows(IllegalArgumentException::class.java) { event.copy(processedAtNs = 3_999_000_000) }
        val rendered = event.copy(feedbackAtNs = 4_160_000_000)
        val withFeedback = trace.copy(events = listOf(rendered))
        assertEquals(4_160_000_000, PatternTrialTraceCodec.decode(PatternTrialTraceCodec.encode(withFeedback)).events.single().feedbackAtNs)
        assertNull(PatternTrialTraceCodec.decode(PatternTrialTraceCodec.encode(trace, formatVersion = 1)).events.single().feedbackAtNs)
    }
}
