package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalibrationSessionServiceTest {
    @Test fun pairedCameraFlowsThroughPrepareCountAndConfirmedResultWithoutWorkoutTotals() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get(); val session = "calibration-paired-service"
        fun state() = shadowOf(app).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }
        service.onStartCommand(Intent().putExtra("command", "START").putExtra("session", session)
            .putExtra("exerciseId", "dumbbell_curl").putExtra("exerciseName", "컬")
            .putExtra("setId", "set").putExtra("attemptId", "attempt").putExtra("targetReps", 1), 0, 1)
        assertEquals("BASELINE", state().getStringExtra("phase"))
        assertEquals(3000L, state().getLongExtra("preparationHoldTargetMs", 0))
        service.onStartCommand(Intent().putExtra("command", "CALIBRATION_CAMERA_STATE").putExtra("payload",
            CalibrationCameraState(session, "set", "attempt", 1, true).encode()), 0, 2)
        assertTrue(state().getBooleanExtra("phoneCameraRecording", false))
        repeat(170) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)) {
                val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(3)
                event.sensor = ShadowSensor.newInstance(type); event.timestamp = SystemClock.elapsedRealtimeNanos()
                event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                if (type == Sensor.TYPE_ACCELEROMETER) event.values[2] = 9.8f
                service.onSensorChanged(event)
            }
        }
        service.onStartCommand(Intent().putExtra("command", "QUERY"), 0, 3)
        assertTrue(state().getBooleanExtra("running", false))
        assertTrue(state().getStringExtra("phase")!!.startsWith("WORKING"))
        service.onStartCommand(Intent().putExtra("command", "STOP").putExtra("session", session), 0, 4)
        assertEquals("CALIBRATION_RESULT", state().getStringExtra("phase"))
        assertFalse(state().getBooleanExtra("phoneCameraRecording", true))
        service.onStartCommand(Intent().putExtra("command", "CALIBRATION_CAMERA_STATE").putExtra("payload",
            CalibrationCameraState(session, "set", "attempt", 3, false, 10).encode()), 0, 5)
        assertEquals(10, state().getIntExtra("calibrationConfirmedReps", -1))
        assertEquals(session, state().getStringExtra("session"))
        val manifest = WireCodec.decode(app.filesDir.listFiles()!!.single { it.name.endsWith(".manifest") }.readText())
        assertEquals("PAIRED_REFERENCE", manifest.payload["capturePurpose"])
        assertTrue(manifest.payload.getValue(WirePayloadKeys.SAMPLE_COUNT).toInt() >= 160)
        assertNull(WatchSessionTally.get(app).finish(session))
        controller.destroy()
    }
}
