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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PatternShadowServiceTest {
    @Test fun theRealServiceReportsObservedUseOfTheFrozenComparisonProfileAfterTheFirstDeliveredSample() {
        val app = RuntimeEnvironment.getApplication()
        val runtime = PatternRuntimeProfile("candidate", 1, "a".repeat(64),
            PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE", WearClockEpoch.current(app).deviceId, "watch-native-si-v1"),
            List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0), 3_000_000_000, 1_800_000_000, 4_800_000_000, .2)
        val command = PatternProfileCommand("shadow-command", PatternProfileOperation.SHADOW, runtime, 0)
        val profiles = PatternProfileStore(app)
        profiles.stage(command).getOrThrow()
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent().putExtra("command", "START").putExtra("session", "paired-service")
            .putExtra("exerciseId", "dumbbell_curl").putExtra("exerciseName", "컬")
            .putExtra("setId", "set").putExtra("attemptId", "attempt").putExtra("patternShadowCommandId", command.commandId), 0, 1)
        assertEquals(PatternProfileStatus.STAGED, profiles.stage(command).getOrThrow().status)
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        fun send(type: Int, values: FloatArray) {
            val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(3)
            event.sensor = ShadowSensor.newInstance(type); event.timestamp = SystemClock.elapsedRealtimeNanos()
            event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
            values.copyInto(event.values); service.onSensorChanged(event)
        }
        send(Sensor.TYPE_ACCELEROMETER, floatArrayOf(0f, 0f, 9.8f))
        send(Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, 0f))
        val ack = profiles.stage(command).getOrThrow()
        assertEquals(PatternProfileStatus.APPLIED, ack.status)
        assertEquals("set", ack.application!!.setId)
        assertEquals(runtime, profiles.frozen(runtime.compatibility).getOrThrow()!!.runtime)
        service.onStartCommand(Intent().putExtra("command", "STOP").putExtra("session", "paired-service"), 0, 2)
        val manifestFile = app.filesDir.listFiles()!!.single { it.name.endsWith(".manifest") }
        val manifest = WireCodec.decode(manifestFile.readText())
        val trace = PatternTrialTraceCodec.decode(manifest.payload.getValue("patternShadowTrace"))
        assertEquals(runtime, trace.runtime)
        assertEquals(1L, trace.sampleCalls)
        assertEquals(PatternTrialSource.EMULATED, trace.source)
        assertEquals("set", trace.setId)
        assertFalse(trace.interrupted)
        controller.destroy()
    }
}
