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
    @Test fun restAndSetEditsPersistResyncAndRejectOtherSessionsOrAttempts() {
        val app = RuntimeEnvironment.getApplication(); val store = SessionStore(app)
        val offer = store.prepareForWatch(snapshot(), 100).getOrThrow()
        val sent = mutableListOf<Pair<String, String>>()
        val controller = WatchWorkoutController(app) { path, value -> sent += path to value }
        controller.receive(WatchWorkoutControl("start", WatchWorkoutAction.START_PREPARED, offer.sessionId,
            offer.setId, offer.attemptId, offer.planHash).encode(), 200).getOrThrow()
        store.applyManualSet(ManualSetCommand("count", offer.sessionId, "leg_press", 0, "set-0", "attempt-0", 8,
            0, ManualSetOperation.COMPLETE), 300).getOrThrow()
        val adjust = WatchWorkoutControl("adjust", WatchWorkoutAction.REST_ADJUST, offer.sessionId, "set-1", "attempt-1",
            deltaSeconds = 10, restSeconds = 70, restEndAtMs = 70_300)
        assertEquals(WatchWorkoutControlStatus.APPLIED, controller.receive(adjust.encode(), 400).getOrThrow().ack.status)
        assertEquals(70_300L, SessionStore(app).loadActive().getOrThrow()!!.restEndAtMs)
        assertEquals(70, store.loadActive().getOrThrow()!!.plannedSets[0].restSeconds)
        assertEquals(WatchWorkoutControlStatus.DUPLICATE, controller.receive(adjust.encode(), 500).getOrThrow().ack.status)
        val previous = WatchWorkoutControl("previous", WatchWorkoutAction.EDIT_SET, offer.sessionId, "set-1", "attempt-1",
            which = "PREVIOUS", reps = 0, weightKg = 35.0)
        controller.receive(previous.encode(), 600).getOrThrow()
        val next = previous.copy(commandId = "edit-next", which = "NEXT", reps = 9, weightKg = 40.0)
        assertEquals(WatchWorkoutControlStatus.APPLIED, controller.receive(next.encode(), 700).getOrThrow().ack.status)
        val saved = SessionStore(app).loadActive().getOrThrow()!!
        assertEquals(0, saved.plannedSets[0].performedReps)
        assertEquals(SetRepSource.MANUAL, saved.plannedSets[0].repSource)
        assertEquals(35.0, saved.plannedSets[0].weightKg!!, 0.0)
        assertEquals(0, saved.performedReps["leg_press"])
        assertEquals(9, saved.plannedSets[1].targetReps)
        assertEquals(40.0, saved.plannedSets[1].weightKg!!, 0.0)
        val sync = WearSync.parse(sent.last { it.first == WearSync.PATH_COMMAND }.second)
        assertEquals("SYNC", sync[1]); assertEquals("9", sync[6]); assertEquals("40.0", sync[7])
        assertEquals("70300", sync[9])
        assertEquals(WatchWorkoutControlStatus.STALE, controller.receive(next.copy(commandId = "wrong-session", sessionId = "other").encode(), 800).getOrThrow().ack.status)
        assertEquals(WatchWorkoutControlStatus.STALE, controller.receive(next.copy(commandId = "wrong-attempt", attemptId = "old").encode(), 800).getOrThrow().ack.status)
        assertEquals(saved, store.loadActive().getOrThrow())
    }

    @Test fun finalRestUsesEmptyNextIdentityAndStillEditsPrevious() {
        val app = RuntimeEnvironment.getApplication(); val store = SessionStore(app)
        val plan = snapshot().copy(plannedSets = snapshot().plannedSets.take(1))
        val offer = store.prepareForWatch(plan, 100).getOrThrow()
        val controller = WatchWorkoutController(app) { _, _ -> }
        controller.receive(WatchWorkoutControl("start", WatchWorkoutAction.START_PREPARED, offer.sessionId,
            offer.setId, offer.attemptId, offer.planHash).encode(), 200).getOrThrow()
        store.applyManualSet(ManualSetCommand("count", offer.sessionId, "leg_press", 0, "set-0", "attempt-0", 8,
            0, ManualSetOperation.COMPLETE), 300).getOrThrow()
        val edit = WatchWorkoutControl("edit", WatchWorkoutAction.EDIT_SET, offer.sessionId, "", "",
            which = "PREVIOUS", reps = 7)
        val result = controller.receive(edit.encode(), 400).getOrThrow()
        assertEquals(WatchWorkoutControlStatus.APPLIED, result.ack.status)
        assertEquals(result.ack, WatchWorkoutControlAck.decode(result.ack.encode()))
        assertEquals(7, store.loadActive().getOrThrow()!!.plannedSets.single().performedReps)
        assertEquals(WatchWorkoutControlStatus.REJECTED,
            controller.receive(edit.copy(commandId = "no-next", which = "NEXT").encode(), 500).getOrThrow().ack.status)
    }

}
