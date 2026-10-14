package dev.kyungmin.kcoach.phone

import android.os.Looper
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartDirectionAnalysisRecoveryTest {
    @Test fun recoveringAPreparationOnlyCaptureDoesNotPersistAConfirmedZero() {
        val app = RuntimeEnvironment.getApplication()
        val context = StartWristContext("external_rotation", "owner", "LEFT", "OUTSIDE", "ELBOW", "PALM_UP_HORIZONTAL")
        val direction = StartDirectionRuntimeProfile("synthetic-target", 1, StartWristDirectionKey(context, "watch-A"),
            "a".repeat(64), "b".repeat(64), StartPoseGateConfig.forProfile(ExerciseTrackingProfiles.resolve(context.exerciseId))
                .copy(requireSetupMotion = false, maxSampleGapMs = 60, maxAccelerationAgeMs = 40,
                    expectedGravity = StartWristGravityTarget(0.0, 0.0, -1.0, .1)))
        val records = (0..120).map { i ->
            val ns = 1_000_000_000L + i * 20_000_000L
            SensorCaptureRecord(SensorSample(ns, 0f, 0f, 9.80665f, 0f, 0f, 0f, sensorAccuracy = 3),
                accelerationTimestampNs = ns, gyroscopeTimestampNs = ns, receivedElapsedNs = ns + 1_000_000, accelerationAccuracy = 3)
        }
        val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val manifest = WireMessages.captureManifest("preparation", context.exerciseId, "set", "prep-capture", 2, "NONE",
            records.size, 50.0, 50.0, records.first().sample.timestampNs, records.last().sample.timestampNs,
            SensorCaptureFormat.sha256(bytes), bytes.size.toLong(), 0, context.exerciseId, "회전", 0, "attempt").let {
            it.copy(payload = it.payload + mapOf("watchDeviceId" to "watch-A", "startWristContext" to StartWristContextCodec.encode(context),
                "startDirectionSnapshot" to StartDirectionRuntimeCodec.encode(direction), "startDirectionStatus" to "WAITING", "countingStartNs" to ""))
        }
        val inbox = CaptureInbox(app, app.getSharedPreferences("wear-sync", 0))
        inbox.receive(manifest, bytes).getOrThrow()
        val service = Robolectric.buildService(PhoneWearListenerService::class.java).create()
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!inbox.analysisSucceeded("prep-capture") && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                ShadowLooper.getAllLoopers().filter { it.thread.name == "queued-work-looper" }.forEach { shadowOf(it).idle() }
                Thread.yield()
            }
            assertTrue("The real startup recovery must persist the analysis", inbox.analysisSucceeded("prep-capture"))
            val result = SetAnalysisStore(app).loadForSession("preparation").values.single()
            assertNull(result.confirmedReps)
            assertNull(result.phoneReps)
            assertTrue(result.trackingStatus?.contains("준비만") == true)
            assertEquals(records.size, inbox.readCapture("prep-capture").getOrThrow().validated.document.sampleCount)
        } finally { service.destroy() }
    }
}
