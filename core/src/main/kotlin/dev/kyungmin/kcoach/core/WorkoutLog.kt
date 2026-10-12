package dev.kyungmin.kcoach.core

/** One finished workout. [volumeKg] counts weighted sets only. */
data class WorkoutLog(
    val epochDay: Long,
    val title: String,
    val minutes: Int,
    val sets: Int,
    val volumeKg: Int,
    val sessionId: String? = null,
)

object WorkoutLogCodec {
    fun encode(logs: List<WorkoutLog>): String =
        logs.joinToString("\n") { listOf(it.epochDay, it.title.replace('\t', ' ').replace('\n', ' '), it.minutes, it.sets, it.volumeKg).joinToString("\t") + (it.sessionId?.let { id -> "\t$id" } ?: "") }

    fun decode(text: String): List<WorkoutLog> = text.lines().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size !in 5..6) return@mapNotNull null
        WorkoutLog(
            f[0].toLongOrNull() ?: return@mapNotNull null,
            f[1],
            f[2].toIntOrNull() ?: return@mapNotNull null,
            f[3].toIntOrNull() ?: return@mapNotNull null,
            f[4].toIntOrNull() ?: return@mapNotNull null,
            f.getOrNull(5)?.takeIf { it.isNotBlank() },
        )
    }
}

/** Totals for the stats screen. */
data class PeriodStats(val workouts: Int, val minutes: Int, val sets: Int, val volumeKg: Int)

object WorkoutStats {
    fun between(logs: List<WorkoutLog>, firstDay: Long, lastDay: Long): PeriodStats {
        val inRange = logs.filter { it.epochDay in firstDay..lastDay }
        return PeriodStats(inRange.size, inRange.sumOf { it.minutes }, inRange.sumOf { it.sets }, inRange.sumOf { it.volumeKg })
    }

    /** Sets per routine title, most-done first. */
    fun byTitle(logs: List<WorkoutLog>): List<Pair<String, Int>> =
        logs.groupBy { it.title }.map { (title, list) -> title to list.sumOf { it.sets } }.sortedByDescending { it.second }
}
