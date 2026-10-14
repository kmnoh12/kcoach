package dev.kyungmin.kcoach.core

/** Four monotonic timestamps; the phone and watch epochs are deliberately independent. */
data class ClockExchange(
    val phoneSendNs: Long,
    val watchReceiveNs: Long,
    val watchSendNs: Long,
    val phoneReceiveNs: Long,
) {
    init {
        require(listOf(phoneSendNs, watchReceiveNs, watchSendNs, phoneReceiveNs).all { it >= 0 })
        require(phoneReceiveNs >= phoneSendNs && watchSendNs >= watchReceiveNs)
        require(phoneReceiveNs - phoneSendNs >= watchSendNs - watchReceiveNs) { "Impossible clock exchange" }
    }
    val minimumOffsetNs get() = phoneSendNs - watchReceiveNs
    val maximumOffsetNs get() = phoneReceiveNs - watchSendNs
}

/** A clock mapping, not a promise that keypoint or repetition boundaries are accurate. */
data class ClockAlignment(
    val watchOriginNs: Long,
    val phoneOriginNs: Long,
    val uncertaintyNs: Long,
    val phoneClockId: String,
    val watchClockId: String,
) {
    init {
        require(watchOriginNs >= 0 && phoneOriginNs >= 0 && uncertaintyNs >= 0)
        require(phoneClockId.isNotBlank() && watchClockId.isNotBlank())
    }

    fun phoneTimeNs(watchTimeNs: Long, currentWatchClockId: String): Long {
        require(watchTimeNs >= 0 && currentWatchClockId == watchClockId) { "Sensor clock epoch changed" }
        return Math.addExact(phoneOriginNs, watchTimeNs - watchOriginNs).also {
            require(it >= 0) { "Mapped timestamp precedes phone clock epoch" }
        }
    }

    fun withinUncertainty(maximumNs: Long): Boolean {
        require(maximumNs >= 0)
        return uncertaintyNs <= maximumNs
    }

    companion object {
        fun estimate(exchanges: List<ClockExchange>, phoneClockId: String, watchClockId: String): ClockAlignment {
            require(exchanges.isNotEmpty()) { "No clock exchanges" }
            // Intersect delay bounds. Contradictory samples must not create a precise-looking mapping.
            val lower = exchanges.maxOf { it.minimumOffsetNs }
            val upper = exchanges.minOf { it.maximumOffsetNs }
            require(lower <= upper) { "Inconsistent clock epochs or drift; align again" }
            val width = Math.subtractExact(upper, lower)
            val offset = Math.addExact(lower, width / 2)
            val reference = exchanges.last()
            val watchOrigin = reference.watchReceiveNs + (reference.watchSendNs - reference.watchReceiveNs) / 2
            return ClockAlignment(watchOrigin, Math.addExact(watchOrigin, offset),
                width / 2 + width % 2, phoneClockId, watchClockId)
        }
    }
}
