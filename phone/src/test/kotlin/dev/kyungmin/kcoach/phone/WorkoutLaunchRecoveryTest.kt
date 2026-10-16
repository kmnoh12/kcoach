package dev.kyungmin.kcoach.phone

import android.app.ApplicationExitInfo.*
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutLaunchRecoveryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun seed() {
        // Application.onCreate has already run on a real wall clock; use the injected test timeline.
        app.getSharedPreferences("workout-launch-recovery", 0).edit().clear().commit()
        assertTrue(SessionStore(app).saveActive(WorkoutSnapshot(sessionId = "recover", routineId = "routine", exerciseIndex = 0, setIndex = 1,
            plannedSets = listOf(PlannedSetSnapshot(0, "curl", 0, 10, 5.0, true, performedReps = 12),
                PlannedSetSnapshot(0, "curl", 1, 10, 5.0, false)), performedReps = mapOf("curl" to 12),
            phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0, paused = false,
            startedAtMs = 1000, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0, lastCompletedSetKey = null, captureStates = emptyMap())))
    }
    private fun exit(reason: Int, at: Long = 2000, process: String = app.packageName) = WorkoutProcessExit(reason, at, process)

    @Test fun userRequestedDiscardsCompletedSetsBeforeRestoration() {
        seed()
        assertTrue(WorkoutLaunchRecovery(app) { listOf(exit(REASON_USER_REQUESTED)) }.recover(3000).isSuccess)
        assertNull(SessionStore(app).loadActive().getOrThrow())
        assertTrue(FinishedWorkoutStore(app).allEvidence().isEmpty())
        assertTrue(WorkoutLogStore(app).load().isEmpty())
        assertEquals("STOP", WearSync.parse(PhoneCommandOutbox(app).pending()!!)[1])
        assertTrue(WorkoutTermination(app).consumeHomeReset())
        assertFalse(WorkoutTermination(app).consumeHomeReset())
    }
    @Test fun userStoppedDiscards() {
        seed()
        assertTrue(WorkoutLaunchRecovery(app) { listOf(exit(REASON_USER_STOPPED)) }.recover(3000).isSuccess)
        assertNull(SessionStore(app).loadActive().getOrThrow())
    }
    @Test fun crashNativeCrashAnrLowMemorySignaledAndUnknownPreserveRecovery() {
        seed()
        listOf(REASON_CRASH, REASON_CRASH_NATIVE, REASON_ANR, REASON_LOW_MEMORY, REASON_SIGNALED, REASON_UNKNOWN).forEachIndexed { i, reason ->
            val at = 2000L + i * 1000L
            assertTrue(WorkoutLaunchRecovery(app) { listOf(exit(reason, at)) }.recover(at + 500).isSuccess)
            assertEquals(12, SessionStore(app).loadActive().getOrThrow()!!.plannedSets.first().performedReps)
            assertFalse(WorkoutTermination(app).consumeHomeReset())
        }
    }
    @Test fun latestMainProcessDeathWinsOverOlderStopAndOtherProcesses() {
        seed()
        assertTrue(WorkoutLaunchRecovery(app) { listOf(exit(REASON_USER_REQUESTED, 1500), exit(REASON_CRASH),
            exit(REASON_USER_REQUESTED, 2500, app.packageName + ":worker")) }.recover(3000).isSuccess)
        assertNotNull(SessionStore(app).loadActive().getOrThrow())
    }
    @Test fun exitBeforeSessionOrAlreadyCheckedCannotDiscardLater() {
        seed()
        assertTrue(WorkoutLaunchRecovery(app) { listOf(exit(REASON_USER_REQUESTED, 500)) }.recover(3000).isSuccess)
        assertTrue(WorkoutLaunchRecovery(app) { listOf(exit(REASON_USER_REQUESTED)) }.recover(4000).isSuccess)
        assertNotNull(SessionStore(app).loadActive().getOrThrow())
    }
    @Test fun missingOrFailedQueryKeepsSession() {
        seed()
        assertTrue(WorkoutLaunchRecovery(app) { error("unavailable") }.recover(3000).isSuccess)
        assertTrue(WorkoutLaunchRecovery(app) { emptyList() }.recover(4000).isSuccess)
        assertNotNull(SessionStore(app).loadActive().getOrThrow())
    }
    @Test fun hardExitCancelsPendingSaveBeforeArchiveRecovery() {
        seed()
        app.getSharedPreferences("workout-termination", 0).edit().putString("session", "recover")
            .putBoolean("saveCompleted", true).commit()
        assertTrue(WorkoutLaunchRecovery(app) { listOf(exit(REASON_USER_REQUESTED)) }.recover(3000).isSuccess)
        assertNull(SessionStore(app).loadActive().getOrThrow())
        assertTrue(FinishedWorkoutStore(app).allEvidence().isEmpty())
    }
    @Test fun interruptedDiscardIsReplayedEvenWhenExitIsCrash() {
        seed()
        app.getSharedPreferences("workout-termination", 0).edit().putString("session", "recover").putBoolean("saveCompleted", false).commit()
        assertTrue(WorkoutLaunchRecovery(app) { listOf(exit(REASON_CRASH)) }.recover(3000).isSuccess)
        assertNull(SessionStore(app).loadActive().getOrThrow())
        assertTrue(FinishedWorkoutStore(app).allEvidence().isEmpty())
    }
}
