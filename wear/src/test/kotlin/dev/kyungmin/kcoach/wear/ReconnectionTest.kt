package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.hardware.SensorManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconnectionTest {
    private fun field(s: WearWorkoutService, name: String) = WearWorkoutService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(s: WearWorkoutService, name: String, value: Any?) = field(s, name).set(s, value)
    private fun get(s: WearWorkoutService, name: String) = field(s, name).get(s)
    private fun call(s: WearWorkoutService, name: String) = WearWorkoutService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(s)
    private fun service(): WearWorkoutService {
        val s = Robolectric.buildService(WearWorkoutService::class.java).get()
        set(s, "sensorManager", RuntimeEnvironment.getApplication().getSystemService(SensorManager::class.java))
        val sink = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) { override fun execute(command: Runnable) {} }
        set(s, "outboundExecutor", sink); set(s, "captureTransferExecutor", sink)
        return s
    }
    private fun command(type: String, session: String = "session-new", revision: Long = 1) = Intent()
        .putExtra("command", type).putExtra("session", session).putExtra("exerciseId", "external_rotation")
        .putExtra("exerciseName", "외회전").putExtra("setIndex", 0).putExtra("targetReps", 10)
        .putExtra("restSeconds", 90).putExtra("commandRevision", revision)
    private fun start(s: WearWorkoutService) { s.onStartCommand(command("START"), 0, 1) }

    @Test fun disconnectAndHeartbeatPreserveTheCurrentSetAndReps() {
        val s = service(); start(s); set(s, "reps", 6)
        call(s, "resetForPhoneDisconnect")
        assertEquals("session-new", get(s, "sessionId"))
        assertEquals(6, get(s, "reps"))
        assertEquals(true, get(s, "running"))
        assertEquals(false, get(s, "paired"))
        s.onStartCommand(Intent().putExtra("command", "HEARTBEAT"), 0, 2)
        assertEquals(6, get(s, "reps"))
        assertEquals(true, get(s, "paired"))
    }
    @Test fun oldSessionCommandsCannotPauseRestOrStopTheNewSession() {
        val s = service(); start(s)
        for (type in listOf("PAUSE", "REST", "STOP", "COMPLETE")) s.onStartCommand(command(type, "session-old", 2), 0, 2)
        assertEquals("session-new", get(s, "sessionId"))
        assertEquals(true, get(s, "running"))
        assertEquals(false, get(s, "paused"))
        assertEquals(false, get(s, "resting"))
    }
    @Test fun completionSurvivesServiceRecreationUntilPhoneAcknowledgesIt() {
        val s = service(); start(s); set(s, "reps", 10)
        WearWorkoutService::class.java.getDeclaredMethod("completeSet", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(s, true)
        assertNotNull(get(s, "pendingCompletion"))
        val restored = service(); call(restored, "restoreRuntime")
        assertEquals(get(s, "pendingCompletion"), get(restored, "pendingCompletion"))
        assertEquals("session-new", get(restored, "sessionId"))
    }
    @Test fun duplicateAndOlderCommandsDoNotRestartRestOrSwitchBackToAnOldSession() {
        val s = service(); start(s)
        val deadline = System.currentTimeMillis() + 90_000
        s.onStartCommand(command("REST", revision = 2).putExtra("restEndAtMs", deadline), 0, 2)
        s.onStartCommand(command("REST", revision = 2).putExtra("restEndAtMs", deadline + 30_000), 0, 3)
        assertEquals(deadline, get(s, "restEndAtMs"))
        s.onStartCommand(command("START", "session-next", 3), 0, 4)
        s.onStartCommand(command("START", "session-new", 1), 0, 5)
        assertEquals("session-next", get(s, "sessionId"))
    }
    @Test fun restoredWorkingAndPausedSessionsKeepTheRepCount() {
        val s = service(); start(s); set(s, "reps", 6)
        s.onStartCommand(command("PAUSE", revision = 2), 0, 2)
        val restored = service(); call(restored, "restoreRuntime")
        assertEquals(6, get(restored, "reps")); assertEquals(true, get(restored, "paused"))
        restored.onStartCommand(command("SYNC", revision = 3).putExtra("syncPhase", "WORKING"), 0, 3)
        assertEquals(6, get(restored, "reps")); assertEquals(true, get(restored, "running"))
    }
    @Test fun coldRestSyncOwnsTheSessionAndPausedRestRemainsPaused() {
        val s = service()
        s.onStartCommand(command("SYNC").putExtra("syncPhase", "RESTING").putExtra("restEndAtMs", System.currentTimeMillis() + 45_000), 0, 1)
        assertEquals("session-new", get(s, "sessionId")); assertEquals(true, get(s, "resting"))
        s.onStartCommand(command("SYNC", revision = 2).putExtra("syncPhase", "PAUSED").putExtra("pausedRestRemainingMs", 25_000L), 0, 2)
        assertEquals(true, get(s, "paused")); assertEquals(25_000L, get(s, "pausedRestRemainingMs"))
    }
    @Test fun mismatchedCompletionAckCannotDeleteThePendingEventAndSyncCannotRestartIt() {
        val s = service(); start(s); set(s, "reps", 10)
        WearWorkoutService::class.java.getDeclaredMethod("completeSet", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(s, true)
        val original = get(s, "pendingCompletion") as String
        s.onStartCommand(Intent().putExtra("command", "SET_ACK").putExtra("payload", "1|session-new|external_rotation|0|wrong-token"), 0, 2)
        assertEquals(original, get(s, "pendingCompletion"))
        s.onStartCommand(command("SYNC", revision = 2).putExtra("syncPhase", "WORKING"), 0, 3)
        assertEquals(false, get(s, "running"))
        val f = dev.kyungmin.kcoach.core.WearSync.parse(original)
        val ack = dev.kyungmin.kcoach.core.WearSync.setAck(f[1], f[2], f[4].toInt(), f[16])
        s.onStartCommand(Intent().putExtra("command", "SET_ACK").putExtra("payload", ack), 0, 4)
        assertNull(get(s, "pendingCompletion"))
    }
    @Test fun completedSessionCannotBeResurrectedByADelayedSet() {
        val s = service(); start(s)
        s.onStartCommand(command("COMPLETE", revision = 2), 0, 2)
        s.onStartCommand(command("SET", revision = 3), 0, 3)
        assertEquals("", get(s, "sessionId")); assertEquals(false, get(s, "running"))
    }
    @Test fun actualListenerPreservesSyncMetadataAndRoutesCompletionAck() {
        val s = service(); start(s); set(s, "reps", 6)
        val listener = Robolectric.buildService(WearCommandService::class.java).get()
        val receive = WearCommandService::class.java.getDeclaredMethod("handlePayload", String::class.java, String::class.java).apply { isAccessible = true }
        val sync = dev.kyungmin.kcoach.core.WearSync.richCommand("SYNC", "session-new", "external_rotation", "외회전", 0, 10, 5.0, 90) + "|2|PAUSED|0"
        receive.invoke(listener, dev.kyungmin.kcoach.core.WearSync.PATH_COMMAND, sync)
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(2L, intent.getLongExtra("commandRevision", 0)); assertEquals("PAUSED", intent.getStringExtra("syncPhase"))
        s.onStartCommand(intent, 0, 2)
        assertEquals(true, get(s, "paused")); assertEquals(6, get(s, "reps"))
    }
    @Test fun rawCheckpointsAndRepsSurviveRuntimeRecreation() {
        val s = service(); start(s); set(s, "reps", 6)
        val append = WearWorkoutService::class.java.getDeclaredMethod("appendCaptureRecord", dev.kyungmin.kcoach.core.SensorCaptureRecord::class.java).apply { isAccessible = true }
        repeat(2) { i -> append.invoke(s, dev.kyungmin.kcoach.core.SensorCaptureRecord(dev.kyungmin.kcoach.core.SensorSample(1_000L + i * 20_000_000L, 0f, 0f, 9.8f, 1f, 0f, 0f))) }
        call(s, "checkpoint")
        val restored = service(); call(restored, "restoreRuntime")
        assertEquals(6, get(restored, "reps")); assertEquals(2, get(restored, "captureSampleCount"))
        assertEquals(1, (get(restored, "captureChunkFiles") as List<*>).size)
    }
    @Test fun retryUsesTheSameCompletionTokenAndAcknowledgementTravelsThroughTheListener() {
        val s = service(); start(s); set(s, "reps", 10)
        WearWorkoutService::class.java.getDeclaredMethod("completeSet", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(s, true)
        val pending = get(s, "pendingCompletion") as String
        call(s, "replayCompletion"); assertEquals(pending, get(s, "pendingCompletion"))
        val f = dev.kyungmin.kcoach.core.WearSync.parse(pending)
        val ack = dev.kyungmin.kcoach.core.WearSync.setAck(f[1], f[2], f[4].toInt(), f[16])
        val listener = Robolectric.buildService(WearCommandService::class.java).get()
        WearCommandService::class.java.getDeclaredMethod("handlePayload", String::class.java, String::class.java).apply { isAccessible = true }
            .invoke(listener, dev.kyungmin.kcoach.core.WearSync.PATH_SET_ACK, ack)
        s.onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 2)
        assertNull(get(s, "pendingCompletion"))
        val restored = service(); call(restored, "restoreRuntime")
        assertNull(get(restored, "pendingCompletion"))
    }
    @Test fun heartbeatDoesNotCreateAnIdleForegroundServiceButWakesAnOwnedSession() {
        val listener = Robolectric.buildService(WearCommandService::class.java).get()
        val receive = WearCommandService::class.java.getDeclaredMethod("handlePayload", String::class.java, String::class.java).apply { isAccessible = true }
        receive.invoke(listener, dev.kyungmin.kcoach.core.WearSync.PATH_HEARTBEAT, "1|phone|0")
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        val s = service(); start(s)
        receive.invoke(listener, dev.kyungmin.kcoach.core.WearSync.PATH_HEARTBEAT, "1|phone|1")
        assertEquals("HEARTBEAT", shadowOf(RuntimeEnvironment.getApplication()).nextStartedService.getStringExtra("command"))
    }
    @Test fun anEqualRevisionForADifferentSessionIsNotAProofThatStartWasApplied() {
        val s = service(); start(s)
        val candidate = command("START", "another-session", 1)
        val status = WearWorkoutService::class.java.getDeclaredMethod("commandStatus", Intent::class.java).apply { isAccessible = true }
        assertEquals("STALE", status.invoke(s, candidate))
        assertEquals("DUPLICATE", status.invoke(s, command("START")))
        val restored = service(); call(restored, "restoreRuntime")
        assertEquals("DUPLICATE", status.invoke(restored, command("START")))
        assertEquals("STALE", status.invoke(restored, candidate))
    }
    @Test fun anEqualRevisionForAnotherDesiredStateIsNotAcknowledgedAsApplied() {
        val s = service(); start(s)
        val status = WearWorkoutService::class.java.getDeclaredMethod("commandStatus", Intent::class.java).apply { isAccessible = true }
        assertEquals("STALE", status.invoke(s, command("PAUSE", revision = 1)))
    }

}
