package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RestVersionProtocolTest {
    @Test fun restVersionAppendsAfterAllExistingFieldsAndPreservesIdentity() {
        val old = WearSync.withVerifiedPatternSelection(WearSync.richCommand("REST", "session", "leg_press", "휴식", 1, 8,
            30.0, 90, 100_000, "set", "attempt"))
        val fields = WearSync.parse(WearSync.withRestVersion(old, 42, 17))
        assertEquals(WearSync.parse(old), fields.take(WearSync.parse(old).size))
        assertEquals(24, fields.size)
        assertEquals("42", fields[22]); assertEquals("17", fields[23])
        assertEquals("set", fields[13]); assertEquals("attempt", fields[14])
    }

    @Test fun orderedRestControlsRoundTripAndMissingSeqDefaultsToZero() {
        val old = WatchWorkoutControl("adjust", WatchWorkoutAction.REST_ADJUST, "session", "", "",
            deltaSeconds = 10, restSeconds = 90, restEndAtMs = 100_000)
        assertEquals(0L, WatchWorkoutControl.decode(old.encode()).seq)
        val adjust = old.copy(seq = Long.MAX_VALUE)
        val pause = old.copy(action = WatchWorkoutAction.REST_PAUSE, seq = 2, restEndAtMs = 0, pausedRestRemainingMs = 53_123)
        val resume = old.copy(action = WatchWorkoutAction.REST_RESUME, seq = 3)
        listOf(adjust, pause, resume).forEach { assertEquals(it, WatchWorkoutControl.decode(it.encode())) }
        assertFailsWith<IllegalArgumentException> { adjust.copy(seq = -1) }
        assertFailsWith<IllegalArgumentException> { pause.copy(pausedRestRemainingMs = null) }
    }
}
