package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkoutSessionReducerTest {
    private fun snapshot() = WorkoutSnapshot(
        sessionId = "session-1",
        routineId = "shoulders",
        exerciseIndex = 0,
        setIndex = 0,
        plannedSets = listOf(
            PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, false),
            PlannedSetSnapshot(0, "external_rotation", 1, 10, 5.0, false),
        ),
        performedReps = emptyMap(),
        phase = WorkoutPhase.WORKING,
        restEndAtMs = 0L,
        pausedRestRemainingMs = 0L,
        paused = false,
        startedAtMs = 1L,
        pausedSinceMs = 0L,
        totalPausedMs = 0L,
        lastSequence = 4L,
        lastCompletedSetKey = null,
        captureStates = emptyMap(),
    )

    private val event = SetCompletionEvent(
        sessionId = "session-1",
        exerciseId = "external_rotation",
        setIndex = 0,
        reps = 12,
        restEndAtMs = 60_000L,
        sequence = 5L,
    )

    @Test
    fun `completion atomically advances reps phase and durable idempotency key`() {
        val applied = assertIs<SetCompletionResult.Applied>(
            WorkoutSessionReducer.completeSet(snapshot(), event),
        )

        assertEquals(1, applied.snapshot.setIndex)
        assertEquals(12, applied.snapshot.performedReps["external_rotation"])
        assertEquals(true, applied.snapshot.plannedSets.first().completed)
        assertEquals(10, applied.snapshot.plannedSets.first().targetReps)
        assertEquals(12, applied.snapshot.plannedSets.first().performedReps)
        assertEquals(WorkoutPhase.RESTING, applied.snapshot.phase)
        assertEquals(60_000L, applied.snapshot.restEndAtMs)
        assertEquals(event.completionKey, applied.snapshot.lastCompletedSetKey)
    }

    @Test
    fun `duplicate completion never increments reps twice`() {
        val once = assertIs<SetCompletionResult.Applied>(
            WorkoutSessionReducer.completeSet(snapshot(), event),
        )
        val duplicate = assertIs<SetCompletionResult.Duplicate>(
            WorkoutSessionReducer.completeSet(once.snapshot, event.copy(sequence = 6L)),
        )

        assertEquals(12, duplicate.snapshot.performedReps["external_rotation"])
        assertEquals(1, duplicate.snapshot.plannedSets.count { it.completed })
    }

    @Test
    fun `wrong identity and invalid index preserve the session`() {
        val wrongSession = assertIs<SetCompletionResult.Rejected>(
            WorkoutSessionReducer.completeSet(snapshot(), event.copy(sessionId = "other")),
        )
        val wrongExercise = assertIs<SetCompletionResult.Rejected>(
            WorkoutSessionReducer.completeSet(snapshot(), event.copy(exerciseId = "leg_press")),
        )
        val wrongSet = assertIs<SetCompletionResult.Rejected>(
            WorkoutSessionReducer.completeSet(snapshot(), event.copy(setIndex = 9)),
        )

        assertEquals("unknown_session", wrongSession.reason)
        assertEquals("wrong_exercise", wrongExercise.reason)
        assertEquals("invalid_set_index", wrongSet.reason)
        assertEquals(snapshot(), wrongSet.snapshot)
    }
}
