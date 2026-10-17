package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.os.SystemClock
import dev.kyungmin.kcoach.core.SensorCaptureFormat
import dev.kyungmin.kcoach.core.PhoneRepAnalyzer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WearSensorQualityServiceTest {
    @Test fun unreliableAccelerationDoesNotIncrementLiveRepetitionsButOriginalSamplesRemainAvailable() {
        val app = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        fun reps(): Int {
            service.onStartCommand(Intent().putExtra("command", "QUERY"), 0, 100)
            return shadowOf(app).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }.getIntExtra("reps", -1)
        }
        fun sample(angle: Double, rate: Double, accelerationQuality: Int) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            val ns = SystemClock.elapsedRealtimeNanos()
            listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE).forEach { type ->
                val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.newInstance(3)
                event.sensor = ShadowSensor.newInstance(type); event.timestamp = ns
                event.accuracy = if (type == Sensor.TYPE_ACCELEROMETER) accelerationQuality else 3
                if (type == Sensor.TYPE_ACCELEROMETER) {
                    event.values[1] = (9.80665 * sin(angle)).toFloat(); event.values[2] = (9.80665 * cos(angle)).toFloat()
                } else event.values[0] = rate.toFloat()
                service.onSensorChanged(event)
            }
        }
        fun cycles(quality: Int) {
            val amplitude = 100 * PI / 180
            repeat(160) { sample(0.0, 0.0, quality) }
            repeat(3) {
                for (i in 1..50) { val p = i / 50.0; sample(amplitude * (1 - cos(PI * p)) / 2, amplitude * PI * sin(PI * p) / 2, quality) }
                for (i in 1..150) { val p = i / 150.0; sample(amplitude * (1 + cos(PI * p)) / 2, -amplitude * PI * sin(PI * p) / 6, quality) }
            }
            repeat(25) { sample(0.0, 0.0, quality) }
        }
        try {
            service.onStartCommand(Intent().putExtra("command", "START").putExtra("session", "quality")
                .putExtra("exerciseId", "barbell_biceps_curl").putExtra("setId", "set").putExtra("attemptId", "attempt")
                .putExtra("targetReps", 10).putExtra("commandRevision", 1L), 0, 1)
            repeat(160) { sample(0.0, 0.0, 3) } // the three-second still start hold, before any quality problem
            cycles(0)
            assertEquals("A reliable gyro must not turn bad acceleration into live counts", 0, reps())
            cycles(3)
            assertEquals("Recovery must still count valid complete cycles", 3, reps())
            service.onStartCommand(Intent().putExtra("command", "STOP").putExtra("session", "quality").putExtra("commandRevision", 2L), 0, 2)
            val document = SensorCaptureFormat.read(app.filesDir.listFiles()!!.single { it.name.endsWith(".kcs") }.inputStream())
            assertTrue(document.records.any { it.accelerationAccuracy == 0 && it.sample.sensorAccuracy == 3 })
            assertTrue(document.records.any { it.accelerationAccuracy == 3 && it.sample.sensorAccuracy == 3 })
            val replay = PhoneRepAnalyzer.analyze(document, "barbell_biceps_curl", watchDetectedReps = 3).result
            assertEquals("Replay must apply the same input-quality policy", 3, replay.phoneReanalyzedReps)
            assertNull("Invalid earlier input does not prove a complete set total", replay.confirmedReps)
        } finally { controller.destroy() }
    }
}
