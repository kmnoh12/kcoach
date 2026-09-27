package dev.kyungmin.kcoach.core

data class SetCompletionEvent(
    val sessionId: String,
    val exerciseId: String,
    val setIndex: Int,
    val reps: Int,
    val restEndAtMs: Long,
    val sequence: Long,
) {
    val completionKey: String = "$sessionId:$exerciseId:$setIndex"
}

sealed interface SetCompletionResult {
    val snapshot: WorkoutSnapshot

    data class Applied(override val snapshot: WorkoutSnapshot) : SetCompletionResult
    data class Duplicate(override val snapshot: WorkoutSnapshot) : SetCompletionResult
    data class Rejected(
        override val snapshot: WorkoutSnapshot,
        val reason: String,
    ) : SetCompletionResult
}

object WorkoutSessionReducer {
    fun completeSet(
        snapshot: WorkoutSnapshot,
        event: SetCompletionEvent,
    ): SetCompletionResult {
        if (event.sessionId != snapshot.sessionId) {
            return SetCompletionResult.Rejected(snapshot, "unknown_session")
        }
        if (event.exerciseId.isBlank() || event.setIndex < 0 || event.reps < 0) {
            return SetCompletionResult.Rejected(snapshot, "invalid_completion_fields")
        }
        if (event.restEndAtMs < 0L || event.sequence < 0L) {
            return SetCompletionResult.Rejected(snapshot, "invalid_completion_timing")
        }
        if (snapshot.lastCompletedSetKey == event.completionKey) {
            return SetCompletionResult.Duplicate(snapshot)
        }
        if (event.sequence > 0L && event.sequence <= snapshot.lastSequence) {
            return SetCompletionResult.Rejected(snapshot, "stale_sequence")
        }
        val matchingSets = snapshot.plannedSets.filter {
            it.exerciseIndex == snapshot.exerciseIndex && it.exerciseId == event.exerciseId
        }
        if (matchingSets.isEmpty()) {
            return SetCompletionResult.Rejected(snapshot, "wrong_exercise")
        }
        val completedSet = matchingSets.firstOrNull { it.setIndex == event.setIndex }
            ?: return SetCompletionResult.Rejected(snapshot, "invalid_set_index")
        if (event.setIndex != snapshot.setIndex && !completedSet.completed) {
            return SetCompletionResult.Rejected(snapshot, "out_of_order_set")
        }
        if (completedSet.completed) {
            return SetCompletionResult.Duplicate(
                snapshot.copy(lastCompletedSetKey = event.completionKey),
            )
        }

        val updatedSets = snapshot.plannedSets.map { set ->
            if (
                set.exerciseIndex == snapshot.exerciseIndex &&
                set.exerciseId == event.exerciseId &&
                set.setIndex == event.setIndex
            ) {
                set.copy(
                    targetReps = maxOf(set.targetReps, event.reps),
                    completed = true,
                )
            } else {
                set
            }
        }
        val maxSetIndex = matchingSets.maxOf { it.setIndex }
        val updatedReps = snapshot.performedReps.toMutableMap().apply {
            this[event.exerciseId] = (this[event.exerciseId] ?: 0) + event.reps
        }
        return SetCompletionResult.Applied(
            snapshot.copy(
                setIndex = (event.setIndex + 1).coerceAtMost(maxSetIndex),
                plannedSets = updatedSets,
                performedReps = updatedReps,
                phase = WorkoutPhase.RESTING,
                restEndAtMs = event.restEndAtMs,
                pausedRestRemainingMs = 0L,
                paused = false,
                lastSequence = maxOf(snapshot.lastSequence, event.sequence),
                lastCompletedSetKey = event.completionKey,
            ),
        )
    }
}
