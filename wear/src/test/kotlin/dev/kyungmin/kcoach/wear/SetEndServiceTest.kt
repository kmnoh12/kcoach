package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.os.Looper
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetEndServiceTest {
    private fun field(name: String) = WearWorkoutService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun rep(service: WearWorkoutService) = WearWorkoutService::class.java.getDeclaredMethod("recordDetectedRep").apply { isAccessible = true }.invoke(service)
    private fun start(service: WearWorkoutService) {
        service.onStartCommand(Intent().putExtra("command", "START").putExtra("session", "set-end-session")
            .putExtra("exerciseId", "external_rotation").putExtra("targetReps", 10), 0, 1)
        field("measurementReady").set(service, true)
    }
    @Test fun tenThenQuietCompletesWithoutAButtonAndLateRepCannotChangeIt() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        try {
            start(service)
            repeat(10) { rep(service) }
            assertTrue(field("running").getBoolean(service))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3100))
            assertFalse(field("running").getBoolean(service))
            rep(service)
            assertEquals(10, field("reps").getInt(service))
            assertEquals("SET_COMPLETE", WearSync.parse(field("pendingCompletion").get(service) as String)[9])
        } finally { controller.destroy() }
    }
    @Test fun continuousExtraRepsReachTwelveBeforeAutomaticCompletion() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        try {
            start(service)
            repeat(12) {
                rep(service)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
            }
            assertEquals(12, field("reps").getInt(service))
            assertTrue(field("running").getBoolean(service))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2100))
            assertFalse(field("running").getBoolean(service))
            assertEquals("12", WearSync.parse(field("pendingCompletion").get(service) as String)[5])
        } finally { controller.destroy() }
    }
    @Test fun manualExerciseNeverUsesInactivityToComplete() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        try {
            start(service)
            field("trackingProfile").set(service, ExerciseTrackingProfiles.resolve("leg_extension"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4000))
            assertTrue(field("running").getBoolean(service))
            assertEquals(0, field("reps").getInt(service))
        } finally { controller.destroy() }
    }
}
