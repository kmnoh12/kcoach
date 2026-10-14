package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import dev.kyungmin.kcoach.phone.ui.SetField
import dev.kyungmin.kcoach.phone.ui.WorkoutActions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutPresentationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun plan() = WorkoutSnapshot(
        sessionId = "presentation-session", routineId = "free", exerciseIndex = 0, setIndex = 0,
        plannedSets = listOf(PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, false,
            exerciseName = "외회전", setId = "presentation-set", attemptId = "presentation-attempt", identityRequired = true)),
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
        paused = false, startedAtMs = 1, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
        lastCompletedSetKey = null, captureStates = emptyMap(),
    )

    @Test fun reopeningTheWorkoutScreenDoesNotSaveOrReplaceAnUnacknowledgedCommand() {
        SessionStore(app).saveActive(plan())
        val outbox = PhoneCommandOutbox(app)
        val pending = outbox.enqueue(WearSync.command("SET", "presentation-session", "external_rotation", 0, 10))
        val prefs = app.getSharedPreferences("workout-session", Context.MODE_PRIVATE)
        val revision = prefs.getLong("revision", 0)
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            activity.pause().stop().start().resume()
            assertEquals("Rendering must not replace the desired watch command", pending, outbox.pending())
            assertEquals("Rendering must not commit another session revision", revision, prefs.getLong("revision", 0))
            assertEquals(plan(), SessionStore(app).loadActive().getOrThrow())
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun finishingAWeightEditPersistsItAndSendsTheSameSetIdentity() {
        SessionStore(app).saveActive(plan())
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val actions = MainActivity::class.java.getDeclaredField("workoutActions").apply { isAccessible = true }
                .get(activity.get()) as WorkoutActions
            actions.onEditValue(0, 0, SetField.WEIGHT, "7.5", false)
            actions.onEditFinished()
            val saved = SessionStore(app).loadActive().getOrThrow()!!.plannedSets.single()
            assertEquals(7.5, saved.weightKg!!, 0.0)
            val fields = WearSync.parse(PhoneCommandOutbox(app).pending()!!)
            assertEquals("SET", fields[1])
            assertEquals("presentation-session", fields[2])
            assertEquals("7.5", fields[7])
            assertEquals("presentation-set", fields[13])
            assertEquals("presentation-attempt", fields[14])
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun skippingRestUsesTheSameNextExerciseRuleAsTheBackgroundTimer() {
        val first = plan().plannedSets.single()
        val resting = plan().copy(phase = WorkoutPhase.RESTING, restEndAtMs = System.currentTimeMillis() + 90_000,
            plannedSets = listOf(
                first.copy(completed = true, performedReps = 6),
                first.copy(exerciseIndex = 1, exerciseId = "flat_bench", completed = true, setId = "done-set", attemptId = "done-attempt"),
                first.copy(exerciseIndex = 2, exerciseId = "lat_pull_down", exerciseName = "랫 풀 다운", setId = "next-set", attemptId = "next-attempt")))
        SessionStore(app).saveActive(resting)
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val actions = MainActivity::class.java.getDeclaredField("workoutActions").apply { isAccessible = true }
                .get(activity.get()) as WorkoutActions
            actions.onSkipRest()
            val saved = SessionStore(app).loadActive().getOrThrow()!!
            assertEquals(2, saved.exerciseIndex)
            assertEquals(WorkoutPhase.WORKING, saved.phase)
            assertEquals(0L, saved.restEndAtMs)
            val fields = WearSync.parse(PhoneCommandOutbox(app).pending()!!)
            assertEquals("SET", fields[1])
            assertEquals("lat_pull_down", fields[3])
            assertEquals("next-set", fields[13])
            assertEquals("next-attempt", fields[14])
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun resumingAfterChangingExerciseSendsTheSelectedSetToTheWatch() {
        val first = plan().plannedSets.single()
        SessionStore(app).saveActive(plan().copy(plannedSets = listOf(first,
            first.copy(exerciseIndex = 1, exerciseId = "lat_pull_down", exerciseName = "랫 풀 다운",
                setId = "next-set", attemptId = "next-attempt"))))
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val actions = MainActivity::class.java.getDeclaredField("workoutActions").apply { isAccessible = true }
                .get(activity.get()) as WorkoutActions
            actions.onTogglePause()
            actions.onPageSettled(1)
            actions.onTogglePause()
            val saved = SessionStore(app).loadActive().getOrThrow()!!
            assertFalse(saved.paused)
            assertEquals(1, saved.exerciseIndex)
            val fields = WearSync.parse(PhoneCommandOutbox(app).pending()!!)
            assertEquals("SET", fields[1])
            assertEquals("lat_pull_down", fields[3])
            assertEquals("next-set", fields[13])
            assertEquals("next-attempt", fields[14])
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun restingHighlightUsesCompletedSetIdentityInsteadOfNextExecutionCursor() {
        val completed = plan().plannedSets.single().copy(completed = true, performedReps = 10)
        val next = completed.copy(setIndex = 1, completed = false, performedReps = null,
            setId = "next-set", attemptId = "next-attempt")
        SessionStore(app).saveActive(plan().copy(setIndex = 1, phase = WorkoutPhase.RESTING,
            restEndAtMs = System.currentTimeMillis() + 90_000,
            plannedSets = listOf(completed, next),
            lastCompletedSetKey = SetIdentity.completionKey(plan().sessionId, completed)))
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val instance = activity.get()
            val workout = MainActivity::class.java.getDeclaredField("activeWorkout").apply { isAccessible = true }.get(instance)!!
            val build = MainActivity::class.java.getDeclaredMethod("buildWorkoutModel", workout.javaClass).apply { isAccessible = true }
            val resting = build.invoke(instance, workout) as dev.kyungmin.kcoach.phone.ui.WorkoutUiModel
            assertEquals(1, resting.exercises.single().currentSetIndex)
            assertEquals(0 to 0, resting.restingSet)
            MainActivity::class.java.getDeclaredField("activeRestEndAtMs").apply { isAccessible = true }.setLong(instance, 0L)
            val working = build.invoke(instance, workout) as dev.kyungmin.kcoach.phone.ui.WorkoutUiModel
            assertNull(working.restingSet)
            assertEquals(1, working.exercises.single().currentSetIndex)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun calibrationCannotReplaceAnActiveWorkoutCommand() {
        SessionStore(app).saveActive(plan())
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val before = PhoneCommandOutbox(app).pending()
            MainActivity::class.java.getDeclaredMethod("startOrStopCalibration").apply { isAccessible = true }.invoke(activity.get())
            assertNull(MainActivity::class.java.getDeclaredField("calibrationSessionId").apply { isAccessible = true }.get(activity.get()))
            assertEquals(before, PhoneCommandOutbox(app).pending())
            assertEquals(plan().sessionId, SessionStore(app).loadActive().getOrThrow()!!.sessionId)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun sensorViewRemainsReachableWhenNoWorkoutIsRunning() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            MainActivity::class.java.getDeclaredMethod("showSensorView").apply { isAccessible = true }.invoke(activity.get())
            assertEquals(ProbeActivity::class.java.name,
                org.robolectric.Shadows.shadowOf(activity.get()).nextStartedActivity.component!!.className)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun sensorViewCannotCompeteWithAnActiveWorkout() {
        SessionStore(app).saveActive(plan())
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            MainActivity::class.java.getDeclaredMethod("showSensorView").apply { isAccessible = true }.invoke(activity.get())
            assertNull(org.robolectric.Shadows.shadowOf(activity.get()).nextStartedActivity)
        } finally { activity.pause().stop().destroy() }
    }

}
