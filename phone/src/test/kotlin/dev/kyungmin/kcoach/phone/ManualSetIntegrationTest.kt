package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualSetIntegrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun snapshot() = WorkoutSnapshot(
        sessionId = "manual-session", routineId = "legs", exerciseIndex = 0, setIndex = 0,
        plannedSets = listOf(
            PlannedSetSnapshot(0, "leg_press", 0, 12, 100.0, false,
                setId = "manual-set", attemptId = "manual-attempt", identityRequired = true),
            PlannedSetSnapshot(0, "leg_press", 1, 12, 100.0, false,
                setId = "next-set", attemptId = "next-attempt", identityRequired = true)),
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0,
        pausedRestRemainingMs = 0, paused = false, startedAtMs = 1,
        pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
        lastCompletedSetKey = null, captureStates = emptyMap(),
    )
    private fun command(reps: Int = 0) = ManualSetCommand("manual-command", "manual-session", "leg_press", 0,
        "manual-set", "manual-attempt", reps, 0, ManualSetOperation.COMPLETE)

    @Test
    fun manualZeroIsCommittedOnceAndSurvivesStoreRecreation() {
        val store = SessionStore(app)
        assertTrue(store.saveActive(snapshot()))
        val applied = store.applyManualSet(command(), 1_000L).getOrThrow()
        assertEquals(ManualSetStatus.APPLIED, applied.ack.status)
        val saved = SessionStore(app).loadActive().getOrThrow()!!
        assertEquals(0, saved.plannedSets.first().performedReps)
        assertEquals(SetRepSource.MANUAL, saved.plannedSets.first().repSource)
        assertEquals(91_000L, saved.restEndAtMs)
        val replay = SessionStore(app).applyManualSet(command(), 2_000L).getOrThrow()
        assertEquals(ManualSetStatus.DUPLICATE, replay.ack.status)
        assertEquals(91_000L, SessionStore(app).loadActive().getOrThrow()!!.restEndAtMs)
    }

    @Test
    fun phoneEditWinsOverAnOfflineWatchCountUntilTheUserResolvesConflict() {
        val store = SessionStore(app)
        assertTrue(store.saveActive(snapshot()))
        store.completeSet(SetCompletionEvent("manual-session", "leg_press", 0, 8, 91_000, 1,
            setId = "manual-set", attemptId = "manual-attempt")).getOrThrow()
        val conflict = SessionStore(app).applyManualSet(command(12), 2_000).getOrThrow()
        assertEquals(ManualSetStatus.CONFLICT, conflict.ack.status)
        assertEquals(1L, conflict.ack.currentRevision)
        assertEquals(8, conflict.ack.currentReps)
        val resolved = command(10).copy(commandId = "resolved-command", expectedRevision = 1,
            operation = ManualSetOperation.EDIT)
        val edited = SessionStore(app).applyManualSet(resolved, 3_000).getOrThrow()
        assertEquals(ManualSetStatus.APPLIED, edited.ack.status)
        assertEquals(2L, edited.ack.currentRevision)
        assertFalse(edited.restStarted)
        assertEquals(91_000L, edited.snapshot!!.restEndAtMs)
        assertEquals(10, edited.snapshot.performedReps["leg_press"])
    }

    @Test
    fun staleAttemptFutureSetAndReusedCommandIdCannotChangeResults() {
        val store = SessionStore(app)
        assertTrue(store.saveActive(snapshot()))
        assertEquals(ManualSetStatus.STALE, store.applyManualSet(command().copy(attemptId = "previous-attempt"), 1_000).getOrThrow().ack.status)
        assertEquals(ManualSetStatus.REJECTED, store.applyManualSet(command().copy(setIndex = 1,
            setId = "next-set", attemptId = "next-attempt"), 1_000).getOrThrow().ack.status)
        assertFalse(store.loadActive().getOrThrow()!!.plannedSets.first().completed)
        store.applyManualSet(command(7), 1_000).getOrThrow()
        assertEquals(ManualSetStatus.REJECTED, store.applyManualSet(command(9), 2_000).getOrThrow().ack.status)
        assertEquals(7, store.loadActive().getOrThrow()!!.performedReps["leg_press"])
    }

    @Test
    fun watchEstimateCanBeCorrectedWithoutRestartingRestAndCannotOverwriteManualResult() {
        val store = SessionStore(app)
        assertTrue(store.saveActive(snapshot()))
        store.completeWatchSet(SetCompletionEvent("manual-session", "leg_press", 0, 11, 0, 1,
            setId = "manual-set", attemptId = "manual-attempt"), 1_000).getOrThrow()
        val corrected = store.applyManualSet(command(9), 2_000).getOrThrow()
        assertEquals(ManualSetStatus.APPLIED, corrected.ack.status)
        assertFalse(corrected.restStarted)
        assertEquals(91_000L, corrected.snapshot!!.restEndAtMs)
        store.completeWatchSet(SetCompletionEvent("manual-session", "leg_press", 0, 13, 0, 2,
            setId = "manual-set", attemptId = "manual-attempt"), 3_000).getOrThrow()
        assertEquals(9, SessionStore(app).loadActive().getOrThrow()!!.performedReps["leg_press"])
    }

    @Test
    fun backgroundControllerAcknowledgesSavedCountAndOnlyStartsRestOnce() {
        val store = SessionStore(app)
        assertTrue(store.saveActive(snapshot()))
        val messages = mutableListOf<Pair<String, String>>()
        val controller = PhoneSessionController(store) { path, body ->
            assertEquals(6, SessionStore(app).loadActive().getOrThrow()!!.plannedSets.first().performedReps)
            messages.add(path to body)
        }
        val payload = WireCodec.encode(command(6).toEnvelope(100, 1_000))
        controller.receiveManual(payload, 1_000).getOrThrow()
        controller.receiveManual(payload, 2_000).getOrThrow()
        assertEquals(1, messages.count { it.first == WearSync.PATH_COMMAND })
        assertEquals(listOf(ManualSetStatus.APPLIED, ManualSetStatus.DUPLICATE), messages
            .filter { it.first == WearSync.PATH_MANUAL_ACK }.map { ManualSetAck.decode(it.second).status })
    }
}
