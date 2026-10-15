package dev.kyungmin.kcoach.core

import kotlin.test.*

class TrailingRepSuspicionTest {
    private fun rep(index: Int, durationMs: Long, peak: Double) =
        RepEvent(index, 0, 0, 0, durationMs, null, null, 1.0, peak, 1.0)
    private val normal = (1..5).map { rep(it, 2000, 3.0) }

    @Test fun aSlowGentleLastRepLooksLikeAWatchGlance() {
        assertTrue(TrailingRepSuspicion.suspectLast(normal + rep(6, 4500, 1.0)))
    }

    @Test fun aSlowButForcefulLastRepIsKept() {
        assertFalse(TrailingRepSuspicion.suspectLast(normal + rep(6, 4500, 3.0)))
    }

    @Test fun aQuickGentleLastRepIsKept() {
        assertFalse(TrailingRepSuspicion.suspectLast(normal + rep(6, 2000, 1.0)))
    }

    @Test fun tooFewEarlierRepsGiveNoBasisToDoubt() {
        assertFalse(TrailingRepSuspicion.suspectLast(listOf(rep(1, 2000, 3.0), rep(2, 2000, 3.0), rep(3, 6000, 0.5))))
    }

    @Test fun slowStyleSetsAreNotFlaggedBecauseTheBaselineIsTheSetItself() {
        val slow = (1..5).map { rep(it, 5000, 1.0) }
        assertFalse(TrailingRepSuspicion.suspectLast(slow + rep(6, 5200, 1.0)))
    }
}
