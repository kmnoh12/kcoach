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
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.time.Duration
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PatternActiveServiceTest {
    @Test fun disablingAcknowledgesActualBasicProcessingWithoutMarkingTheRemovedPatternAsUsed() {
        val app = RuntimeEnvironment.getApplication()
        val key = PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE",
            WearClockEpoch.current(app).deviceId, "watch-native-si-v1")
        val runtime = PatternRuntimeProfile("candidate", 1, "a".repeat(64), key, List(33) { List(6) { 0.0 } },
            List(6) { 1.0 }, listOf(0), 3_000_000_000, 1_800_000_000, 4_800_000_000, .2)
        val profiles = PatternProfileStore(app)
        val evidence = PatternValidationEvidence("c".repeat(64), "d".repeat(64))
        profiles.stage(PatternProfileCommand("activate", PatternProfileOperation.ACTIVATE, runtime, 0, evidence)).getOrThrow()
        val first = profiles.beginSet(key, "previous", "old-set", "old-attempt", WearClockEpoch.current(app).id, true).getOrThrow()!!
        profiles.confirmUsed(first, 10, 11).getOrThrow()
        val disable = PatternProfileCommand("disable", PatternProfileOperation.DISABLE, runtime, 1,
            evidence.copy(previousActiveHash = runtime.candidateHash))
        profiles.stage(disable).getOrThrow()
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent().putExtra("command", "START").putExtra("session", "basic-session")
            .putExtra("exerciseId", "dumbbell_curl").putExtra("setId", "set").putExtra("attemptId", "attempt")
            .putExtra("patternSelectionMode", "VERIFIED_PATTERN")
            .putExtra("patternWearing", PatternWearingContext("owner", "LEFT", "OUTSIDE").encode()), 0, 1)
        assertEquals(PatternProfileStatus.STAGED, profiles.stage(disable).getOrThrow().status)
        repeat(220) { index ->
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)) {
                val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.newInstance(3)
                event.sensor = ShadowSensor.newInstance(type); event.timestamp = SystemClock.elapsedRealtimeNanos()
                event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                if (type == Sensor.TYPE_ACCELEROMETER) event.values[2] = 9.8f
                if (type == Sensor.TYPE_GYROSCOPE && index < 3) event.values[0] = 2f
                service.onSensorChanged(event)
            }
        }
        assertEquals(PatternProfileStatus.DISABLED, profiles.stage(disable).getOrThrow().status)
        service.onStartCommand(Intent().putExtra("command", "STOP").putExtra("session", "basic-session"), 0, 2)
        val manifest = WireCodec.decode(app.filesDir.listFiles()!!.single { it.name.endsWith(".manifest") }.readText())
        assertEquals("", manifest.payload["patternActiveSnapshot"])
        assertEquals("false", manifest.payload["patternActiveUsed"])
        controller.destroy()
    }
    @Test fun aNewConfirmedWorkoutDoesNotSilentlyUseAnOldSelfLabeledCalibration() {
        val app = RuntimeEnvironment.getApplication()
        val legacy = PersonalCalibrationProfile("old-profile", 1, "dumbbell_curl", "standard", "LEFT", "WRIST_BONE", "watch",
            0, 1, .01, .1, .5, 2.0, 1.2, 2_000, .1, 5, 1)
        val listener = Robolectric.buildService(WearCommandService::class.java).get()
        listener.onMessageReceived(object : com.google.android.gms.wearable.MessageEvent {
            override fun getRequestId() = 1
            override fun getPath() = WearSync.PATH_PERSONAL_PROFILE
            override fun getData() = PersonalCalibrationProfileCodec.encode(legacy).toByteArray()
            override fun getSourceNodeId() = "phone"
        })
        val payload = WearSync.withVerifiedPatternSelection(WearSync.richCommand("START", "session", "dumbbell_curl", "컬", 0,
            8, 5.0, 60, setId = "set", attemptId = "attempt"))
        listener.onMessageReceived(object : com.google.android.gms.wearable.MessageEvent {
            override fun getRequestId() = 2
            override fun getPath() = WearSync.PATH_COMMAND
            override fun getData() = payload.toByteArray()
            override fun getSourceNodeId() = "phone"
        })
        val dispatched = shadowOf(app).nextStartedService
        assertEquals("VERIFIED_PATTERN", dispatched.getStringExtra("patternSelectionMode"))
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        controller.get().onStartCommand(dispatched, 0, 1)
        val state = shadowOf(app).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }
        assertEquals(ExerciseTrackingProfiles.resolve("dumbbell_curl", "컬").id, state.getStringExtra("trackingProfileId"))
        assertFalse(state.getBooleanExtra("personalized", true))
        controller.destroy()
    }

    @Test fun normalWearingContextKeepsAnInitiallyAbsentProfileAcrossRestartAndUsesItOnlyInTheNextAttempt() {
        val app = RuntimeEnvironment.getApplication()
        val key = PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE", WearClockEpoch.current(app).deviceId, "watch-native-si-v1")
        val context = PatternWearingContext("owner", "LEFT", "OUTSIDE").encode()
        fun start(set: String, attempt: String) = Intent().putExtra("command", "START").putExtra("session", "paired-context")
            .putExtra("exerciseId", "dumbbell_curl").putExtra("setId", set).putExtra("attemptId", attempt).putExtra("patternWearing", context)
        fun sample(service: WearWorkoutService) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(3)
            event.sensor = ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE); event.timestamp = SystemClock.elapsedRealtimeNanos()
            event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
            service.onSensorChanged(event)
        }
        val first = Robolectric.buildService(WearWorkoutService::class.java).create()
        first.get().onStartCommand(start("set-1", "attempt-1"), 0, 1)
        sample(first.get())
        val runtime = PatternRuntimeProfile("candidate", 1, "a".repeat(64), key, List(33) { List(6) { 0.0 } },
            List(6) { 1.0 }, listOf(0), 3_000_000_000, 1_800_000_000, 4_800_000_000, .2)
        val profiles = PatternProfileStore(app)
        val command = PatternProfileCommand("activate", PatternProfileOperation.ACTIVATE, runtime, 0,
            PatternValidationEvidence("c".repeat(64), "d".repeat(64)))
        profiles.stage(command).getOrThrow()
        first.destroy()
        val restored = Robolectric.buildService(WearWorkoutService::class.java).create()
        restored.get().onStartCommand(start("set-1", "attempt-1"), 0, 2)
        sample(restored.get())
        assertEquals(PatternProfileStatus.STAGED, profiles.stage(command).getOrThrow().status)
        restored.get().onStartCommand(start("set-2", "attempt-2"), 0, 3)
        sample(restored.get())
        assertEquals(PatternProfileStatus.APPLIED, profiles.stage(command).getOrThrow().status)
        assertEquals("attempt-2", profiles.stage(command).getOrThrow().application!!.attemptId)
        restored.get().onStartCommand(Intent().putExtra("command", "STOP").putExtra("session", "paired-context"), 0, 4)
        val manifests = app.filesDir.listFiles()!!.filter { it.name.endsWith(".manifest") }.map { WireCodec.decode(it.readText()) }
        assertEquals("", manifests.single { it.setId == "set-1" }.payload["patternActiveSnapshot"])
        assertEquals(runtime.candidateHash, manifests.single { it.setId == "set-2" }.payload["patternActiveCandidateHash"])
        restored.destroy()
    }

    @Test fun restartingDuringRestDoesNotApplyThePreviousExercisesBindingToTheNextExercise() {
        val app = RuntimeEnvironment.getApplication()
        val key = PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE",
            WearClockEpoch.current(app).deviceId, "watch-native-si-v1")
        val first = Robolectric.buildService(WearWorkoutService::class.java).create()
        first.get().onStartCommand(Intent().putExtra("command", "START").putExtra("session", "session")
            .putExtra("exerciseId", "dumbbell_curl").putExtra("setId", "curl-set").putExtra("attemptId", "curl-attempt")
            .putExtra("patternBinding", PatternCompatibilityCodec.encode(key)), 0, 1)
        first.get().onStartCommand(Intent().putExtra("command", "REST").putExtra("session", "session")
            .putExtra("exerciseId", "dumbbell_shoulder_press").putExtra("setId", "press-set")
            .putExtra("attemptId", "press-attempt").putExtra("restSeconds", 60), 0, 2)
        first.destroy()
        val restored = Robolectric.buildService(WearWorkoutService::class.java).create()
        val state = shadowOf(app).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }
        assertEquals("RESTING", state.getStringExtra("phase"))
        assertEquals("dumbbell_shoulder_press", state.getStringExtra("exerciseId"))
        assertFalse(state.getBooleanExtra("running", true))
        restored.destroy()
    }

    @Test fun aDifferentWearingSelectionCannotUseTheStoredActiveProfile() {
        val app = RuntimeEnvironment.getApplication()
        val key = PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE",
            WearClockEpoch.current(app).deviceId, "watch-native-si-v1")
        val runtime = PatternRuntimeProfile("candidate", 1, "a".repeat(64), key, List(33) { List(6) { 0.0 } },
            List(6) { 1.0 }, listOf(0), 3_000_000_000, 1_800_000_000, 4_800_000_000, .2)
        val profiles = PatternProfileStore(app)
        val command = PatternProfileCommand("activate", PatternProfileOperation.ACTIVATE, runtime, 0,
            PatternValidationEvidence("c".repeat(64), "d".repeat(64)))
        profiles.stage(command).getOrThrow()
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent().putExtra("command", "START").putExtra("session", "paired-other-hand")
            .putExtra("exerciseId", "dumbbell_curl").putExtra("setId", "set").putExtra("attemptId", "attempt")
            .putExtra("patternBinding", PatternCompatibilityCodec.encode(key.copy(side = "RIGHT"))), 0, 1)
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(3)
        event.sensor = ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE); event.timestamp = SystemClock.elapsedRealtimeNanos()
        event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        service.onSensorChanged(event)
        service.onStartCommand(Intent().putExtra("command", "STOP").putExtra("session", "paired-other-hand"), 0, 2)
        val manifest = WireCodec.decode(app.filesDir.listFiles()!!.single { it.name.endsWith(".manifest") }.readText())
        assertEquals("", manifest.payload["patternActiveSnapshot"])
        assertEquals("false", manifest.payload["patternActiveUsed"])
        assertEquals(PatternProfileStatus.STAGED, profiles.stage(command).getOrThrow().status)
        assertNull(profiles.frozen(key, true).getOrThrow())
        controller.destroy()
    }

    @Test fun thePrimaryCounterUsesTheBoundActiveSnapshotAndSavesThatSnapshotWithItsRawData() = activeCapture(false)

    @Test fun pairedCalibrationObservesTheCurrentPersonalBaselineFromTheFirstPreparationSample() = activeCapture(true)

    private fun activeCapture(paired: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        val key = PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE",
            WearClockEpoch.current(app).deviceId, "watch-native-si-v1")
        val records = (0..450).map { index ->
            val t = 1_000_000_000L + index * 20_000_000L
            val p = (index % 150) / 150.0
            SensorCaptureRecord(SensorSample(t, (4 * (1 - cos(2 * PI * p))).toFloat(), 0f, 9.8f,
                (1.2 * sin(2 * PI * p)).toFloat(), 0f, 0f), accelerationTimestampNs = t,
                gyroscopeTimestampNs = t, receivedElapsedNs = t + 1_000_000)
        }
        val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val refs = (0..2).map { index -> RepReference("training", "set", "attempt",
            RepCycle(1_000_000_000L + index * 3_000_000_000L, 2_500_000_000L + index * 3_000_000_000L,
                4_000_000_000L + index * 3_000_000_000L, MusclePhase.CONCENTRIC), ReferenceSource.REVIEWED_VIDEO,
            5_000_000, true, true, 100, revision = 1) }
        val trained = ReviewedPatternBuilder.build(listOf(ReviewedPatternSet("training", SensorCaptureFormat.sha256(bytes),
            "dumbbell_curl", key, "2026-10-14", SensorCaptureDocument(2, 50.0, records), refs)), "candidate", 1).getOrThrow()
        val runtime = PatternRuntimeProfile.from(trained)
        val command = PatternProfileCommand("activate", PatternProfileOperation.ACTIVATE, runtime, 0,
            PatternValidationEvidence("c".repeat(64), "d".repeat(64)))
        val profiles = PatternProfileStore(app)
        profiles.stage(command).getOrThrow()
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        val session = if (paired) "paired-active" else "workout-active"
        service.onStartCommand(Intent().putExtra("command", "START").putExtra("session", session)
            .putExtra("exerciseId", "dumbbell_curl").putExtra("exerciseName", "컬").putExtra("targetReps", 1)
            .putExtra("setId", "set").putExtra("attemptId", "attempt")
            .putExtra("patternWearing", PatternWearingContext(key.localUserId, key.side, key.placement).encode()), 0, 1)
        fun send(type: Int, values: FloatArray) {
            val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(3)
            event.sensor = ShadowSensor.newInstance(type); event.timestamp = SystemClock.elapsedRealtimeNanos()
            event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
            values.copyInto(event.values); service.onSensorChanged(event)
        }
        for (index in 0..170) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            val p = if (index <= 150) index / 150.0 else 0.0
            send(Sensor.TYPE_ACCELEROMETER, floatArrayOf((4 * (1 - cos(2 * PI * p))).toFloat(), 0f, 9.8f))
            send(Sensor.TYPE_GYROSCOPE, floatArrayOf((1.2 * sin(2 * PI * p)).toFloat(), 0f, 0f))
        }
        val applied = profiles.stage(command).getOrThrow()
        assertEquals(PatternProfileStatus.APPLIED, applied.status)
        assertEquals("attempt", applied.application!!.attemptId)
        service.onStartCommand(Intent().putExtra("command", "STOP").putExtra("session", session), 0, 2)
        val manifest = WireCodec.decode(app.filesDir.listFiles()!!.single { it.name.endsWith(".manifest") }.readText())
        if (!paired) assertEquals("1", manifest.payload[WirePayloadKeys.WATCH_REPS])
        assertEquals("1", manifest.payload["patternBaselineCount"])
        assertEquals("171", manifest.payload["patternBaselineSampleCalls"])
        assertEquals(runtime.candidateHash, manifest.payload["patternActiveCandidateHash"])
        assertEquals(runtime, PatternRuntimeCodec.decode(java.util.Base64.getDecoder().decode(manifest.payload.getValue("patternActiveSnapshot"))))
        assertEquals("true", manifest.payload["patternActiveUsed"])
        val raw = app.filesDir.listFiles()!!.single { it.name.endsWith(".kcs") }
        val document = SensorCaptureFormat.read(raw.inputStream())
        assertEquals(171, document.sampleCount)
        assertEquals(1, PhoneRepAnalyzer.analyzePersonal(document, "dumbbell_curl", key, runtime, 1, true, false).result.confirmedReps)
        controller.destroy()
    }
}
