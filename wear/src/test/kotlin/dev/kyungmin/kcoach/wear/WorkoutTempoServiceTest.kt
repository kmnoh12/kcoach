package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.os.SystemClock
import dev.kyungmin.kcoach.core.*
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
import java.io.ByteArrayOutputStream
import java.time.Duration
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutTempoServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun activateCounter() {
        val key = PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE", WearClockEpoch.current(app).deviceId, "watch-native-si-v1")
        val records = (0..450).map { i ->
            val ns = 1_000_000_000L + i * 20_000_000L; val p = (i % 150) / 150.0
            SensorCaptureRecord(SensorSample(ns, (4 * (1 - cos(2 * PI * p))).toFloat(), 0f, 9.8f,
                (1.2 * sin(2 * PI * p)).toFloat(), 0f, 0f, sensorAccuracy = 3), accelerationTimestampNs = ns,
                gyroscopeTimestampNs = ns, receivedElapsedNs = ns + 1_000_000, accelerationAccuracy = 3)
        }
        val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val references = (0..2).map { i -> RepReference("synthetic-fit", "set", "attempt",
            RepCycle(1_000_000_000L + i * 3_000_000_000, 2_500_000_000L + i * 3_000_000_000,
                4_000_000_000L + i * 3_000_000_000, MusclePhase.CONCENTRIC), ReferenceSource.REVIEWED_VIDEO,
            5_000_000, true, true, 100, revision = 1) }
        val profile = ReviewedPatternBuilder.build(listOf(ReviewedPatternSet("synthetic-fit", SensorCaptureFormat.sha256(bytes),
            "dumbbell_curl", key, "2026-10-15", SensorCaptureDocument(2, 50.0, records), references)), "candidate", 1).getOrThrow()
        PatternProfileStore(app).stage(PatternProfileCommand("synthetic-activate", PatternProfileOperation.ACTIVATE,
            PatternRuntimeProfile.from(profile), 0, PatternValidationEvidence("a".repeat(64), "b".repeat(64)))).getOrThrow()
    }
    private fun cycle(service: WearWorkoutService) {
        for (i in 0..170) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            val ns = SystemClock.elapsedRealtimeNanos(); val p = if (i <= 150) i / 150.0 else 0.0
            for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)) {
                val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(3)
                event.sensor = ShadowSensor.newInstance(type); event.timestamp = ns; event.accuracy = 3
                if (type == Sensor.TYPE_ACCELEROMETER) { event.values[0] = (4 * (1 - cos(2 * PI * p))).toFloat(); event.values[2] = 9.8f }
                else event.values[0] = (1.2 * sin(2 * PI * p)).toFloat()
                service.onSensorChanged(event)
            }
        }
    }
    private fun state(service: WearWorkoutService): Intent {
        service.onStartCommand(Intent().putExtra("command", "QUERY"), 0, 50)
        return shadowOf(app).broadcastIntents.last { it.action == WearWorkoutService.ACTION_STATE }
    }
    private fun start() = Intent().putExtra("command", "START").putExtra("session", "tempo-session")
        .putExtra("exerciseId", "dumbbell_curl").putExtra("setId", "set").putExtra("attemptId", "attempt")
        .putExtra("patternSelectionMode", "VERIFIED_PATTERN").putExtra("patternWearing", PatternWearingContext("owner", "LEFT", "OUTSIDE").encode())
        .putExtra("tempoSettings", WorkoutTempoSettingsCodec.encode(WorkoutTempoSettings("dumbbell_curl", true,
            mapOf(WorkoutTempoMetric.REPETITION to WorkoutTempoRange(200, 500)), 2, 1_000)))

    @Test fun liveValidatedCyclesAlertOnlyAfterTheStreakAndTheUserCanMuteThisAttempt() {
        activateCounter()
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(start(), 0, 1)
            cycle(service)
            assertEquals("", state(service).getStringExtra("tempoHint"))
            cycle(service)
            assertTrue(state(service).getStringExtra("tempoHint")!!.contains("목표보다 느림"))
            service.onStartCommand(Intent().putExtra("command", "TEMPO_MUTE").putExtra("session", "tempo-session")
                .putExtra("setId", "set").putExtra("attemptId", "attempt"), 0, 2)
            assertFalse(state(service).getBooleanExtra("tempoEnabled", true))
            cycle(service)
            assertEquals("이번 세트 템포 알림 꺼짐", state(service).getStringExtra("tempoHint"))
            assertEquals(3, state(service).getIntExtra("reps", -1))
        } finally { controller.destroy() }
    }
    @Test fun anUnvalidatedBasicCounterDoesNotInventTempoFeedback() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            controller.get().onStartCommand(start(), 0, 1)
            repeat(3) { cycle(controller.get()) }
            assertEquals("", state(controller.get()).getStringExtra("tempoHint"))
        } finally { controller.destroy() }
    }
    @Test fun restRecoveryDoesNotApplyThePreviousExercisesTempoAsTheNewExercisesSettings() {
        val first = Robolectric.buildService(WearWorkoutService::class.java).create()
        first.get().onStartCommand(start(), 0, 1)
        first.get().onStartCommand(Intent().putExtra("command", "REST").putExtra("session", "tempo-session")
            .putExtra("exerciseId", "dumbbell_shoulder_press").putExtra("setId", "press-set").putExtra("attemptId", "press-attempt")
            .putExtra("restSeconds", 60).putExtra("restEndAtMs", System.currentTimeMillis() + 60_000), 0, 2)
        first.destroy()
        val restored = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            assertEquals("RESTING", state(restored.get()).getStringExtra("phase"))
            restored.get().onStartCommand(Intent().putExtra("command", "SET").putExtra("session", "tempo-session")
                .putExtra("exerciseId", "dumbbell_shoulder_press").putExtra("setId", "press-set").putExtra("attemptId", "press-attempt")
                .putExtra("tempoSettings", WorkoutTempoSettingsCodec.encode(WorkoutTempoSettings("dumbbell_shoulder_press"))), 0, 3)
            assertFalse(state(restored.get()).getBooleanExtra("tempoEnabled", true))
        } finally { restored.destroy() }
    }
}
