package dev.kyungmin.kcoach.core

/** A comparison condition is an identity, not a translated exercise or routine label. */
data class WorkoutCondition(val exerciseId: String, val equipment: String, val grip: String, val family: String) {
    val missingFields: List<String> get() = listOfNotNull(
        "equipment".takeIf { equipment.isBlank() }, "grip".takeIf { grip.isBlank() }, "family".takeIf { family.isBlank() },
    )
    companion object {
        fun of(set: PlannedSetSnapshot) = WorkoutCondition(set.exerciseId, set.equipment, set.gripType, set.family)
    }
}

data class WorkoutSetTotals(val sets: Int, val reps: Long?, val volumeKgReps: Double?)
data class SameWeightComparison(val weightKg: Double, val previousSets: Int, val currentSets: Int,
    val previousReps: Long, val currentReps: Long) {
    val comparableSetCount: Boolean get() = previousSets == currentSets
}
data class SameRepsComparison(val reps: Int, val previousWeightsKg: List<Double>, val currentWeightsKg: List<Double>)
data class WorkoutConditionComparison(val condition: WorkoutCondition, val previous: WorkoutSetTotals?,
    val current: WorkoutSetTotals, val sameWeight: List<SameWeightComparison>, val sameReps: List<SameRepsComparison>) {
    val setCountChanged: Boolean get() = previous != null && previous.sets != current.sets
    val volumeChange: Double? get() = previous?.volumeKgReps?.let { before -> current.volumeKgReps?.minus(before) }
    val volumeChangePercent: Double? get() = previous?.volumeKgReps?.takeIf { it > 0 }?.let { before ->
        current.volumeKgReps?.let { (it - before) / before * 100 }?.takeIf { it.isFinite() }
    }
}

object WorkoutComparison {
    fun normalGroups(snapshot: WorkoutSnapshot): Map<WorkoutCondition, List<PlannedSetSnapshot>> =
        snapshot.plannedSets.filter { it.completed && it.setType == "NORMAL" }.groupBy(WorkoutCondition::of)

    fun totals(sets: List<PlannedSetSnapshot>): WorkoutSetTotals = WorkoutSetTotals(sets.size,
        sets.takeIf { it.all { set -> set.performedReps != null } }?.sumOf { it.performedReps!!.toLong() },
        sets.takeIf { it.all { set -> set.performedReps != null && set.weightKg != null } }
            ?.sumOf { it.performedReps!! * it.weightKg!! }?.takeIf { it.isFinite() },
    )

    /** Equal timestamps do not establish order, even when session IDs differ. */
    fun previous(current: WorkoutSnapshot, records: List<WorkoutSnapshot>): WorkoutSnapshot? {
        if (records.any { it.sessionId != current.sessionId && it.routineId == current.routineId && it.startedAtMs == current.startedAtMs }) return null
        val earlier = records.filter { it.sessionId != current.sessionId && it.routineId == current.routineId && it.startedAtMs < current.startedAtMs }
        val time = earlier.maxOfOrNull { it.startedAtMs } ?: return null
        return earlier.filter { it.startedAtMs == time }.singleOrNull()
    }

    fun compare(current: WorkoutSnapshot, previous: WorkoutSnapshot?): List<WorkoutConditionComparison> {
        val before = previous?.let(::normalGroups).orEmpty()
        return normalGroups(current).map { (condition, sets) ->
            val prior = before[condition]
            val oldMeasured = prior.orEmpty().filter { it.performedReps != null && it.weightKg != null }
            val newMeasured = sets.filter { it.performedReps != null && it.weightKg != null }
            // Partial groups cannot establish like-for-like repetition or weight progress.
            val fullyMeasured = prior != null && oldMeasured.size == prior.size && newMeasured.size == sets.size
            val sameWeight = if (!fullyMeasured) emptyList() else {
                val old = oldMeasured.groupBy { it.weightKg!! }; val new = newMeasured.groupBy { it.weightKg!! }
                (old.keys intersect new.keys).sorted().map { kg -> SameWeightComparison(kg, old.getValue(kg).size,
                    new.getValue(kg).size, old.getValue(kg).sumOf { it.performedReps!!.toLong() },
                    new.getValue(kg).sumOf { it.performedReps!!.toLong() }) }
            }
            val sameReps = if (!fullyMeasured) emptyList() else {
                val old = oldMeasured.groupBy { it.performedReps!! }; val new = newMeasured.groupBy { it.performedReps!! }
                (old.keys intersect new.keys).sorted().mapNotNull { reps ->
                    val oldWeights = old.getValue(reps).map { it.weightKg!! }.sorted()
                    val newWeights = new.getValue(reps).map { it.weightKg!! }.sorted()
                    if (oldWeights.size == newWeights.size) SameRepsComparison(reps, oldWeights, newWeights) else null
                }
            }
            WorkoutConditionComparison(condition, prior?.let(::totals), totals(sets), sameWeight, sameReps)
        }
    }
}
