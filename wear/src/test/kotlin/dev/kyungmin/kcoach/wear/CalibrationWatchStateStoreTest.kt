package dev.kyungmin.kcoach.wear

import android.content.Intent
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalibrationWatchStateStoreTest {
    @Test fun cameraAndConfirmedInputsSurviveStopRestartAndIgnoreOutOfOrderOrForeignUpdates() {
        val app = RuntimeEnvironment.getApplication()
        val store = CalibrationWatchStateStore(app)
        val session = "calibration-paired-fixture"
        store.begin(session, "set", "attempt", "dumbbell_curl", "컬")
        val recording = CalibrationCameraState(session, "set", "attempt", 1, true)
        assertTrue(store.update(recording))
        val live = Intent(); store.applyTo(live, session)
        assertTrue(live.getBooleanExtra("phoneCameraRecording", false))
        assertEquals(-1, live.getIntExtra("calibrationConfirmedReps", 99))
        store.finish(session, 9)
        assertTrue(store.update(recording.copy(revision = 3, recording = false, confirmedReps = 10)))
        assertFalse(store.update(recording))
        assertFalse(store.update(recording.copy(attemptId = "foreign", revision = 4)))
        val result = Intent(); CalibrationWatchStateStore(app).applyTo(result, "")
        assertEquals(session, result.getStringExtra("session"))
        assertEquals("CALIBRATION_RESULT", result.getStringExtra("phase"))
        assertEquals(9, result.getIntExtra("reps", -1))
        assertEquals(10, result.getIntExtra("calibrationConfirmedReps", -1))
        assertFalse(result.getBooleanExtra("phoneCameraRecording", true))
        val workout = Intent(); store.applyTo(workout, "workout-session")
        assertFalse(workout.hasExtra("calibrationConfirmedReps"))
        store.begin("workout-session", "set2", "attempt2", "dumbbell_curl", "컬")
        assertFalse(store.update(recording.copy(revision = 5)))
    }
    @Test fun newCalibrationAttemptClearsTheOldConfirmation() {
        val store = CalibrationWatchStateStore(RuntimeEnvironment.getApplication())
        store.begin("calibration-paired-one", "set", "attempt", "dumbbell_curl", "컬")
        store.update(CalibrationCameraState("calibration-paired-one", "set", "attempt", 3, false, 10))
        store.begin("calibration-paired-two", "set2", "attempt2", "squat", "스쿼트")
        val state = Intent(); store.applyTo(state, "calibration-paired-two")
        assertEquals(-1, state.getIntExtra("calibrationConfirmedReps", 99))
        assertFalse(state.getBooleanExtra("phoneCameraRecording", true))
    }
}
