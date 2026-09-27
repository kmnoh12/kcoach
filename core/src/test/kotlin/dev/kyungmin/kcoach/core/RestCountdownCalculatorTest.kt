package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RestCountdownCalculatorTest {
    @Test
    fun `sixty second rest renders fifty nine over sixty after first tick`() {
        val countdown = RestCountdownCalculator.calculate(
            nowMs = 1_001L,
            endAtMs = 60_000L,
            totalSeconds = 60,
        )

        assertEquals(59, countdown.remainingSeconds)
        assertEquals(60, countdown.totalSeconds)
        assertEquals(58_999f / 60_000f, countdown.remainingFraction)
    }

    @Test
    fun `expired and invalid rests are clamped`() {
        assertEquals(
            RestCountdown(0, 60, 0f),
            RestCountdownCalculator.calculate(nowMs = 61_000L, endAtMs = 60_000L, totalSeconds = 60),
        )
        assertEquals(
            RestCountdown(0, 0, 0f),
            RestCountdownCalculator.calculate(nowMs = 0L, endAtMs = 60_000L, totalSeconds = 0),
        )
    }
}
