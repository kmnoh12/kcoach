package dev.kyungmin.kcoach.core

enum class RecordKind { HEAVIEST_WEIGHT, SAME_WEIGHT_REPS, ESTIMATED_ONE_REP_MAX, BEST_VOLUME }

/** One strongest record per exercise/session. Values are reps for SAME_WEIGHT_REPS, kg otherwise. */
data class RecordHit(val exerciseId: String, val kind: RecordKind, val value: Double, val previous: Double)

object PersonalRecords {
    /**
     * Records broken by [today] against everything before it. An exercise with no earlier log has nothing to beat,
     * so the first session never counts as a record.
     */
    fun detect(history: ExerciseHistory, today: ExerciseLog): List<RecordHit> {
        val id = today.exerciseId
        if (history.recent(id, 1).isEmpty()) return emptyList()
        val before = ExerciseHistory(history.all.filter {
            it.exerciseId == id &&
                (if (today.sessionId != null) it.sessionId != today.sessionId else it.epochDay != today.epochDay) &&
                it.epochDay <= today.epochDay &&
                (today.startedAtMs == 0L || it.startedAtMs == 0L || it.startedAtMs < today.startedAtMs)
        })
        if (before.last(id) == null) return emptyList()
        fun eligible(log: ExerciseLog) = log.sets.filter { !it.isWarmup && it.load != ProgressionLoad.TIME && it.completed && it.repsEntered && it.reps > 0 }
        val old = before.all.flatMap(::eligible)
        val current = eligible(today)
        fun recordWeight(set: LoggedSet): Double? = when (set.load) {
            ProgressionLoad.EXTERNAL -> set.weightKg
            ProgressionLoad.WEIGHTED_BODYWEIGHT -> set.bodyWeightKg?.let { it + (set.weightKg ?: 0.0) }
            else -> null
        }
        val oldWeight = old.mapNotNull(::recordWeight).maxOrNull()
        val newWeight = current.mapNotNull(::recordWeight).maxOrNull()
        if (oldWeight != null && newWeight != null && newWeight > oldWeight)
            return listOf(RecordHit(id, RecordKind.HEAVIEST_WEIGHT, newWeight, oldWeight))
        val repHit = current.mapNotNull { set ->
            if (oldWeight != null && (recordWeight(set) ?: 0.0) < oldWeight) return@mapNotNull null
            if (set.load == ProgressionLoad.ASSISTANCE && old.any { it.load == set.load && (it.weightKg ?: 0.0) < (set.weightKg ?: 0.0) }) return@mapNotNull null
            val previous = old.filter { it.load == set.load && it.weightKg == set.weightKg &&
                (set.load != ProgressionLoad.WEIGHTED_BODYWEIGHT || recordWeight(it) == recordWeight(set)) }.maxOfOrNull { it.reps } ?: return@mapNotNull null
            if (set.reps > previous) RecordHit(id, RecordKind.SAME_WEIGHT_REPS, set.reps.toDouble(), previous.toDouble()) else null
        }.maxByOrNull { it.value }
        if (repHit != null) return listOf(repHit)
        fun estimate(set: LoggedSet): Double? {
            val weight = when (set.load) {
                ProgressionLoad.EXTERNAL -> set.weightKg
                ProgressionLoad.WEIGHTED_BODYWEIGHT -> set.bodyWeightKg?.let { it + (set.weightKg ?: 0.0) }
                else -> null
            }
            return EstimatedOneRepMax.epley(weight, set.reps)
        }
        val previous = old.mapNotNull(::estimate).maxOrNull()
        val value = current.mapNotNull(::estimate).maxOrNull()
        return if (previous != null && value != null && value > previous)
            listOf(RecordHit(id, RecordKind.ESTIMATED_ONE_REP_MAX, value, previous)) else emptyList()
    }
}
