package dev.kyungmin.kcoach.updates

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OtaProtocolTest {
    private val hash = "b".repeat(64)
    @Test fun persistedCommandsAndStatesKeepExactIdentitySequenceAndTarget() {
        val command = OtaCommand("request", "phone", 9, OtaOperation.INSTALL, "watch", 42, hash)
        assertEquals(command, OtaCommand.decode(command.encode()))
        val state = OtaDeviceState("request", "watch", 17, 9, OtaPhase.USER_ACTION_REQUIRED, 41, "old", 42, "new", hash)
        assertEquals(state, OtaDeviceState.decode(state.encode()))
    }
    @Test fun malformedAndUnboundInstallCommandsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { OtaCommand("request", "phone", 1, OtaOperation.INSTALL) }
        assertThrows(IllegalArgumentException::class.java) { OtaCommand("../request", "phone", 1, OtaOperation.CHECK) }
        assertThrows(IllegalArgumentException::class.java) { OtaCommand("..", "phone", 1, OtaOperation.CHECK) }
        assertThrows(IllegalArgumentException::class.java) { OtaCommand.decode(OtaCommand("r", "p", 1, OtaOperation.CHECK).encode().replace("\"format\":1", "\"format\":2")) }
    }
    @Test fun advertisedTargetsAndInstallerRequestsCannotMasqueradeAsInstalledVersions() {
        assertThrows(IllegalArgumentException::class.java) { OtaDeviceState("r", "w", 1, 1, OtaPhase.INSTALLED, 41, "old", 42, "new", hash) }
        assertThrows(IllegalArgumentException::class.java) { OtaDeviceState("r", "w", 1, 1, OtaPhase.UP_TO_DATE, 41, "old", 42, "new", hash) }
        val state = OtaDeviceState("r", "w", 1, 1, OtaPhase.USER_ACTION_REQUIRED, 41, "old", 42, "new", hash)
        assertTrue(OtaPresentation.summarize(state, null, false, false).message.contains("확인 필요"))
        assertFalse(OtaPresentation.summarize(state, null, false, false).message.contains("설치 확인 ("))
    }
    @Test fun waitingForAnOfflineWatchDoesNotBlockAnAlreadyReadyPhoneUpdate() {
        val phone = OtaDeviceState("r", "p", 1, 1, OtaPhase.READY, 4, "four", 5, "five", hash)
        val watch = OtaDeviceState("r", "w", 1, 1, OtaPhase.QUEUED, 4, "four")
        val ui = OtaPresentation.summarize(phone, watch, true, false)
        assertTrue(ui.readyToInstall); assertFalse(ui.busy)
        assertTrue(ui.message.contains("작업 시작 대기"))
    }
}
