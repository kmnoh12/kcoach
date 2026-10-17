package dev.kyungmin.kcoach.wear

import android.content.Intent
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchRecordDeliveryTest {
    private fun record(set: String = "a") = SetRecordCommand.fromHit("s", set, "attempt",
        RecordHit("leg_press", RecordKind.HEAVIEST_WEIGHT, 45.0, 40.0))

    @Test fun listenerRoutesLateRecordToServiceBroadcastAndNextWorkingSetClearsIt() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        val app = RuntimeEnvironment.getApplication()
        fun command(type: String, set: String) = Intent().putExtra("command", type).putExtra("session", "s")
            .putExtra("exerciseId", "leg_press").putExtra("setId", set).putExtra("attemptId", "attempt")
        fun latest() = shadowOf(app).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }
        try {
            service.onStartCommand(command("START", "a"), 0, 1)
            assertFalse(latest().hasExtra("recordKind"))
            service.onStartCommand(command("REST", "b"), 0, 2)
            val listener = Robolectric.buildService(WearCommandService::class.java).create()
            try {
                WearCommandService::class.java.getDeclaredMethod("handlePayload", String::class.java, String::class.java)
                    .apply { isAccessible = true }.invoke(listener.get(), WearSync.PATH_SET_RECORD, record().encode())
                val forwarded = shadowOf(app).nextStartedService
                assertEquals("SET_RECORD", forwarded.getStringExtra("command"))
                service.onStartCommand(forwarded, 0, 3)
            } finally { listener.destroy() }
            assertEquals("b", latest().getStringExtra("setId"))
            assertEquals("a", latest().getStringExtra("recordSetId"))
            assertEquals(45.0, latest().getDoubleExtra("recordWeightKg", 0.0), 0.0)
            assertEquals(40.0, latest().getDoubleExtra("recordPreviousWeightKg", 0.0), 0.0)
            assertNotNull(SetRecord.from(latest(), "a"))
            val count = shadowOf(app).broadcastIntents.size
            service.onStartCommand(Intent().putExtra("command", "SET_RECORD").putExtra("payload", record().encode()), 0, 4)
            assertEquals(count, shadowOf(app).broadcastIntents.size)
            assertEquals(1, WatchSessionTally.get(app).finish("s")!!.recordsBroken)
            service.onStartCommand(command("SET", "b"), 0, 5)
            assertFalse(latest().hasExtra("recordKind"))
            service.onStartCommand(Intent().putExtra("command", "SET_RECORD").putExtra("payload", record().encode()), 0, 6)
            assertFalse(latest().hasExtra("recordSetId"))
        } finally { controller.destroy() }
    }

    @Test fun persistedStateRejectsWrongSessionAttemptAndDuplicateSetAndFormatsReps() {
        val app = RuntimeEnvironment.getApplication()
        var state = WatchRecordState(app)
        state.observe("s", "a", "attempt", true)
        assertNull(state.receive(record().copy(sessionId = "other").encode()))
        assertNull(state.receive(record().copy(attemptId = "old").encode()))
        val reps = SetRecordCommand.fromHit("s", "a", "attempt", RecordHit("leg_press", RecordKind.SAME_WEIGHT_REPS, 12.0, 10.0, 40.0))
        assertEquals(reps, state.receive(reps.encode()))
        state = WatchRecordState(app)
        assertNull(state.receive(reps.copy(commandId = "retry").encode()))
        val intent = Intent().also(state::addExtras)
        assertEquals(12, intent.getIntExtra("recordReps", 0))
        assertEquals(10, intent.getIntExtra("recordPreviousReps", 0))
        assertEquals(40.0, intent.getDoubleExtra("recordWeightKg", 0.0), 0.0)
        state.observe("other", "a", "attempt", true)
        assertFalse(Intent().also(state::addExtras).hasExtra("recordKind"))
    }
}
