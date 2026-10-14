package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import com.google.android.gms.wearable.MessageEvent
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
class StartWristContextServiceTest {
    private val declared = StartWristContext("external_rotation", "owner", "LEFT", "OUTSIDE", "ELBOW", "PALM_UP_HORIZONTAL")
    private fun command(context: StartWristContext = declared, revision: Long = 1): Intent {
        val listener = Robolectric.buildService(WearCommandService::class.java).get()
        val base = WearSync.withVerifiedPatternSelection(WearSync.withPatternWearing(
            WearSync.richCommand("START", "session", "external_rotation", "회전", 0, 8, 5.0, 60,
                setId = "set", attemptId = "attempt"), PatternWearingContext("owner", "LEFT", "OUTSIDE")))
        val payload = WearSync.parse(WearSync.withStartWristContext(base, context)).toMutableList().apply { this[10] = revision.toString() }.joinToString("|")
        listener.onMessageReceived(object : MessageEvent {
            override fun getRequestId() = 1
            override fun getPath() = WearSync.PATH_COMMAND
            override fun getSourceNodeId() = "phone"
            override fun getData() = payload.toByteArray()
        })
        return shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
    }
    private fun sample(service: WearWorkoutService) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        val timestamp = SystemClock.elapsedRealtimeNanos()
        for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)) {
            val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(3)
            event.sensor = ShadowSensor.newInstance(type); event.timestamp = timestamp
            event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
            if (type == Sensor.TYPE_ACCELEROMETER) event.values[2] = 9.8f
            service.onSensorChanged(event)
        }
    }
    private fun state() = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }
    @Test fun actualListenerRuntimeAndRawManifestPreserveDeclarationsAndRejectChangesDuringPause() {
        val app = RuntimeEnvironment.getApplication()
        val first = Robolectric.buildService(WearWorkoutService::class.java).create()
        first.get().onStartCommand(command(), 0, 1)
        assertEquals("WORKING_START_UNCHECKED", state().getStringExtra("phase"))
        sample(first.get()); sample(first.get())
        first.get().onStartCommand(Intent().putExtra("command", "PAUSE").putExtra("session", "session").putExtra("commandRevision", 2L), 0, 2)
        first.get().onStartCommand(command(declared.copy(deviceTop = "HAND"), 3), 0, 3)
        assertEquals("PAUSED", state().getStringExtra("phase"))
        assertEquals(2L, app.getSharedPreferences("workout-runtime", 0).getLong("commandRevision", -1))
        first.destroy()
        val restored = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            restored.get().onStartCommand(Intent().putExtra("command", "RESUME").putExtra("session", "session").putExtra("commandRevision", 4L), 0, 4)
            assertEquals("WORKING_START_UNCHECKED", state().getStringExtra("phase"))
            sample(restored.get())
            restored.get().onStartCommand(Intent().putExtra("command", "STOP").putExtra("session", "session").putExtra("commandRevision", 5L), 0, 5)
            val manifest = WireCodec.decode(app.filesDir.listFiles()!!.single { it.name.endsWith(".manifest") }.readText())
            assertEquals(declared, StartWristContextCodec.decode(manifest.payload.getValue("startWristContext")))
            assertEquals(WearClockEpoch.current(app).deviceId, manifest.payload["startWristDeviceId"])
            assertEquals("UNCONFIRMED", manifest.payload["startDirectionStatus"])
            val raw = app.filesDir.listFiles()!!.single { it.name.endsWith(".kcs") }
            assertEquals(3, SensorCaptureFormat.read(raw.inputStream()).sampleCount)
        } finally { restored.destroy() }
    }
    @Test fun authoritativeRestSyncDoesNotRequireAWorkingStartContext() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            controller.get().onStartCommand(command(), 0, 1)
            controller.get().onStartCommand(Intent().putExtra("command", "SYNC").putExtra("session", "session")
                .putExtra("exerciseId", "external_rotation").putExtra("setId", "set").putExtra("attemptId", "attempt")
                .putExtra("syncPhase", "RESTING").putExtra("restSeconds", 60)
                .putExtra("restEndAtMs", System.currentTimeMillis() + 60_000L).putExtra("commandRevision", 2L), 0, 2)
            assertEquals("RESTING", state().getStringExtra("phase"))
        } finally { controller.destroy() }
    }
}
