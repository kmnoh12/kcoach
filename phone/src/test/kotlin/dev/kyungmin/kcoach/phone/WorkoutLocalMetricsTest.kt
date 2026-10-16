package dev.kyungmin.kcoach.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutLocalMetricsTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private fun snapshot(id: String, time: Long = 1_000, reps: Int = 8) = WorkoutSnapshot(sessionId = id, routineId = "routine",
        exerciseIndex = 0, setIndex = 0, plannedSets = listOf(PlannedSetSnapshot(0, "flat_bench", 0, 8, 40.0, true,
            exerciseName = "벤치", performedReps = reps, repSource = SetRepSource.MANUAL, setId = "set-$id", attemptId = "attempt-$id")),
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
        paused = false, startedAtMs = time, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
        lastCompletedSetKey = null, captureStates = emptyMap())
    private fun reject(action: () -> Unit) { try { action(); fail("Unsafe input accepted") } catch (_: IllegalArgumentException) {} }
    @Test fun onlyFreshHeartSamplesAreStoredAndGapsAreNotInterpolated() {
        val store = WorkoutVitalsStore(app)
        store.receive("1|s|1000000000|100|0|3|WORKING", 1000)
        store.receive("1|s|3000000000|120|0|3|WORKING", 3000)
        store.receive("1|s|23000000000|150|0|3|RESTING", 23000)
        store.receive("1|s|23000000000|150|0|3|RESTING", 24000)
        reject { store.receive("1|s|25000000000|160|12000|3|WORKING", 25000) }
        reject { store.receive("1|s|25000000000|160|0|0|WORKING", 25000) }
        val read = store.summary("s", 1000, 23000)
        assertEquals(3, read.points.size); assertEquals(100.0, read.average!!, 0.0); assertEquals(150, read.maximum); assertTrue(read.hasGaps)
    }
    @Test fun energyRequiresExplicitAdultBasisAndKnownNonFutureBodyMass() {
        val day = LocalDate.of(2026, 10, 14)
        val start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val record = FinishedWorkoutStore.Evidence(snapshot("energy", start), "routine", 60, 0)
        val store = WorkoutEnergyStore(app)
        assertTrue(store.estimate(record, null).isNull("active_kcal"))
        val p = WorkoutProfile(ageYears = 25, weightKg = 80.0, weightMeasuredOn = "2026-10-14", calorieBasis = "RESISTANCE_MULTIPLE_8_15")
        val value = store.estimate(record, p)
        assertEquals(210.0, value.getDouble("active_kcal"), 0.0001)
        assertTrue(store.estimate(record, p.copy(weightMeasuredOn = "2026-10-15")).isNull("active_kcal"))
        assertTrue(store.estimate(record, p.copy(ageYears = 15)).isNull("active_kcal"))
        assertTrue(store.estimate(record, p.copy(calorieBasis = "")).isNull("active_kcal"))
        assertTrue(store.estimate(record, p.copy(weightMeasuredOn = "2026-01-01")).isNull("active_kcal"))
    }
    @Test fun captureStatusUsesCaptureIdsRatherThanTheNumberOfCompletedSets() {
        val s = snapshot("capture")
        assertEquals(0, WorkoutCaptureStatus(app).read(s).getInt("known_capture_count"))
        val status = WorkoutCaptureStatus(app).read(s.copy(captureStates = mapOf("one" to CaptureState.PENDING_UPLOAD)))
        assertEquals(1, status.getInt("pending")); assertEquals(0, status.getInt("received"))
        assertTrue(status.getString("message").contains("0/1"))
    }
    @Test fun committedPhaseTimelineSurvivesRecreationAndDuplicateBoundaries() {
        val s = snapshot("time", 1000); val store = WorkoutTimeStore(app)
        store.record(s, 1000); store.record(s, 1500)
        store.record(s.copy(phase = WorkoutPhase.RESTING), 3000)
        WorkoutTimeStore(app).record(s.copy(phase = WorkoutPhase.PAUSED, paused = true), 5000)
        store.close("time", 6000); store.close("time", 9000)
        val read = store.read("time")!!
        assertEquals(2000, read.getLong("working_ms")); assertEquals(2000, read.getLong("resting_ms")); assertEquals(1000, read.getLong("paused_ms"))
        assertFalse(read.getBoolean("partial"))
    }
}
