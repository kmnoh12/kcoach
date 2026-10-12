package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.hardware.SensorManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeartRateTest {
    private fun field(s: WearWorkoutService, name: String) = WearWorkoutService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(s: WearWorkoutService, name: String, value: Any?) = field(s, name).set(s, value)
    private fun get(s: WearWorkoutService, name: String) = field(s, name).get(s)
    private fun call(s: WearWorkoutService, name: String) = WearWorkoutService::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(s)
    private fun service(): WearWorkoutService {
        val s = Robolectric.buildService(WearWorkoutService::class.java).get()
        set(s, "sensorManager", RuntimeEnvironment.getApplication().getSystemService(SensorManager::class.java))
        val sink = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) { override fun execute(command: Runnable) {} }
        set(s, "outboundExecutor", sink); set(s, "captureTransferExecutor", sink)
        return s
    }
    private fun command(type: String, session: String = "session-new", revision: Long = 1) = Intent()
        .putExtra("command", type).putExtra("session", session).putExtra("exerciseId", "external_rotation")
        .putExtra("exerciseName", "외회전").putExtra("setIndex", 0).putExtra("targetReps", 10)
        .putExtra("restSeconds", 90).putExtra("commandRevision", revision)
    private fun start(s: WearWorkoutService) { s.onStartCommand(command("START"), 0, 1) }

    @Test fun startAndRestDoNotInventAHeartRate() {
        val s = service(); start(s)
        assertEquals(0, get(s, "heartRate"))
        s.onStartCommand(command("REST", revision = 2), 0, 2)
        assertEquals(0, get(s, "heartRate"))
    }
    @Test fun measuredHeartRateBelowSixtyIsPreservedDuringRest() {
        val s = service(); start(s); set(s, "heartRate", 48)
        s.onStartCommand(command("REST", revision = 2), 0, 2)
        assertEquals(48, get(s, "heartRate"))
    }
    @Test fun runtimeRecoveryWaitsForANewMeasurement() {
        val s = service(); start(s); set(s, "heartRate", 120); call(s, "checkpoint")
        val restored = service(); call(restored, "restoreRuntime")
        assertEquals(0, get(restored, "heartRate"))
    }
    @Test fun invalidReadingsAreUnknownAndValidSensorReadingsArePreserved() {
        val s = service(); start(s)
        val sensor = org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_HEART_RATE)
        fun event(value: Float) {
            val event = android.hardware.SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(1)
            event.sensor = sensor; event.values[0] = value
            s.onSensorChanged(event)
        }
        event(48f); assertEquals(48, get(s, "heartRate"))
        for (value in listOf(0f, Float.NaN, Float.POSITIVE_INFINITY, 300f, -1f)) {
            event(value); assertEquals(0, get(s, "heartRate"))
        }
    }
    @Test fun pauseAndNewSessionWaitForNewHeartRate() {
        val s = service(); start(s); set(s, "heartRate", 120)
        s.onStartCommand(command("PAUSE", revision = 2), 0, 2)
        assertEquals(0, get(s, "heartRate"))
        set(s, "heartRate", 120)
        s.onStartCommand(command("START", "another-session", 3), 0, 3)
        assertEquals(0, get(s, "heartRate"))
    }

}
