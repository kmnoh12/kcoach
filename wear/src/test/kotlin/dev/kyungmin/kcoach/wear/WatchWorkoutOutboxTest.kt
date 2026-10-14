package dev.kyungmin.kcoach.wear

import android.content.Intent
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchWorkoutOutboxTest {
    @Test fun theActualServiceRetainsOfflineRequestsAcrossRestartAndHeartbeatUntilAMatchingAck() {
        val app = RuntimeEnvironment.getApplication()
        val request = WatchWorkoutControl("request", WatchWorkoutAction.START_PREPARED, "session", "set", "attempt", "a".repeat(64))
        val first = Robolectric.buildService(WearWorkoutService::class.java).create()
        first.get().onStartCommand(Intent().putExtra("command", "WORKOUT_CONTROL_SUBMIT").putExtra("payload", request.encode()), 0, 1)
        assertEquals(listOf(request), WatchWorkoutOutbox(app).pending())
        first.destroy()
        val restored = Robolectric.buildService(WearWorkoutService::class.java).create()
        assertEquals(android.app.Service.START_STICKY, restored.get().onStartCommand(Intent().putExtra("command", "HEARTBEAT"), 0, 2))
        val ack = WatchWorkoutControlAck(request.commandId, request.sessionId, request.setId, request.attemptId, WatchWorkoutControlStatus.APPLIED)
        restored.get().onStartCommand(Intent().putExtra("command", "WORKOUT_CONTROL_ACK").putExtra("payload", ack.copy(attemptId = "old").encode()), 0, 3)
        assertEquals(listOf(request), WatchWorkoutOutbox(app).pending())
        restored.get().onStartCommand(Intent().putExtra("command", "WORKOUT_CONTROL_ACK").putExtra("payload", ack.encode()), 0, 4)
        assertTrue(WatchWorkoutOutbox(app).pending().isEmpty())
        restored.destroy()
    }
    @Test fun offlineStartKeepsExactPlanWearingAndIdentityUntilAMatchingReceipt() {
        val app = RuntimeEnvironment.getApplication(); val outbox = WatchWorkoutOutbox(app)
        val offer = WatchWorkoutOffer("session", "수동 루틴", "레그 프레스", "set", "attempt", "a".repeat(64), 1000)
        assertTrue(outbox.receiveOffer(offer.encode()))
        assertTrue(outbox.supportsControls())
        assertTrue(outbox.receivePhoneHello(WearSync.parse(WearSync.hello("phone"))))
        assertFalse(outbox.supportsControls())
        assertTrue(outbox.receivePhoneHello(WearSync.parse(WearSync.hello("phone") + "|WORKOUT_CONTROL_V1")))
        assertTrue(outbox.supportsControls())
        val command = WatchWorkoutControl("request", WatchWorkoutAction.START_PREPARED, "session", "set", "attempt", offer.planHash, "RIGHT", "INSIDE")
        assertTrue(outbox.enqueue(command))
        assertEquals(listOf(command), WatchWorkoutOutbox(app).pending())
        assertFalse(outbox.enqueue(command.copy(commandId = "another")))
        val ack = WatchWorkoutControlAck("request", "session", "set", "attempt", WatchWorkoutControlStatus.APPLIED)
        assertFalse(outbox.acknowledge(ack.copy(attemptId = "old-attempt")))
        assertEquals(listOf(command), outbox.pending())
        assertTrue(outbox.acknowledge(ack))
        assertTrue(outbox.acknowledge(ack.copy(status = WatchWorkoutControlStatus.DUPLICATE)))
        assertTrue(WatchWorkoutOutbox(app).pending().isEmpty())
        assertNull(outbox.offer())
        assertFalse(outbox.acknowledge(ack.copy(status = WatchWorkoutControlStatus.STALE)))
    }
}
