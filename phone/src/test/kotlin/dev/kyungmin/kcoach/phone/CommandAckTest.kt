package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.WearSync
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandAckTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Test fun temporaryRejectionsRemainDurableUntilTheSameCommandIsActuallyApplied() {
        for (reason in listOf("WAITING_COMPLETION", "CAPTURE_RECOVERY_REQUIRED")) {
            val outbox = PhoneCommandOutbox(app)
            val pending = outbox.enqueue(WearSync.command("START", "session", "external_rotation", 0, 10))
            val ack = "1|session|${WearSync.parse(pending)[10]}|$reason"
            assertFalse(outbox.acknowledge(ack))
            assertEquals(pending, PhoneCommandOutbox(app).pending())
            assertTrue(outbox.acknowledge(ack.substringBeforeLast('|') + "|APPLIED"))
            assertNull(outbox.pending())
        }
    }
    @Test fun invalidClosedOrUnknownRejectionsDoNotPretendThatStartWasApplied() {
        for (reason in listOf("INVALID", "CLOSED_SESSION", "UNKNOWN_STATUS", "WRONG_SESSION", "STALE")) {
            val outbox = PhoneCommandOutbox(app)
            val pending = outbox.enqueue(WearSync.command("START", "session", "external_rotation", 0, 10))
            assertFalse(outbox.acknowledge("1|session|${WearSync.parse(pending)[10]}|$reason"))
            assertEquals(pending, outbox.pending())
        }
    }
    @Test fun malformedMatchingAckCannotClearACommand() {
        val outbox = PhoneCommandOutbox(app)
        val pending = outbox.enqueue(WearSync.command("START", "session", "external_rotation", 0, 10))
        assertFalse(outbox.acknowledge("1|session|${WearSync.parse(pending)[10]}|APPLIED|garbage"))
        assertEquals(pending, outbox.pending())
    }
    @Test fun permanentRejectionsRemainInspectableWithoutBeingResentForever() {
        for (reason in listOf("INVALID", "CLOSED_SESSION", "STALE")) {
            val outbox = PhoneCommandOutbox(app)
            val pending = outbox.enqueue(WearSync.command("START", "session", "external_rotation", 0, 10))
            assertEquals(CommandAckResult.BLOCKED, outbox.receiveAck("1|session|${WearSync.parse(pending)[10]}|$reason"))
            assertEquals(pending, PhoneCommandOutbox(app).pending())
            assertNull(PhoneCommandOutbox(app).pendingForDelivery())
            val replacement = outbox.enqueue(WearSync.command("START", "new-session", "external_rotation", 0, 10))
            assertEquals(replacement, outbox.pendingForDelivery())
        }
    }
    @Test fun waitingCompletionRetainsACommandEligibleForDeliveryAndDuplicateClearsIt() {
        val outbox = PhoneCommandOutbox(app)
        val pending = outbox.enqueue(WearSync.command("START", "session", "external_rotation", 0, 10))
        val prefix = "1|session|${WearSync.parse(pending)[10]}|"
        assertEquals(CommandAckResult.RETRY, outbox.receiveAck(prefix + "WAITING_COMPLETION"))
        assertEquals(pending, PhoneCommandOutbox(app).pendingForDelivery())
        assertTrue(outbox.acknowledge(prefix + "DUPLICATE"))
        assertNull(outbox.pending())
    }
    @Test fun stoppingAnAlreadyClosedSessionIsSatisfiedButStartingItIsNot() {
        val outbox = PhoneCommandOutbox(app)
        val pending = outbox.enqueue(WearSync.command("STOP", "session", "", 0, 0))
        assertTrue(outbox.acknowledge("1|session|${WearSync.parse(pending)[10]}|CLOSED_SESSION"))
        assertNull(outbox.pending())
    }
    @Test fun stalePeerRevisionProducesANewerSyncOfTheActualPhoneState() {
        val snapshot = dev.kyungmin.kcoach.core.WorkoutSnapshot(sessionId = "session", routineId = "routine", exerciseIndex = 0, setIndex = 0,
            plannedSets = listOf(dev.kyungmin.kcoach.core.PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, false)),
            performedReps = emptyMap(), phase = dev.kyungmin.kcoach.core.WorkoutPhase.PAUSED, restEndAtMs = 0, pausedRestRemainingMs = 0, paused = true,
            startedAtMs = 1, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0, lastCompletedSetKey = null, captureStates = emptyMap())
        SessionStore(app).saveActive(snapshot)
        val outbox = PhoneCommandOutbox(app)
        val pending = outbox.enqueue(WearSync.command("START", "session", "external_rotation", 0, 10))
        val listener = org.robolectric.Robolectric.buildService(PhoneWearListenerService::class.java).get()
        PhoneWearListenerService::class.java.getDeclaredMethod("handlePayload", String::class.java, String::class.java).apply { isAccessible = true }
            .invoke(listener, WearSync.PATH_COMMAND_ACK, "1|session|${WearSync.parse(pending)[10]}|STALE|120")
        val sync = WearSync.parse(outbox.pending()!!)
        assertEquals("SYNC", sync[1]); assertEquals("PAUSED", sync[11])
        assertEquals("121", sync[10]); assertNotNull(outbox.pendingForDelivery())
    }
    @Test fun unknownOrInconsistentStatusCannotDisableOrConfirmTheCommand() {
        val outbox = PhoneCommandOutbox(app)
        val pending = outbox.enqueue(WearSync.command("START", "session", "external_rotation", 0, 10))
        val prefix = "1|session|${WearSync.parse(pending)[10]}|"
        for (suffix in listOf("UNKNOWN", "APPLIED|999", "APPLIED|-1", "STALE|9223372036854775807")) {
            assertEquals(CommandAckResult.IGNORED, outbox.receiveAck(prefix + suffix))
            assertEquals(pending, outbox.pendingForDelivery())
        }
    }

    @Test fun aPermanentRejectionNotifiesThePhoneWithoutDroppingItsPendingIntent() {
        val outbox = PhoneCommandOutbox(app)
        val pending = outbox.enqueue(WearSync.command("START", "session", "external_rotation", 0, 10))
        var reason: String? = null
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) { reason = intent.getStringExtra("reason") }
        }
        app.registerReceiver(receiver, android.content.IntentFilter(PhoneWearListenerService.ACTION_COMMAND_REJECTED), android.content.Context.RECEIVER_NOT_EXPORTED)
        try {
            val listener = org.robolectric.Robolectric.buildService(PhoneWearListenerService::class.java).get()
            PhoneWearListenerService::class.java.getDeclaredMethod("handlePayload", String::class.java, String::class.java).apply { isAccessible = true }
                .invoke(listener, WearSync.PATH_COMMAND_ACK, "1|session|${WearSync.parse(pending)[10]}|CLOSED_SESSION")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("CLOSED_SESSION", reason)
            assertEquals(pending, outbox.pending()); assertNull(outbox.pendingForDelivery())
        } finally { app.unregisterReceiver(receiver) }
    }

    @Test fun collidingPeerRevisionAlsoRequiresANewerCommandInsteadOfFalseSuccess() {
        val outbox = PhoneCommandOutbox(app)
        val pending = outbox.enqueue(WearSync.command("START", "new-session", "external_rotation", 0, 10))
        val revision = WearSync.parse(pending)[10]
        assertEquals(CommandAckResult.RESYNC, outbox.receiveAck("1|new-session|$revision|STALE|$revision"))
        assertEquals(pending, outbox.pending()); assertNull(outbox.pendingForDelivery())
        val retry = outbox.enqueue(WearSync.command("START", "new-session", "external_rotation", 0, 10))
        assertTrue(WearSync.parse(retry)[10].toLong() > revision.toLong())
    }

}
