package dev.kyungmin.kcoach.core

data class SetCompletionEvent(
    val sessionId: String,
    val exerciseId: String,
    val setIndex: Int,
    val reps: Int,
    val restEndAtMs: Long,
    val sequence: Long,
    val repSource: SetRepSource = SetRepSource.MANUAL,
    val setId: String = "",
    val attemptId: String = "",
) {
    val completionKey: String = if (setId.isBlank()) "$sessionId:$exerciseId:$setIndex" else "$sessionId:$setId:$attemptId"
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
    fun nextPendingSet(snapshot: WorkoutSnapshot): PlannedSetSnapshot? = snapshot.plannedSets.firstOrNull {
        !it.completed && (it.exerciseIndex > snapshot.exerciseIndex ||
            (it.exerciseIndex == snapshot.exerciseIndex && it.setIndex >= snapshot.setIndex))
    }

    fun advanceAfterRest(snapshot: WorkoutSnapshot, nowMs: Long, skip: Boolean = false): WorkoutSnapshot? {
        // A rest that was paused can still be skipped; skipping also ends the pause.
        val pausedRest = snapshot.paused && snapshot.pausedRestRemainingMs > 0
        if (snapshot.paused && !(skip && pausedRest) || snapshot.phase != WorkoutPhase.RESTING && !pausedRest ||
            (!skip && nowMs < snapshot.restEndAtMs)) return null
        val pending = nextPendingSet(snapshot)
        val finishedIndex = (snapshot.plannedSets.maxOfOrNull { it.exerciseIndex } ?: snapshot.exerciseIndex) + 1
        return snapshot.copy(exerciseIndex = pending?.exerciseIndex ?: finishedIndex, setIndex = pending?.setIndex ?: 0,
            phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0, paused = false, pausedSinceMs = 0,
            totalPausedMs = snapshot.totalPausedMs + if (snapshot.paused) (nowMs - snapshot.pausedSinceMs).coerceAtLeast(0) else 0)
    }

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
        val completedSet = SetIdentity.resolve(snapshot, event.exerciseId, event.setIndex, event.setId, event.attemptId)
            ?: return SetCompletionResult.Rejected(snapshot, when {
                event.setId.isNotBlank() || event.attemptId.isNotBlank() || snapshot.plannedSets.any { it.exerciseId == event.exerciseId && it.setIndex == event.setIndex && it.identityRequired } -> "unmatched_set_identity"
                snapshot.plannedSets.none { it.exerciseId == event.exerciseId } -> "wrong_exercise"
                else -> "invalid_set_index"
            })
        val completionKey = SetIdentity.completionKey(snapshot.sessionId, completedSet)
        if (completedSet.completed) return SetCompletionResult.Duplicate(snapshot)
        if (snapshot.lastCompletedSetKey == completionKey) return SetCompletionResult.Duplicate(snapshot)
        if (event.sequence > 0L && event.sequence <= snapshot.lastSequence) return SetCompletionResult.Rejected(snapshot, "stale_sequence")
        if (completedSet.exerciseIndex != snapshot.exerciseIndex) return SetCompletionResult.Rejected(snapshot, "wrong_exercise")
        if (completedSet.setIndex != snapshot.setIndex) return SetCompletionResult.Rejected(snapshot, "out_of_order_set")
        val matchingSets = snapshot.plannedSets.filter { it.exerciseIndex == completedSet.exerciseIndex }
        val updatedSets = snapshot.plannedSets.map { set ->
            if (set === completedSet) {
                set.copy(
                    performedReps = event.reps,
                    repSource = event.repSource,
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
                setIndex = (completedSet.setIndex + 1).coerceAtMost(maxSetIndex),
                plannedSets = updatedSets,
                performedReps = updatedReps,
                phase = WorkoutPhase.RESTING,
                restEndAtMs = event.restEndAtMs,
                pausedRestRemainingMs = 0L,
                paused = false,
                lastSequence = maxOf(snapshot.lastSequence, event.sequence),
                lastCompletedSetKey = completionKey,
            ),
        )
    }
    /** Confirmed native analysis replaces a watch estimate, never an explicit manual entry. */
    fun confirmReps(snapshot: WorkoutSnapshot, exerciseId: String, setIndex: Int, reps: Int?, setId: String = "", attemptId: String = ""): WorkoutSnapshot {
        if (reps == null || reps < 0) return snapshot
        val set = SetIdentity.resolve(snapshot, exerciseId, setIndex, setId, attemptId)?.takeIf { it.completed }
            ?: return snapshot
        if (set.repSource == SetRepSource.MANUAL) return snapshot
        val updated = snapshot.plannedSets.map {
            if (it === set) it.copy(performedReps = reps, repSource = SetRepSource.PHONE_ANALYSIS) else it
        }
        return snapshot.copy(plannedSets = updated, performedReps = updated.filter { it.completed }
            .groupBy { it.exerciseId }.mapValues { (_, sets) -> sets.sumOf { it.performedReps ?: 0 } })
    }

}
