package dev.kyungmin.kcoach.core

/**
 * Flags a final repetition that looks like glancing at the watch rather than another rep: much slower than the
 * set's own earlier reps and with a gentle peak angular speed. It only flags; the count is never changed here,
 * so a genuinely slow last rep costs the user one tap instead of a silently lost repetition.
 */
object TrailingRepSuspicion {
    const val MIN_PRIOR_REPS = 3
    const val SLOW_RATIO = 1.8
    const val GENTLE_RATIO = 0.6

    fun suspectLast(events: List<RepEvent>): Boolean {
        if (events.size <= MIN_PRIOR_REPS) return false
        val last = events.last()
        val prior = events.dropLast(1)
        val typicalDuration = median(prior.map { it.durationMs.toDouble() })
        val typicalPeak = median(prior.map { it.peakAngularVelocity })
        if (typicalDuration <= 0.0 || typicalPeak <= 0.0) return false
        return last.durationMs >= SLOW_RATIO * typicalDuration && last.peakAngularVelocity <= GENTLE_RATIO * typicalPeak
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
}
