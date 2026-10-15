package dev.kyungmin.kcoach.core

/**
 * Chooses which video-found repetitions may become reviewed labels without the user marking every boundary.
 * The user's confirmed total is the evidence: the video windows, minus those the user said are not reps, must add up
 * to exactly that total. Anything else stays unlabeled, since a missing turnaround time cannot be invented.
 */
object RepPromotion {
    /** [spans] is parallel to [proposals]; [rejected] holds windows the user answered "not a repetition". */
    fun select(proposals: List<RepCycle>, spans: List<RepSpan?>, rejected: Set<RepSpan>, confirmedCount: Int): List<RepCycle>? {
        require(proposals.size == spans.size)
        if (confirmedCount < 1) return null
        val kept = proposals.filterIndexed { index, _ -> spans[index]?.let { it !in rejected } ?: false }
        val rejectedHere = spans.count { it != null && it in rejected }
        // A window with no usable video time is neither kept nor rejected, so the totals can no longer be trusted.
        return kept.takeIf { it.size == confirmedCount && it.size == proposals.size - rejectedHere }
    }
}
