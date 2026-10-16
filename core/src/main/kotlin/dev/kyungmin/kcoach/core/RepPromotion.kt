package dev.kyungmin.kcoach.core

/**
 * Selects video candidates consistent with the user's confirmed total, after excluding rejected windows.
 * Count agreement does not verify individual start, turnaround or end times and does not grant learning eligibility.
 * Boundary review and the camera, sensor and clock gates are separate responsibilities of the review store.
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
