package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdversarialReviewTest {
    private fun field(s: WearWorkoutService, n: String) = WearWorkoutService::class.java.getDeclaredField(n).apply { isAccessible = true }
    private fun set(s: WearWorkoutService, n: String, v: Any?) = field(s, n).set(s, v)
    private fun get(s: WearWorkoutService, n: String) = field(s, n).get(s)
    private fun call(s: WearWorkoutService, n: String) = WearWorkoutService::class.java.getDeclaredMethod(n).apply { isAccessible = true }.invoke(s)
    private fun service(): WearWorkoutService {
        val s = Robolectric.buildService(WearWorkoutService::class.java).get()
        set(s, "sensorManager", RuntimeEnvironment.getApplication().getSystemService(SensorManager::class.java))
        val sink = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) { override fun execute(command: Runnable) {} }
        set(s, "outboundExecutor", sink); set(s, "captureTransferExecutor", sink)
        return s
    }
    private fun command(type: String, revision: Long) = Intent().putExtra("command", type)
        .putExtra("session", "review-session").putExtra("exerciseId", "external_rotation")
        .putExtra("exerciseName", "외회전").putExtra("setIndex", 0).putExtra("targetReps", 20)
        .putExtra("setId", "review-set").putExtra("attemptId", "review-attempt")
        .putExtra("restSeconds", 90).putExtra("commandRevision", revision)

    @Test fun pausedRestResumeMustPreserveSetAndAttemptIds() {
        val s = service()
        s.onStartCommand(command("START", 1), 0, 1)
        s.onStartCommand(command("REST", 2).putExtra("restEndAtMs", System.currentTimeMillis() + 90_000), 0, 2)
        s.onStartCommand(command("PAUSE", 3), 0, 3)
        assertEquals("review-set", get(s, "setId"))
        s.onStartCommand(command("RESUME", 4), 0, 4)
        assertEquals("RESUME cleared set identity", "review-set", get(s, "setId"))
        assertEquals("review-attempt", get(s, "attemptId"))
    }

    @Test fun rebootedSensorClockMustNotSuppressNewRawSamples() {
        android.provider.Settings.Global.putInt(RuntimeEnvironment.getApplication().contentResolver, android.provider.Settings.Global.BOOT_COUNT, 7)
        val s = service()
        s.onStartCommand(command("START", 1), 0, 1)
        val append = WearWorkoutService::class.java.getDeclaredMethod("appendCaptureRecord", SensorCaptureRecord::class.java).apply { isAccessible = true }
        append.invoke(s, SensorCaptureRecord(SensorSample(86_400_000_000_000L, 0f, 0f, 9.8f, 0f, 0f, 0f)))
        set(s, "lastCaptureTimestampNs", 86_400_000_000_000L)
        call(s, "checkpoint")
        android.provider.Settings.Global.putInt(RuntimeEnvironment.getApplication().contentResolver, android.provider.Settings.Global.BOOT_COUNT, 8)
        val restored = service(); call(restored, "restoreRuntime")
        set(restored, "measurementReady", true)
        val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(3)
        event.sensor = ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE)
        event.timestamp = 10_000_000_000L
        restored.onSensorChanged(event)
        call(restored, "checkpoint")
        val archived = RuntimeEnvironment.getApplication().filesDir.listFiles()!!.filter { it.name.startsWith("capture-incomplete-") }
        val retained = archived.sumOf { org.json.JSONObject(it.readText()).getInt("expectedSamples") }
        assertEquals("old and new boot samples must both survive", 2, retained + (get(restored, "captureSampleCount") as Int))
        assertEquals(1, archived.size)
        val oldName = org.json.JSONObject(archived.single().readText()).getJSONArray("chunks").getString(0)
        val old = SensorCaptureFormat.read(java.io.File(RuntimeEnvironment.getApplication().filesDir, oldName).inputStream())
        assertEquals(86_400_000_000_000L, old.records.single().sample.timestampNs)
        val fresh = get(restored, "captureChunkFiles") as List<*>
        val current = SensorCaptureFormat.read((fresh.single() as java.io.File).inputStream())
        assertEquals(10_000_000_000L, current.records.single().sample.timestampNs)
        assertEquals(0, current.validateTimestamps().size)
    }
    private fun record(s: WearWorkoutService, timestamp: Long) {
        val append = WearWorkoutService::class.java.getDeclaredMethod("appendCaptureRecord", SensorCaptureRecord::class.java).apply { isAccessible = true }
        append.invoke(s, SensorCaptureRecord(SensorSample(timestamp, 0f, 0f, 9.8f, 0f, 0f, 0f)))
        set(s, "lastCaptureTimestampNs", timestamp); call(s, "checkpoint")
    }
    private fun gyro(s: WearWorkoutService, timestamp: Long) {
        set(s, "measurementReady", true)
        val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(3)
        event.sensor = ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE); event.timestamp = timestamp
        s.onSensorChanged(event); call(s, "checkpoint")
    }
    @Test fun serviceRecreationWithinTheSameBootKeepsOneContinuousCapture() {
        val app = RuntimeEnvironment.getApplication()
        android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 7)
        val s = service(); s.onStartCommand(command("START", 1), 0, 1); record(s, 1_000_000_000L)
        val restored = service(); call(restored, "restoreRuntime"); gyro(restored, 1_020_000_000L)
        assertEquals(2, get(restored, "captureSampleCount"))
        assertFalse(app.filesDir.listFiles()!!.any { it.name.startsWith("capture-incomplete-") })
    }
    @Test fun bootCountSeparatesStreamsEvenWhenTheNewTimestampIsLarger() {
        val app = RuntimeEnvironment.getApplication()
        android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 7)
        val s = service(); s.onStartCommand(command("START", 1), 0, 1); record(s, 1_000_000_000L)
        android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 8)
        val restored = service(); call(restored, "restoreRuntime"); gyro(restored, 10_000_000_000L)
        assertEquals(1, get(restored, "captureSampleCount"))
        assertEquals(1, app.filesDir.listFiles()!!.count { it.name.startsWith("capture-incomplete-") })
        WearWorkoutService::class.java.getDeclaredMethod("completeSet", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(restored, true)
        assertEquals(2, app.filesDir.listFiles()!!.count { it.name.startsWith("capture-incomplete-") })
        assertFalse("Neither partial stream represents a complete set", app.filesDir.listFiles()!!.any { it.name.endsWith(".manifest") })
    }
    @Test fun legacyRuntimeWithoutBootMetadataAlsoPreservesBothTimeDomains() {
        val app = RuntimeEnvironment.getApplication()
        val s = service(); s.onStartCommand(command("START", 1), 0, 1); record(s, 86_400_000_000_000L)
        val prefs = app.getSharedPreferences("workout-runtime", android.content.Context.MODE_PRIVATE)
        val saved = org.json.JSONObject(prefs.getString("state", null)!!).apply { remove("bootCount"); remove("runtimeElapsedMs") }
        prefs.edit().putString("state", saved.toString()).commit()
        val restored = service(); call(restored, "restoreRuntime"); gyro(restored, 10_000_000_000L)
        assertEquals(1, get(restored, "captureSampleCount"))
        assertEquals(1, app.filesDir.listFiles()!!.count { it.name.startsWith("capture-incomplete-") })
    }

    @Test fun bootChangeIsStillHandledAfterAnInitialChunkRecoveryFailure() {
        val app = RuntimeEnvironment.getApplication()
        android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 7)
        val s = service(); s.onStartCommand(command("START", 1), 0, 1); record(s, 86_400_000_000_000L)
        val chunk = (get(s, "captureChunkFiles") as List<*>).single() as java.io.File
        val original = chunk.readBytes(); chunk.writeBytes(byteArrayOf(0))
        android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 8)
        val restored = service(); call(restored, "restoreRuntime")
        chunk.writeBytes(original)
        restored.onStartCommand(command("SYNC", 2).putExtra("syncPhase", "WORKING"), 0, 2)
        gyro(restored, 10_000_000_000L)
        assertEquals(1, app.filesDir.listFiles()!!.count { it.name.startsWith("capture-incomplete-") })
        assertEquals(1, get(restored, "captureSampleCount"))
    }

}
