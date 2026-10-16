package dev.kyungmin.kcoach.phone

import android.content.Intent
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutTerminationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun snapshot() = WorkoutSnapshot(sessionId = "task-removed", routineId = "routine", exerciseIndex = 0, setIndex = 1,
        plannedSets = listOf(PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, true, performedReps = 12, repSource = SetRepSource.WATCH),
            PlannedSetSnapshot(0, "external_rotation", 1, 10, 5.0, false)), performedReps = mapOf("external_rotation" to 12),
        phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0, paused = false,
        startedAtMs = 1000, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0, lastCompletedSetKey = null, captureStates = emptyMap())

    @Test fun removingTaskArchivesCompletedTwelveAndStopsService() {
        val store = SessionStore(app); assertTrue(store.saveActive(snapshot()))
        val controller = Robolectric.buildService(PhoneWorkoutService::class.java).create()
        controller.get().onTaskRemoved(Intent())
        assertNull(store.loadActive().getOrThrow())
        assertTrue(store.isSessionClosed("task-removed"))
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertEquals("STOP", WearSync.parse(PhoneCommandOutbox(app).pending()!!)[1])
        val saved = FinishedWorkoutStore(app).evidenceForSessions(setOf("task-removed")).single().snapshot
        assertEquals(12, saved.plannedSets.first().performedReps)
        assertFalse(saved.plannedSets.last().completed)
        assertFalse(WorkoutTermination(app).pending())
        controller.destroy()
    }
    @Test fun ordinaryServiceDestructionKeepsRecoverableSession() {
        SessionStore(app).saveActive(snapshot())
        val controller = Robolectric.buildService(PhoneWorkoutService::class.java).create()
        controller.destroy()
        assertNotNull(SessionStore(app).loadActive().getOrThrow())
        assertTrue(FinishedWorkoutStore(app).evidenceForSessions(setOf("task-removed")).isEmpty())
    }
    @Test fun confirmedStopIsIdempotentAndDoesNotFinishAnActivity() {
        SessionStore(app).saveActive(snapshot())
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        assertTrue(WorkoutTermination(activity).stop(61_000).isSuccess)
        assertTrue(WorkoutTermination(activity).stop(62_000).isSuccess)
        assertFalse(activity.isFinishing)
        assertEquals(1, FinishedWorkoutStore(app).evidenceForSessions(setOf("task-removed")).size)
    }
    @Test fun journalRecoversBeforeRestoringAClosedSession() {
        SessionStore(app).saveActive(snapshot())
        app.getSharedPreferences("workout-termination", 0).edit().putString("session", "task-removed").commit()
        assertTrue(WorkoutTermination(app).recover(61_000).isSuccess)
        assertNull(SessionStore(app).loadActive().getOrThrow())
        assertEquals(12, FinishedWorkoutStore(app).evidenceForSessions(setOf("task-removed")).single().snapshot.plannedSets.first().performedReps)
    }
    @Test fun failedArchiveReturnsFailureKeepsSnapshotAndDurableStopWithoutCrashing() {
        SessionStore(app).saveActive(snapshot())
        app.getSharedPreferences("finished-workouts", 0).edit().putString("session:task-removed", "broken-json").commit()
        val result = WorkoutTermination(app).stop(61_000)
        assertTrue(result.isFailure)
        assertNotNull(SessionStore(app).loadActive().getOrThrow())
        assertTrue(WorkoutTermination(app).pending())
        assertEquals("STOP", WearSync.parse(PhoneCommandOutbox(app).pending()!!)[1])
        app.getSharedPreferences("finished-workouts", 0).edit().remove("session:task-removed").commit()
        assertTrue(WorkoutTermination(app).recover(62_000).isSuccess)
        assertNull(SessionStore(app).loadActive().getOrThrow())
    }
}
