package dev.kyungmin.kcoach.wear

import com.google.android.gms.wearable.MessageEvent
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PatternProfileListenerTest {
    @Test fun incomingProfilesAreDurableBeforeAcknowledgingAndDoNotStartAWorkout() {
        val app = RuntimeEnvironment.getApplication()
        val deviceId = WearClockEpoch.current(app).deviceId
        val runtime = PatternRuntimeProfile("candidate", 1, "a".repeat(64),
            PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE", deviceId, "watch-native-si-v1"),
            List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0), 3_000_000_000, 1_800_000_000, 4_800_000_000, .2)
        val command = PatternProfileCommand("listener-command", PatternProfileOperation.SHADOW, runtime, 0)
        val service = Robolectric.buildService(WearCommandService::class.java).get()
        service.onMessageReceived(object : MessageEvent {
            override fun getRequestId() = 1
            override fun getPath() = WearSync.PATH_PATTERN_PROFILE
            override fun getData() = command.encode().toByteArray()
            override fun getSourceNodeId() = "phone-node"
        })
        val replay = PatternProfileStore(app).stage(command).getOrThrow()
        assertTrue("The listener must have durably saved this command", replay.duplicate)
        assertEquals(PatternProfileStatus.STAGED, replay.status)
        assertNull(PatternProfileStore(app).frozen(runtime.compatibility).getOrThrow())
    }
}
