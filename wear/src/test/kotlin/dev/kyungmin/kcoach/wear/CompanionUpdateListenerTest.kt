package dev.kyungmin.kcoach.wear

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
    @Test fun actualWatchListenerPersistsThePhoneInstallRequestButNeverCallsItInstalled() {
        val app = RuntimeEnvironment.getApplication()
        val c = OtaCoordinator.get(app); val (code, name) = c.installed()
        c.store.begin(OtaCommand("request", "phone", 1, OtaOperation.CHECK),
            OtaDeviceState("request", c.store.instance(), 1, 1, OtaPhase.READY, code, name, code + 1, "next", "a".repeat(64)))
        val command = OtaCommand("request", "phone", 2, OtaOperation.INSTALL, c.store.instance(), code + 1, "a".repeat(64))
        val listener = Robolectric.buildService(WearCommandService::class.java).get()
        listener.onMessageReceived(object : MessageEvent {
            override fun getRequestId() = 1
            override fun getPath() = OtaDataLayer.COMMAND_PATH
            override fun getSourceNodeId() = "phone-node"
            override fun getData() = command.encode().toByteArray()
        })
        assertEquals(command, OtaStore(app).command())
        assertEquals(OtaPhase.USER_ACTION_REQUIRED, c.store.local()!!.phase)
        assertEquals(code, c.store.local()!!.installedCode)
    }
}
