package dev.kyungmin.kcoach.wear

import android.content.Intent
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualWatchFlowTest {
    @Test fun watchActualZeroIsQueuedDurablyAndAHumanCountNeverCreatesAnAutomaticCompletion() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent().putExtra("command", "START").putExtra("session", "session")
            .putExtra("exerciseId", "leg_press").putExtra("exerciseName", "레그프레스")
            .putExtra("setId", "set").putExtra("attemptId", "attempt").putExtra("targetReps", 12), 0, 1)
        val manual = ManualSetCommand("manual", "session", "leg_press", 0, "set", "attempt", 0, 0, ManualSetOperation.COMPLETE)
        service.onStartCommand(Intent().putExtra("command", "MANUAL_SUBMIT")
            .putExtra("payload", WireCodec.encode(manual.toEnvelope(0, 0))), 0, 2)
        assertEquals(listOf(manual), ManualSetOutbox(RuntimeEnvironment.getApplication()).pending())
        val published = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }
        assertEquals("MANUAL_PENDING", published.getStringExtra("phase"))
        assertFalse(published.getBooleanExtra("running", true))
        controller.destroy()
        val restored = Robolectric.buildService(WearWorkoutService::class.java).create()
        restored.get().onStartCommand(Intent().putExtra("command", "MANUAL_ACK").putExtra("payload",
            ManualSetAck("manual", "session", "set", "attempt", ManualSetStatus.APPLIED, 1, 0).encode(0, 0)), 0, 3)
        assertTrue(ManualSetOutbox(RuntimeEnvironment.getApplication()).pending().isEmpty())
        restored.destroy()
    }
}
