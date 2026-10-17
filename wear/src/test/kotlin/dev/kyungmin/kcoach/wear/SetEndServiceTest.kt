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
    @Test fun editorPauseBlocksRepsAndEndGateAndSurvivesRestart() {
        var controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        var service = controller.get()
        start(service)
        rep(service)
        fun edit(type: String) = Intent().putExtra("command", type).putExtra("session", "set-end-session")
            .putExtra("setId", "set").putExtra("attemptId", "attempt")
        field("setId").set(service, "set"); field("attemptId").set(service, "attempt")
        service.onStartCommand(edit("COUNTING_PAUSE"), 0, 2)
        val detector = field("forearmRotationDetector").get(service) as ForearmRotationRepDetector
        val diagnostics = detector.diagnostics()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        fun sample(type: Int, values: FloatArray) {
            val event = android.hardware.SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(3)
            event.sensor = org.robolectric.shadows.ShadowSensor.newInstance(type)
            event.timestamp = android.os.SystemClock.elapsedRealtimeNanos(); event.accuracy = 3
            values.copyInto(event.values); service.onSensorChanged(event)
        }
        sample(android.hardware.Sensor.TYPE_ACCELEROMETER, floatArrayOf(0f, 0f, 9.8f))
        sample(android.hardware.Sensor.TYPE_GYROSCOPE, floatArrayOf(2f, 0f, 0f))
        assertEquals(diagnostics, detector.diagnostics())
        assertEquals(1, field("captureSampleCount").getInt(service))
        repeat(3) { rep(service) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4000))
        assertEquals(1, field("reps").getInt(service))
        assertTrue(field("running").getBoolean(service))
        controller.destroy()
        controller = Robolectric.buildService(WearWorkoutService::class.java).create(); service = controller.get()
        try {
            assertTrue(field("countingPaused").getBoolean(service))
            rep(service)
            assertEquals(1, field("reps").getInt(service))
            service.onStartCommand(edit("COUNTING_RESUME"), 0, 3)
            assertFalse(field("countingPaused").getBoolean(service))
            assertNotSame(detector, field("forearmRotationDetector").get(service))
            rep(service)
            assertEquals(2, field("reps").getInt(service))
            service.onStartCommand(edit("COUNTING_PAUSE"), 0, 4)
            WearWorkoutService::class.java.getDeclaredMethod("completeSet", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, true)
            assertFalse(field("countingPaused").getBoolean(service))
            assertEquals(2, field("reps").getInt(service))
        } finally { controller.destroy() }
    }

}
