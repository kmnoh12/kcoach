package dev.kyungmin.kcoach.core

/** How hard each body part was worked recently, for the muscle map. */
object BodyLoad {
    /** About this many sets a week on one part counts as fully worked. */
    const val FULL_SETS = 12

    /** Sets per body part over the last [days] days ending at [today], from the logs of every exercise. */
    fun setsByPart(logs: List<ExerciseLog>, partOf: (String) -> String?, today: Long, days: Int = 7): Map<String, Int> =
        logs.filter { it.epochDay in (today - days + 1)..today }
            .mapNotNull { log -> partOf(log.exerciseId)?.let { part -> part to log.sets.count { it.completed && it.repsEntered } } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, counts) -> counts.sum() }

    /** 0..1 per part, 1 meaning [FULL_SETS] or more. */
    fun intensity(sets: Map<String, Int>): Map<String, Double> =
        sets.mapValues { (_, count) -> (count.toDouble() / FULL_SETS).coerceIn(0.0, 1.0) }
}
