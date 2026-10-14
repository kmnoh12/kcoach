package dev.kyungmin.kcoach.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import dev.kyungmin.kcoach.core.TempoFeedbackPolicy.*

class TempoFeedbackPolicyTest {
    private fun config(metric: Metric = Metric.REPETITION, count: Int = 2, interval: Long = 0) =
        Config(true, mapOf(metric to RangeMs(1_000, 2_000)), count, interval)
    private fun rep(n: Int, time: Long = n * 3_000L, attempt: String = "a", set: String = "s") =
        Observation(set, attempt, n, time * 1_000_000, 3_000, qualitySufficient = true)

    @Test fun offByDefaultAndOnlyUserConfiguredTargetsCanAlert() {
        val off = TempoFeedbackPolicy()
        (1..5).forEach { assertNull(off.observe(rep(it))) }
        val untargeted = TempoFeedbackPolicy(Config(enabled = true))
        assertNull(untargeted.observe(rep(1)))
        val policy = TempoFeedbackPolicy(config())
        assertNull(policy.observe(rep(1)))
        val result = policy.observe(rep(2))!!
        assertEquals(3_000L, result.deviations.single().observedMs)
        assertEquals(Direction.LONGER, result.deviations.single().direction)
        assertEquals(2, result.deviations.single().consecutiveRepetitions)
    }

    @Test fun duplicatesReversedTimeAndSkippedRepetitionsDoNotBuildAConsecutiveRun() {
        val policy = TempoFeedbackPolicy(config(count = 3))
        assertNull(policy.observe(rep(1)))
        assertNull(policy.observe(rep(1, 4_000)))
        assertNull(policy.observe(rep(2, 2_000)))
        assertNull(policy.observe(rep(2)))
        assertNull(policy.observe(rep(4)))
        assertNull(policy.observe(rep(5)))
        assertNotNull(policy.observe(rep(6)))
    }

    @Test fun qualityBreakPartialRepPauseAndTurningOffRequireNewConsecutiveEvidence() {
        val policy = TempoFeedbackPolicy(config())
        assertNull(policy.observe(rep(1)))
        assertNull(policy.observe(rep(2).copy(qualitySufficient = false)))
        assertNull(policy.observe(rep(3)))
        assertNull(policy.observe(rep(4).copy(complete = false)))
        assertNull(policy.observe(rep(5)))
        policy.pause()
        assertNull(policy.observe(rep(6)))
        policy.configure(config().copy(enabled = false))
        assertNull(policy.observe(rep(7)))
        policy.configure(config())
        assertNull(policy.observe(rep(8)))
        assertNotNull(policy.observe(rep(9)))
    }

    @Test fun newSetOrAttemptResetsTheRunAndLateOldAttemptCannotReviveIt() {
        val policy = TempoFeedbackPolicy(config())
        assertNull(policy.observe(rep(1)))
        assertNull(policy.observe(rep(1, 6_000, "b")))
        assertNull(policy.observe(rep(2, 7_000, "a")))
        assertNotNull(policy.observe(rep(2, 9_000, "b")))
        assertNull(policy.observe(rep(1, 12_000, "c", "next")))
        assertNotNull(policy.observe(rep(2, 15_000, "c", "next")))
    }

    @Test fun rateLimitSurvivesPausesAndAttemptsAndIncludesTheExactBoundary() {
        val policy = TempoFeedbackPolicy(config(count = 1, interval = 10_000))
        assertNotNull(policy.observe(rep(1, 1_000)))
        policy.pause()
        assertNull(policy.observe(rep(1, 2_000, "b")))
        assertNull(policy.observe(rep(2, 10_999, "b")))
        assertNotNull(policy.observe(rep(3, 11_000, "b")))
    }

    @Test fun missingOrUnvalidatedPhasesNeverBecomeHalfOfTheWholeRep() {
        val policy = TempoFeedbackPolicy(config(Metric.CONCENTRIC))
        assertNull(policy.observe(rep(1)))
        assertNull(policy.observe(rep(2).copy(concentricMs = 500)))
        assertNull(policy.observe(rep(3).copy(concentricMs = 500, phasesValidated = true)))
        val result = policy.observe(rep(4).copy(concentricMs = 600, phasesValidated = true))!!
        assertEquals(600L, result.deviations.single().observedMs)
        assertEquals(Metric.CONCENTRIC, result.deviations.single().metric)
        assertNull(policy.observe(rep(5).copy(concentricMs = 6_000, phasesValidated = true)))
        assertNull(policy.observe(rep(6).copy(concentricMs = 500, phasesValidated = true)))
    }

    @Test fun inRangeAndChangedDeviationDirectionResetTheirOwnConsecutiveCondition() {
        val policy = TempoFeedbackPolicy(config())
        assertNull(policy.observe(rep(1)))
        assertNull(policy.observe(rep(2).copy(repetitionMs = 2_000)))
        assertNull(policy.observe(rep(3)))
        assertNull(policy.observe(rep(4).copy(repetitionMs = 500)))
        assertEquals(Direction.SHORTER, policy.observe(rep(5).copy(repetitionMs = 600))!!.deviations.single().direction)
    }
}
