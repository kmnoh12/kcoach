package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutPreparationTest {
    @Test fun onlyTheCurrentRoutinesCandidateExercisesAreAskedOnce() {
        val shoulder = WorkoutData.defaultRoutines().first { it.exercises.any { row -> row.exercise.id == "external_rotation" } }
        assertEquals(setOf("external_rotation", "crossover_reverse_fly", "dumbbell_lateral_raise"),
            WorkoutPreparation.questions(shoulder.exercises.map { it.exercise.id }).map { it.exerciseId }.toSet())
        assertEquals(1, WorkoutPreparation.questions(listOf("external_rotation", "external_rotation")).size)
        assertTrue(WorkoutPreparation.questions(listOf("lat_pull_down", "barbell_row", "push_up", "custom-exercise")).isEmpty())
    }

    @Test fun skippedAndOtherAnswersRemainDistinctAndNeverInferPhysicalDirection() {
        val ids = listOf("external_rotation", "crossover_reverse_fly", "lat_pull_down")
        val previous = WorkoutWearingSelection("RIGHT", "INSIDE", "ELBOW", mapOf("dumbbell_lateral_raise" to "NEUTRAL_ARM_DOWN"))
        val actual = WorkoutPreparation.selection(previous, ids, mapOf("external_rotation" to "OTHER",
            "dumbbell_lateral_raise" to "NEUTRAL_ARM_DOWN"))!!
        assertEquals("RIGHT", actual.side); assertEquals("INSIDE", actual.placement)
        assertNull(actual.deviceTop)
        assertEquals(mapOf("external_rotation" to "OTHER", "crossover_reverse_fly" to "UNKNOWN"), actual.startVariants)
        assertNull(WorkoutPreparation.selection(null, ids, emptyMap()))
        try {
            WorkoutPreparation.selection(previous, ids, mapOf("external_rotation" to "NEUTRAL_ARM_DOWN"))
            fail("A different exercise's answer cannot be used")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun directStartFreezesAnswersWithoutPromotingThemToVerifiedDirection() {
        val app = RuntimeEnvironment.getApplication()
        val defaults = WorkoutWearingSelection("LEFT", "OUTSIDE")
        val selected = WorkoutPreparation.selection(defaults, listOf("external_rotation"),
            mapOf("external_rotation" to "PALM_UP_HORIZONTAL"))
        val store = WorkoutWearingStore(app)
        store.beginSession("direct-session", selected).getOrThrow()
        val start = WearSync.richCommand("START", "direct-session", "external_rotation", "외회전", 0, 8, 5.0, 60,
            setId = "set", attemptId = "attempt")
        val bound = WearSync.parse(store.bind(start).getOrThrow())
        val declared = StartWristContextCodec.decode(bound[19])
        assertEquals("PALM_UP_HORIZONTAL", declared.variant)
        assertEquals("UNKNOWN", declared.deviceTop)
        assertFalse(declared.completeForDirectionValidation)
        assertTrue(bound.getOrNull(20).isNullOrEmpty())
        assertFalse(store.beginSession("direct-session", selected!!.copy(startVariants = mapOf("external_rotation" to "OTHER"))).isSuccess)
        assertEquals(bound, WearSync.parse(WorkoutWearingStore(app).bind(start).getOrThrow()))
    }

    @Test fun rememberedChoicesSurviveReopeningAndOtherRoutinesWithoutChangingAFrozenWorkout() {
        val app = RuntimeEnvironment.getApplication()
        val settings = WorkoutWearingStore(app)
        settings.beginSession("legacy-choice", WorkoutWearingSelection("LEFT", "OUTSIDE", "ELBOW",
            mapOf("external_rotation" to "PALM_UP_HORIZONTAL"))).getOrThrow()
        assertEquals("PALM_UP_HORIZONTAL", settings.preparationAnswers().getOrThrow()["external_rotation"])
        settings.beginSession("before-new-answer", WorkoutWearingSelection("RIGHT", "OUTSIDE")).getOrThrow()
        assertEquals("PALM_UP_HORIZONTAL", WorkoutWearingStore(app).preparationAnswers().getOrThrow()["external_rotation"])
        settings.savePreparationAnswer("crossover_reverse_fly", "OTHER").getOrThrow()
        settings.beginSession("different-routine", WorkoutWearingSelection("RIGHT", "OUTSIDE")).getOrThrow()
        val reopened = WorkoutWearingStore(app)
        assertEquals(mapOf("external_rotation" to "PALM_UP_HORIZONTAL", "crossover_reverse_fly" to "OTHER"),
            reopened.preparationAnswers().getOrThrow())
        reopened.savePreparationAnswer("external_rotation", "UNKNOWN").getOrThrow()
        assertEquals("UNKNOWN", WorkoutWearingStore(app).preparationAnswers().getOrThrow()["external_rotation"])
        assertEquals("PALM_UP_HORIZONTAL", reopened.selection("legacy-choice").getOrThrow()!!.startVariants["external_rotation"])
        assertTrue(WorkoutPreparation.selection(WorkoutWearingSelection("LEFT", "OUTSIDE"), listOf("lat_pull_down"),
            reopened.preparationAnswers().getOrThrow())!!.startVariants.isEmpty())
        assertFalse(reopened.savePreparationAnswer("lat_pull_down", "OTHER").isSuccess)
    }
}
