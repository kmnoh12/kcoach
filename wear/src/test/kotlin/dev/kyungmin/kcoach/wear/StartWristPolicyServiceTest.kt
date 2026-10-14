package dev.kyungmin.kcoach.wear

import android.content.Intent
import dev.kyungmin.kcoach.core.ExerciseMotionCatalog
import dev.kyungmin.kcoach.core.StartWristCandidate
import dev.kyungmin.kcoach.core.StartWristOrientationPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartWristPolicyServiceTest {
    private fun command(operation: String, exercise: String, revision: Long = 1L) = Intent()
        .putExtra("command", operation).putExtra("session", "explicit-session")
        .putExtra("exerciseId", exercise).putExtra("setId", "set-$exercise").putExtra("attemptId", "attempt-$exercise")
        .putExtra("targetReps", 8).putExtra("commandRevision", revision)
    private fun state() = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
        .last { it.action == WearWorkoutService.ACTION_STATE }

    @Test fun everyDeferredExerciseStartsOnTheExplicitCommandWithoutAUniversalPoseHold() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            var revision = 1L
            val deferred = ExerciseMotionCatalog.explicitBindings.filter {
                StartWristOrientationPolicy.candidate(it.exerciseId) == StartWristCandidate.DEFERRED
            }
            assertEquals(33, deferred.size)
            for (exercise in deferred) {
                controller.get().onStartCommand(command("START", exercise.exerciseId, revision++), 0, 1)
                assertEquals(exercise.exerciseId, state().getStringExtra("exerciseId"))
                assertEquals("WORKING", state().getStringExtra("phase"))
                assertTrue(state().getBooleanExtra("running", false))
                assertEquals(0, state().getIntExtra("reps", -1))
            }
        } finally { controller.destroy() }
    }

    @Test fun candidateWithoutAReviewedDirectionStaysUnconfirmedThroughPauseAndServiceRecreation() {
        val app = RuntimeEnvironment.getApplication()
        val first = Robolectric.buildService(WearWorkoutService::class.java).create()
        first.get().onStartCommand(command("START", "external_rotation"), 0, 1)
        assertEquals("WORKING_START_UNCHECKED", state().getStringExtra("phase"))
        first.get().onStartCommand(command("PAUSE", "external_rotation", 2L), 0, 2)
        assertEquals("PAUSED", state().getStringExtra("phase"))
        first.destroy()
        val restored = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            restored.get().onStartCommand(command("RESUME", "external_rotation", 3L), 0, 3)
            assertEquals("WORKING_START_UNCHECKED", state().getStringExtra("phase"))
            assertEquals(0, state().getIntExtra("reps", -1))
            assertNotNull(app.getSharedPreferences("workout-runtime", 0).getString("state", null))
        } finally { restored.destroy() }
    }

    @Test fun manualExercisesNeverAcquireAnAutomaticPostureVerdict() {
        val controller = Robolectric.buildService(WearWorkoutService::class.java).create()
        try {
            controller.get().onStartCommand(command("START", "leg_press"), 0, 1)
            assertEquals("MANUAL_REQUIRED", state().getStringExtra("phase"))
            assertEquals(0, state().getIntExtra("reps", -1))
        } finally { controller.destroy() }
    }
}
