package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ClockAlignmentTest {
    @Test
    fun alignsBootRelativeClocksWithoutReplacingOriginalSensorTimes() {
        val alignment = ClockAlignment.estimate(listOf(
            ClockExchange(2_000_000_000L, 1_010_000_000L, 1_011_000_000L, 2_021_000_000L),
        ), "phone-boot", "watch-boot")
        assertEquals(2_100_000_000L, alignment.phoneTimeNs(1_100_000_000L, "watch-boot"))
        assertEquals(10_000_000L, alignment.uncertaintyNs)
        assertFailsWith<IllegalArgumentException> { alignment.phoneTimeNs(1_100_000_000L, "new-watch-boot") }
    }

    @Test
    fun aSlowRoundTripIsNotPresentedAsPreciseLabelAlignment() {
        val alignment = ClockAlignment.estimate(listOf(
            ClockExchange(2_000_000_000L, 1_100_000_000L, 1_101_000_000L, 2_201_000_000L),
        ), "phone", "watch")
        assertFalse(alignment.withinUncertainty(50_000_000L))
    }

    @Test
    fun impossibleTimestampOrderIsRejected() {
        assertFailsWith<IllegalArgumentException> { ClockExchange(10, 10, 20, 15) }
    }
}
