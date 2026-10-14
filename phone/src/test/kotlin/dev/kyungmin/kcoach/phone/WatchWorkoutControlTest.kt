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
class WatchWorkoutControlTest {
    private fun snapshot() = WorkoutSnapshot(sessionId = "watch-session", routineId = "routine", routineName = "수동 루틴",
        exerciseIndex = 0, setIndex = 0, plannedSets = listOf(0, 1).map { PlannedSetSnapshot(0, "leg_press", it, 12, 30.0,
            false, exerciseName = "레그 프레스", restSeconds = 60, setId = "set-$it", attemptId = "attempt-$it", identityRequired = true) },
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
        paused = false, startedAtMs = 0, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
        lastCompletedSetKey = null, captureStates = emptyMap())

    @Test fun preparationDoesNotStartTheClockAndObservedRequestStartsTheFrozenPlanOnce() {
        val app = RuntimeEnvironment.getApplication(); val store = SessionStore(app)
        val offer = store.prepareForWatch(snapshot(), 100).getOrThrow()
        assertNull(store.loadActive().getOrThrow())
        val command = WatchWorkoutControl("start", WatchWorkoutAction.START_PREPARED, offer.sessionId, offer.setId,
            offer.attemptId, offer.planHash)
        val sent = mutableListOf<Pair<String, String>>()
        val controller = WatchWorkoutController(app) { path, value -> sent += path to value }
        assertEquals(WatchWorkoutControlStatus.APPLIED, controller.receive(command.encode(), 200).getOrThrow().ack.status)
        assertEquals(200L, store.loadActive().getOrThrow()!!.startedAtMs)
        assertNull(store.preparedForWatch(201).getOrThrow())
        assertEquals("VERIFIED_PATTERN", WearSync.parse(sent.single { it.first == WearSync.PATH_COMMAND }.second)[18])
        assertEquals(WatchWorkoutControlStatus.DUPLICATE, WatchWorkoutController(app) { _, _ -> }
            .receive(command.encode(), 300).getOrThrow().ack.status)
        assertEquals(200L, store.loadActive().getOrThrow()!!.startedAtMs)
    }

    @Test fun stalePlanCameraOwnershipAndExpiredOfferDoNotStartAWorkout() {
        val app = RuntimeEnvironment.getApplication(); val store = SessionStore(app)
        val offer = store.prepareForWatch(snapshot(), 100).getOrThrow()
        fun command(id: String, hash: String = offer.planHash) = WatchWorkoutControl(id, WatchWorkoutAction.START_PREPARED,
            offer.sessionId, offer.setId, offer.attemptId, hash)
        val controller = WatchWorkoutController(app) { _, _ -> }
        assertEquals(WatchWorkoutControlStatus.STALE, controller.receive(command("wrong", "a".repeat(64)).encode(), 200).getOrThrow().ack.status)
        app.getSharedPreferences("calibration-captures", 0).edit().putString("active", "camera").commit()
        assertEquals(WatchWorkoutControlStatus.BUSY, controller.receive(command("camera").encode(), 200).getOrThrow().ack.status)
        assertNull(store.loadActive().getOrThrow())
        app.getSharedPreferences("calibration-captures", 0).edit().remove("active").commit()
        assertEquals(WatchWorkoutControlStatus.NO_PLAN, controller.receive(command("expired").encode(), offer.expiresAtMs + 1).getOrThrow().ack.status)
        assertNull(store.loadActive().getOrThrow())
    }

    @Test fun manualZeroThenNextSetIsAtomicAndOfflineReplayCannotSkipAnotherSet() {
        val app = RuntimeEnvironment.getApplication(); val store = SessionStore(app)
        val offer = store.prepareForWatch(snapshot(), 100).getOrThrow()
        val controller = WatchWorkoutController(app) { _, _ -> }
        controller.receive(WatchWorkoutControl("start", WatchWorkoutAction.START_PREPARED, offer.sessionId,
            offer.setId, offer.attemptId, offer.planHash, "RIGHT", "INSIDE").encode(), 200).getOrThrow()
        assertEquals(WorkoutWearingSelection("RIGHT", "INSIDE"), WorkoutWearingStore(app).selection(offer.sessionId).getOrThrow())
        store.applyManualSet(ManualSetCommand("count", offer.sessionId, "leg_press", 0, "set-0", "attempt-0", 0,
            0, ManualSetOperation.COMPLETE), 300).getOrThrow()
        val next = WatchWorkoutControl("next", WatchWorkoutAction.NEXT_SET, offer.sessionId, "set-1", "attempt-1")
        assertEquals(WatchWorkoutControlStatus.APPLIED, controller.receive(next.encode(), 400).getOrThrow().ack.status)
        val after = store.loadActive().getOrThrow()!!
        assertEquals(WorkoutPhase.WORKING, after.phase)
        assertEquals(0, after.plannedSets[0].performedReps)
        store.applyManualSet(ManualSetCommand("count2", offer.sessionId, "leg_press", 1, "set-1", "attempt-1", 5,
            0, ManualSetOperation.COMPLETE), 500).getOrThrow()
        assertEquals(WatchWorkoutControlStatus.DUPLICATE, WatchWorkoutController(app) { _, _ -> }
            .receive(next.encode(), 600).getOrThrow().ack.status)
        assertEquals(WorkoutPhase.RESTING, store.loadActive().getOrThrow()!!.phase)
        assertEquals(WatchWorkoutControlStatus.REJECTED, controller.receive(next.copy(action = WatchWorkoutAction.NEXT_SET,
            setId = "set-0", attemptId = "attempt-0").encode(), 700).getOrThrow().ack.status)
    }
}
