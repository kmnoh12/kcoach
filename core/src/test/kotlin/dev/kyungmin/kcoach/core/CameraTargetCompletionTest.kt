package dev.kyungmin.kcoach.core

import kotlin.test.*

class CameraTargetCompletionTest {
    @Test fun tenRepsWaitThreeSecondsThenFinishEvenWhenTheUserWalksAway() {
        val end = CameraTargetCompletion()
        assertNull(end.observe(1_000_000_000, 8, true))
        assertNull(end.observe(2_000_000_000, 9, true))
        assertNull(end.observe(3_000_000_000, 10, false)) // Unwritten/invalid frame cannot be the cutoff.
        assertNull(end.observe(4_000_000_000, 10, true))
        assertEquals(3, end.remainingSeconds(4_000_000_000))
        assertNull(end.observe(6_999_999_999, 11, false))
        assertEquals(4_000_000_000, end.observe(7_000_000_000, 11, false))
        assertEquals(4_000_000_000, end.motionEndCameraNs)
    }
    @Test fun nineRepsNeverFinishAndDuplicateOrReversedFramesCannotFinish() {
        val end = CameraTargetCompletion()
        for (n in 0L..20L) assertNull(end.observe(n * 1_000_000_000, 9, true))
        assertNull(end.motionEndCameraNs)
        assertNull(end.observe(21_000_000_000, 10, true))
        assertNull(end.observe(21_000_000_000, 10, true))
        assertNull(end.observe(20_000_000_000, 10, true))
        assertEquals(21_000_000_000, end.observe(24_000_000_000, 10, false))
    }
}
