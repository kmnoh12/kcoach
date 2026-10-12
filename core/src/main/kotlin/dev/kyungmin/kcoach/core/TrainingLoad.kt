package dev.kyungmin.kcoach.core

/**
 * How big one session looks on the weekly bars.
 *
 * Time saturates at [REFERENCE_MINUTES]: a longer session is usually longer
 * rests or chatting, not more training. Sets count on top, and may exceed the
 * usual [REFERENCE_SETS] by up to [MAX_SET_RATIO] so a high-volume day stands
 * out. The result is the mean of both, so it tops out at (1 + 1.2) / 2.
 */
object TrainingLoad {
    const val REFERENCE_MINUTES = 70
    const val REFERENCE_SETS = 25
    const val MAX_SET_RATIO = 1.2
    const val MAX_SCORE = (1.0 + MAX_SET_RATIO) / 2

    fun score(minutes: Int, sets: Int): Double {
        val time = (minutes.toDouble() / REFERENCE_MINUTES).coerceIn(0.0, 1.0)
        val volume = (sets.toDouble() / REFERENCE_SETS).coerceIn(0.0, MAX_SET_RATIO)
        return (time + volume) / 2
    }

    /** "28일 전" -> 28, "오늘" -> 0, anything else (e.g. "기록 없음") -> null. */
    fun daysAgo(label: String): Int? {
        if (label.trim() == "오늘") return 0
        return Regex("""(\d+)\s*일\s*전""").find(label)?.groupValues?.get(1)?.toIntOrNull()
    }
}
