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
class CaptureClockStoreTest {
    @Test fun cameraSessionPrefixNeedsAnExplicitUpgradedWatchCapability() {
        val store = CaptureClockStore(RuntimeEnvironment.getApplication())
        fun reply(capture: String, format: String?) {
            val sent = 2_000_000_000L
            val request = store.prepareProbe(capture, "START", sent, "phone", true).getOrThrow()
            val response = ClockProbeProtocol.reply(request, sent - 990_000_000, sent - 988_000_000, "watch", true)
            val capabilities = mapOf("pairedReferenceSupported" to "true", "captureFormats" to "1,2") +
                (format?.let { mapOf("calibrationCameraStateFormat" to it) } ?: emptyMap())
            store.acceptReply(response.copy(payload = response.payload + capabilities), sent + 22_000_000, "phone").getOrThrow()
        }
        reply("calibration-paired-old", null)
        assertFalse(store.calibrationCameraStateSupported("calibration-paired-old"))
        reply("calibration-paired-new", "1")
        assertTrue(store.calibrationCameraStateSupported("calibration-paired-new"))
        reply("calibration-paired-new", null)
        assertFalse(store.calibrationCameraStateSupported("calibration-paired-new"))
    }
    @Test fun explicitlyDeclaredDirectionNeedsSupportFromEveryReplyInOneWatchEpoch() {
        val store = CaptureClockStore(RuntimeEnvironment.getApplication())
        fun reply(id: String, sentNs: Long, format: String?, epoch: String = "watch") {
            val request = store.prepareProbe(id, "START", sentNs, "phone", true).getOrThrow()
            val response = ClockProbeProtocol.reply(request, sentNs - 990_000_000, sentNs - 988_000_000, epoch, true)
            val capabilities = mapOf("pairedReferenceSupported" to "true", "captureFormats" to "1,2") +
                (format?.let { mapOf("startWristContextFormat" to it) } ?: emptyMap())
            store.acceptReply(response.copy(payload = response.payload + capabilities), sentNs + 22_000_000, "phone").getOrThrow()
        }
        reply("old", 2_000_000_000, null)
        assertTrue(store.pairedCaptureSupported("old"))
        assertFalse(store.startWristContextSupported("old"))
        reply("new", 3_000_000_000, "1")
        assertTrue(store.startWristContextSupported("new"))
        reply("new", 4_000_000_000, null)
        assertFalse(store.startWristContextSupported("new"))
        reply("changed", 5_000_000_000, "1")
        reply("changed", 6_000_000_000, "1", "restarted")
        assertFalse(store.startWristContextSupported("changed"))
    }

    @Test fun aComparisonTrialNeedsExplicitSupportForTheBoundPrimaryEngine() {
        val store = CaptureClockStore(RuntimeEnvironment.getApplication())
        fun receive(captureId: String, sentNs: Long, activeSupported: Boolean) {
            val request = store.prepareProbe(captureId, "START", sentNs, "phone", true).getOrThrow()
            val reply = ClockProbeProtocol.reply(request, sentNs - 990_000_000, sentNs - 988_000_000, "watch", true)
            store.acceptReply(reply.copy(payload = reply.payload + mapOf("pairedReferenceSupported" to "true",
                "captureFormats" to "1,2", "patternShadowCaptureSupported" to "true", "patternProfileFormat" to "1",
                "patternActiveBindingSupported" to activeSupported.toString())), sentNs + 22_000_000, "phone").getOrThrow()
        }
        receive("old-watch", 2_000_000_000, false)
        assertTrue(store.patternShadowSupported("old-watch"))
        assertFalse(store.patternActiveSupported("old-watch"))
        receive("new-watch", 3_000_000_000, true)
        assertTrue(CaptureClockStore(RuntimeEnvironment.getApplication()).patternActiveSupported("new-watch"))
    }

    @Test fun captureClockReplyIsPersistedOnceAndAMismatchedOrOldEpochNeverBecomesAlignment() {
        val app = RuntimeEnvironment.getApplication()
        val store = CaptureClockStore(app)
        val request = store.prepareProbe("capture", "START", 2_000_000_000, "phone", true).getOrThrow()
        val reply = ClockProbeProtocol.reply(request, 1_010_000_000, 1_012_000_000, "watch", true)
        assertFalse(store.acceptReply(reply.copy(sessionId = "other"), 2_022_000_000, "phone").isSuccess)
        assertTrue(store.acceptReply(reply, 2_022_000_000, "phone").isSuccess)
        val restored = CaptureClockStore(app)
        assertEquals(1, restored.samples("capture", "START").size)
        assertEquals(10_000_000L, restored.alignment("capture", "START").getOrThrow().uncertaintyNs)
        assertFalse(restored.acceptReply(reply, 2_030_000_000, "phone").isSuccess)
        assertEquals(1, restored.samples("capture", "START").size)
        val next = restored.prepareProbe("capture", "START", 3_000_000_000, "phone", true).getOrThrow()
        assertTrue(restored.acceptReply(ClockProbeProtocol.reply(next, 2_010_000_000, 2_012_000_000, "watch-after-reboot", true),
            3_022_000_000, "phone").isSuccess)
        assertFalse(restored.alignment("capture", "START").isSuccess)
    }
}
