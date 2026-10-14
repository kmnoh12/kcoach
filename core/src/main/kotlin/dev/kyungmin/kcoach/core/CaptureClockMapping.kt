package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.math.roundToLong

/** Interpolate start/end offsets; human signal review remains a separate requirement. */
class CaptureClockMapping(val start: ClockAlignment, val end: ClockAlignment) {
    private val watchSpan = end.watchOriginNs - start.watchOriginNs
    private val phoneSpan = end.phoneOriginNs - start.phoneOriginNs
    val uncertaintyNs = maxOf(start.uncertaintyNs, end.uncertaintyNs)

    init {
        require(start.phoneClockId == end.phoneClockId && start.watchClockId == end.watchClockId)
        require(watchSpan > 0 && phoneSpan > 0)
        require(abs(Math.subtractExact(phoneSpan, watchSpan)) <= watchSpan / 1_000 +
            start.uncertaintyNs + end.uncertaintyNs) { "Clock drift exceeds delay bounds; align again" }
    }

    fun phoneTimeNs(watchNs: Long, phoneClockId: String, watchClockId: String): Long {
        checkEpochs(phoneClockId, watchClockId)
        require(watchNs >= 0 && watchNs >= start.watchOriginNs - 2_000_000_000L && watchNs <= end.watchOriginNs + 2_000_000_000L)
        return (start.phoneOriginNs + (watchNs - start.watchOriginNs).toDouble() * phoneSpan / watchSpan).roundToLong()
            .also { require(it >= 0) }
    }

    fun watchTimeNs(phoneNs: Long, phoneClockId: String, watchClockId: String): Long {
        checkEpochs(phoneClockId, watchClockId)
        require(phoneNs >= 0 && phoneNs >= start.phoneOriginNs - 2_000_000_000L && phoneNs <= end.phoneOriginNs + 2_000_000_000L)
        return (start.watchOriginNs + (phoneNs - start.phoneOriginNs).toDouble() * watchSpan / phoneSpan).roundToLong()
            .also { require(it >= 0) }
    }

    private fun checkEpochs(phoneClockId: String, watchClockId: String) {
        require(phoneClockId == start.phoneClockId && watchClockId == start.watchClockId) { "Capture clock epoch changed" }
    }
}
