package dev.kyungmin.kcoach.core

data class RestCountdown(
    val remainingSeconds: Int,
    val totalSeconds: Int,
    val remainingFraction: Float,
)

object RestCountdownCalculator {
    fun calculate(nowMs: Long, endAtMs: Long, totalSeconds: Int): RestCountdown {
        val safeTotal = totalSeconds.coerceAtLeast(0)
        if (endAtMs <= 0L || safeTotal == 0) return RestCountdown(0, safeTotal, 0f)
        val remainingMs = (endAtMs - nowMs).coerceIn(0L, safeTotal * 1_000L)
        val remainingSeconds = ((remainingMs + 999L) / 1_000L).toInt()
        return RestCountdown(
            remainingSeconds = remainingSeconds,
            totalSeconds = safeTotal,
            remainingFraction = (remainingMs.toFloat() / (safeTotal * 1_000f)).coerceIn(0f, 1f),
        )
    }
}
