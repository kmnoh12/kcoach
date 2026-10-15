package dev.kyungmin.kcoach.core

import kotlin.test.*

class RepPromotionTest {
    private fun cycle(i: Int) = RepCycle(i * 3000L, i * 3000L + 1000, i * 3000L + 2000, MusclePhase.CONCENTRIC)
    private fun span(i: Int) = RepSpan(i * 3000, i * 3000 + 2000)
    private val three = (0..2).map(::cycle)
    private val threeSpans = (0..2).map(::span)

    @Test fun videoWindowsThatAddUpToTheConfirmedTotalAreAllPromoted() {
        assertEquals(three, RepPromotion.select(three, threeSpans, emptySet(), 3))
    }

    @Test fun aTotalThatDiffersFromTheVideoWindowsPromotesNothing() {
        assertNull(RepPromotion.select(three, threeSpans, emptySet(), 4))
        assertNull(RepPromotion.select(three, threeSpans, emptySet(), 2))
    }

    @Test fun aWindowTheUserRejectedIsDroppedAndTheRestMustMatchTheTotal() {
        assertEquals(listOf(three[0], three[2]), RepPromotion.select(three, threeSpans, setOf(span(1)), 2))
        assertNull(RepPromotion.select(three, threeSpans, setOf(span(1)), 3))
    }

    @Test fun aWindowWithoutUsableVideoTimeBlocksPromotion() {
        assertNull(RepPromotion.select(three, listOf(span(0), null, span(2)), emptySet(), 2))
    }

    @Test fun zeroTotalsNeverCreateLabels() {
        assertNull(RepPromotion.select(emptyList(), emptyList(), emptySet(), 0))
    }
}
