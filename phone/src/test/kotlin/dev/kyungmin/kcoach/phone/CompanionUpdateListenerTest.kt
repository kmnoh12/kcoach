package dev.kyungmin.kcoach.phone

import com.google.android.gms.wearable.MessageEvent
import dev.kyungmin.kcoach.updates.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompanionUpdateListenerTest {
    @Test fun actualPhoneListenerReceivesWatchStateWithoutReplacingTheWorkoutOutbox() {
        val app = RuntimeEnvironment.getApplication()
        val c = OtaCoordinator.get(app); val (code, name) = c.installed()
        val command = OtaCommand("request", c.store.instance(), 1, OtaOperation.CHECK)
        c.store.begin(command, OtaDeviceState(command.id, c.store.instance(), 1, 1, OtaPhase.UP_TO_DATE, code, name, code))
        app.getSharedPreferences("wear-sync", 0).edit().putString("test-workout-marker", "retain").commit()
        val state = OtaDeviceState("request", "watch", 1, 1, OtaPhase.READY, 33, "0.2.5", 34, "next", "a".repeat(64))
        val listener = Robolectric.buildService(PhoneWearListenerService::class.java).get()
        listener.onMessageReceived(object : MessageEvent {
            override fun getRequestId() = 1
            override fun getPath() = OtaDataLayer.STATE_PATH
            override fun getSourceNodeId() = "watch-node"
            override fun getData() = state.encode().toByteArray()
        })
        assertEquals(state, c.store.peer())
        assertEquals("retain", app.getSharedPreferences("wear-sync", 0).getString("test-workout-marker", null))
        assertTrue(c.ui().readyToInstall)
    }
}
