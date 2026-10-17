package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneRecordDeliveryTest {
    private fun snapshot() = WorkoutSnapshot(sessionId = "s", routineId = "r", routineName = "routine",
        exerciseIndex = 0, setIndex = 0, plannedSets = listOf(PlannedSetSnapshot(0, "leg_press", 0, 10, 45.0,
            true, setId = "a", attemptId = "1", performedReps = 10)), performedReps = mapOf("leg_press" to 10),
        phase = WorkoutPhase.RESTING, restEndAtMs = 60_000, pausedRestRemainingMs = 0, paused = false,
        startedAtMs = 1, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 1,
        lastCompletedSetKey = "s:a:1", captureStates = emptyMap())

    @Test fun bannerHitIsEncodedOnceAndRetriedOnlyDuringItsOwnRest() {
        val app = RuntimeEnvironment.getApplication()
        val store = SessionStore(app)
        val snapshot = snapshot()
        assertTrue(store.saveActive(snapshot))
        val tracker = RecordBannerTracker()
        val hit = tracker.pick(listOf(RecordHit("leg_press", RecordKind.SAME_WEIGHT_REPS, 12.0, 10.0, 40.0),
            RecordHit("leg_press", RecordKind.HEAVIEST_WEIGHT, 45.0, 40.0)))!!
        val record = SetRecordCommand.fromHit("s", "a", "1", hit)
        PhoneWorkoutService.send(app, WearSync.PATH_SET_RECORD, record.encode())
        val outbound = shadowOf(app).nextStartedService
        assertEquals(WearSync.PATH_SET_RECORD, outbound.getStringExtra(PhoneWorkoutService.EXTRA_PATH))
        assertEquals(record.encode(), outbound.getStringExtra(PhoneWorkoutService.EXTRA_PAYLOAD))
        val outbox = PhoneRecordOutbox(app)
        assertEquals(record.encode(), outbox.enqueue(record.encode()))
        assertNull(outbox.enqueue(record.encode()))
        assertEquals(record, SetRecordCommand.decode(PhoneRecordOutbox(app).pending()!!))
        assertEquals(RecordKind.HEAVIEST_WEIGHT, record.kind)
        assertEquals(45.0, record.weightKg!!, 0.0)
        assertTrue(store.saveActive(snapshot.copy(phase = WorkoutPhase.WORKING)))
        assertNull(outbox.pending())
        assertTrue(store.saveActive(snapshot))
        assertNull(outbox.pending())
    }

    @Test fun changedCompletedSetOrSessionDropsOfflineRecord() {
        val app = RuntimeEnvironment.getApplication()
        val store = SessionStore(app)
        val snapshot = snapshot()
        val record = SetRecordCommand.fromHit("s", "a", "1", RecordHit("leg_press", RecordKind.HEAVIEST_WEIGHT, 45.0, 40.0))
        for ((index, changed) in listOf(snapshot.copy(lastCompletedSetKey = "s:b:1"), snapshot.copy(sessionId = "other")).withIndex()) {
            assertTrue(store.saveActive(snapshot))
            val outbox = PhoneRecordOutbox(app)
            assertNotNull(outbox.enqueue(record.copy(commandId = "record-$index").encode()))
            assertTrue(store.saveActive(changed))
            assertNull(outbox.pending())
        }
    }
}
