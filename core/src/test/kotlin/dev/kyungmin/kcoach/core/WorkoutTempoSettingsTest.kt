package dev.kyungmin.kcoach.core

import kotlin.test.*

class WorkoutTempoSettingsTest {
    @Test fun settingsRoundTripAndThePolicyUsesOnlyActualValidatedPhaseTimes() {
        val settings = WorkoutTempoSettings("external_rotation", true, mapOf(WorkoutTempoMetric.CONCENTRIC to WorkoutTempoRange(100, 200)), 2, 1_000)
        assertEquals(settings, WorkoutTempoSettingsCodec.decode(WorkoutTempoSettingsCodec.encode(settings)))
        val policy = TempoFeedbackPolicy(settings.policyConfig())
        assertNull(policy.observe(TempoFeedbackPolicy.Observation("set", "attempt", 1, 1_000_000_000, 1_000, 300,
            phasesValidated = false, qualitySufficient = true)))
        assertNull(policy.observe(TempoFeedbackPolicy.Observation("set", "attempt", 2, 2_000_000_000, 1_000, 300,
            phasesValidated = true, qualitySufficient = true)))
        assertEquals(300, policy.observe(TempoFeedbackPolicy.Observation("set", "attempt", 3, 3_000_000_000, 1_000, 300,
            phasesValidated = true, qualitySufficient = true))!!.deviations.single().observedMs)
    }
    @Test fun unknownManualTargetsEmptyEnabledAndInvalidRangesAreNotTransferable() {
        assertFails { WorkoutTempoSettings("unregistered") }
        assertFails { WorkoutTempoSettings("leg_press", true, mapOf(WorkoutTempoMetric.REPETITION to WorkoutTempoRange(1_000, 2_000))) }
        assertFails { WorkoutTempoSettings("external_rotation", true) }
        assertFails { WorkoutTempoRange(2_000, 1_000) }
        val encoded = WorkoutTempoSettingsCodec.encode(WorkoutTempoSettings("external_rotation"))
        val bytes = java.util.Base64.getDecoder().decode(encoded)
        assertFails { WorkoutTempoSettingsCodec.decode(java.util.Base64.getEncoder().encodeToString(bytes + byteArrayOf(0))) }
    }
}
