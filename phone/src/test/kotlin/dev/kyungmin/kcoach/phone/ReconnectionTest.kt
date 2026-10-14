package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconnectionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun plan() = WorkoutSnapshot(sessionId = "reconnect-session", routineId = "shoulders", exerciseIndex = 0, setIndex = 0,
        plannedSets = listOf(PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, false, exerciseName = "외회전", restSeconds = 45),
            PlannedSetSnapshot(0, "external_rotation", 1, 8, 7.5, false, exerciseName = "외회전", restSeconds = 60)),
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0, paused = false,
        startedAtMs = 1, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0, lastCompletedSetKey = null, captureStates = emptyMap())
    private fun completion(token: String = "test-completion") = WearSync.liveState("reconnect-session", "external_rotation", "외회전", 0, 10, 10,
        5.0, 100, "SET_COMPLETE", false, completionId = token)
    private fun controller(commands: MutableList<Pair<String, String>>) = PhoneSessionController(SessionStore(app)) { path, payload -> commands += path to payload }

    @Test fun aMidSetWearingChangeIsRetainedForReviewInsteadOfBeingRetriedForever() {
        val outbox = PhoneCommandOutbox(app)
        val pending = outbox.enqueue(WearSync.richCommand("START", "reconnect-session", "external_rotation", "외회전", 0, 10, 5.0, 45))
        val revision = WearSync.parse(pending)[10]
        assertEquals(CommandAckResult.BLOCKED, outbox.receiveAck("1|reconnect-session|$revision|PATTERN_BINDING_CHANGED|0"))
        val restored = PhoneCommandOutbox(app)
        assertEquals(pending, restored.pending())
        assertNull(restored.pendingForDelivery())
    }

    @Test fun commandRevisionAndPendingCommandSurviveRestartAndOnlyMatchingAckRemovesIt() {
        val first = PhoneCommandOutbox(app)
        val start = first.enqueue(WearSync.command("START", "reconnect-session", "external_rotation", 0, 10))
        val revision = WearSync.parse(start)[10].toLong()
        val restored = PhoneCommandOutbox(app)
        assertEquals(start, restored.pending())
        assertFalse(restored.acknowledge("1|other-session|$revision|APPLIED"))
        assertFalse(restored.acknowledge("1|reconnect-session|${revision - 1}|APPLIED"))
        assertEquals(start, restored.pending())
        assertTrue(restored.acknowledge("1|reconnect-session|$revision|APPLIED"))
        assertNull(restored.pending())
        val next = restored.enqueue(WearSync.command("PAUSE", "reconnect-session", "", 0, 0))
        assertTrue(WearSync.parse(next)[10].toLong() > revision)
    }
    @Test fun completionIsAcknowledgedOnlyAfterDurableCommitAndReplayNeverCountsTwice() {
        val store = SessionStore(app); store.saveActive(plan())
        val commands = mutableListOf<Pair<String, String>>()
        controller(commands).receiveCompletion(completion(), 1_000).getOrThrow()
        assertTrue(store.loadActive().getOrThrow()!!.plannedSets.first().completed)
        assertEquals(listOf(WearSync.PATH_COMMAND, WearSync.PATH_SET_ACK), commands.map { it.first })
        val deadline = store.loadActive().getOrThrow()!!.restEndAtMs
        commands.clear()
        controller(commands).receiveCompletion(completion(), 10_000).getOrThrow()
        assertEquals(listOf(WearSync.PATH_SET_ACK), commands.map { it.first })
        val after = store.loadActive().getOrThrow()!!
        assertEquals(10, after.performedReps["external_rotation"])
        assertEquals(deadline, after.restEndAtMs)
    }
    @Test fun lateCompletionAfterSessionClosedStillGetsAck() {
        val store = SessionStore(app); store.saveActive(plan())
        assertTrue(store.clearActive())
        val commands = mutableListOf<Pair<String, String>>()
        controller(commands).receiveCompletion(completion(), 1_000).getOrThrow()
        assertEquals(listOf(WearSync.PATH_SET_ACK), commands.map { it.first })
        assertNull(store.loadActive().getOrThrow())
    }
    @Test fun failedOrWrongSessionCompletionDoesNotGetAck() {
        val store = SessionStore(app); store.saveActive(plan())
        val commands = mutableListOf<Pair<String, String>>()
        val result = controller(commands).receiveCompletion(completion().replace("reconnect-session", "unrelated-session"), 1_000).getOrThrow()
        assertTrue(result is SetCompletionResult.Rejected)
        assertTrue(commands.isEmpty())
        assertFalse(store.loadActive().getOrThrow()!!.plannedSets.first().completed)
    }
    @Test fun completionTokenDoesNotComparePhoneAndWatchWallClocks() {
        val store = SessionStore(app); store.saveActive(plan().copy(lastSequence = Long.MAX_VALUE))
        val commands = mutableListOf<Pair<String, String>>()
        assertTrue(controller(commands).receiveCompletion(completion(), 1_000).getOrThrow() is SetCompletionResult.Applied)
    }
    @Test fun pausedRestAndWorkingStateResyncFromThePersistedSession() {
        val store = SessionStore(app); store.saveActive(plan().copy(phase = WorkoutPhase.PAUSED, paused = true, pausedRestRemainingMs = 25_000))
        val commands = mutableListOf<Pair<String, String>>()
        controller(commands).resync().getOrThrow()
        val sync = WearSync.parse(commands.single().second)
        assertEquals("SYNC", sync[1]); assertEquals("PAUSED", sync[11]); assertEquals("25000", sync[12])
        assertEquals("외회전", sync[4])
    }
    @Test fun actualListenerRoutesReconnectAndCommandAckWithoutAnActivity() {
        val store = SessionStore(app); store.saveActive(plan().copy(phase = WorkoutPhase.PAUSED, paused = true))
        val listener = Robolectric.buildService(PhoneWearListenerService::class.java).get()
        val receive = PhoneWearListenerService::class.java.getDeclaredMethod("handlePayload", String::class.java, String::class.java).apply { isAccessible = true }
        receive.invoke(listener, WearSync.PATH_RECONNECT, WearSync.watchUiReady())
        val sync = shadowOf(app).nextStartedService.getStringExtra(PhoneWorkoutService.EXTRA_PAYLOAD)!!
        assertEquals("SYNC", WearSync.parse(sync)[1]); assertEquals("PAUSED", WearSync.parse(sync)[11])
        val outbox = PhoneCommandOutbox(app)
        val pending = outbox.enqueue(sync)
        val revision = WearSync.parse(pending)[10]
        receive.invoke(listener, WearSync.PATH_COMMAND_ACK, "1|reconnect-session|$revision|APPLIED")
        assertNull(outbox.pending())
    }
    @Test fun supersededCommandAckCannotClearTheNewerDesiredState() {
        val outbox = PhoneCommandOutbox(app)
        val old = outbox.enqueue(WearSync.command("START", "reconnect-session", "external_rotation", 0, 10))
        val current = outbox.enqueue(WearSync.command("PAUSE", "reconnect-session", "", 0, 0))
        assertFalse(outbox.acknowledge("1|reconnect-session|${WearSync.parse(old)[10]}|APPLIED"))
        assertEquals(current, outbox.pending())
    }
    @Test fun linkDownCoalescesHeartbeatsAndCommandsInsteadOfGrowingTheQueue() {
        val bridge = PhoneWearBridge(app)
        val sink = object : java.util.concurrent.AbstractExecutorService() {
            override fun execute(command: Runnable) {}
            override fun shutdown() {}
            override fun shutdownNow() = mutableListOf<Runnable>()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
        }
        PhoneWearBridge::class.java.getDeclaredField("outboundExecutor").apply { isAccessible = true }.set(bridge, sink)
        repeat(100) { bridge.sendHeartbeat(); bridge.sendRaw(WearSync.PATH_COMMAND, WearSync.command("SET", "reconnect-session", "external_rotation", 0, 10)) }
        val queued = PhoneWearBridge::class.java.getDeclaredField("pendingMessages").apply { isAccessible = true }.get(bridge) as Map<*, *>
        assertEquals(2, queued.size)
        assertNotNull(PhoneCommandOutbox(app).pending())
        bridge.close()
    }
    @Test fun watchTelemetryIsNotRejectedBecauseThePhoneClockIsAhead() {
        val store = SessionStore(app); store.saveActive(plan().copy(lastSequence = Long.MAX_VALUE))
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity::class.java.getDeclaredField("sessionStore").apply { isAccessible = true }.set(activity, store)
        val workout = MainActivity::class.java.getDeclaredMethod("restoreActiveWorkout").apply { isAccessible = true }.invoke(activity)
        MainActivity::class.java.getDeclaredField("activeWorkout").apply { isAccessible = true }.set(activity, workout)
        val line = dev.kyungmin.kcoach.phone.ui.LiveLine("", "test")
        MainActivity::class.java.getDeclaredField("activeLiveStatus").apply { isAccessible = true }.set(activity, line)
        val receive = MainActivity::class.java.getDeclaredMethod("handleLivePayload", String::class.java).apply { isAccessible = true }
        receive.invoke(activity, WearSync.liveState("reconnect-session", "external_rotation", "외회전", 0, 6, 10, 5.0, 100, "WORKING", true))
        assertTrue(line.text.contains("6/10"))
        val prior = line.text
        receive.invoke(activity, WearSync.liveState("reconnect-session", "external_rotation", "외회전", 1, 2, 8, 7.5, 100, "WORKING", true))
        assertEquals(prior, line.text)
    }
    @Test fun completionDelayedDuringPauseCannotResumeTheWorkout() {
        val store = SessionStore(app); store.saveActive(plan().copy(paused = true, phase = WorkoutPhase.PAUSED, pausedSinceMs = 500))
        val commands = mutableListOf<Pair<String, String>>()
        controller(commands).receiveCompletion(completion(), 1_000).getOrThrow()
        val saved = store.loadActive().getOrThrow()!!
        assertTrue(saved.plannedSets.first().completed)
        assertTrue(saved.paused); assertEquals(WorkoutPhase.PAUSED, saved.phase)
        assertEquals(45_000L, saved.pausedRestRemainingMs); assertEquals(0L, saved.restEndAtMs)
        assertEquals(500L, saved.pausedSinceMs)
        val sync = WearSync.parse(commands.first().second)
        assertEquals("SYNC", sync[1]); assertEquals("PAUSED", sync[11])
        assertEquals("45", sync[8])
        assertEquals(WearSync.PATH_SET_ACK, commands.last().first)
    }
}
