package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MeasurementRecipeTest {
    @Test
    fun pressEquipmentSharesRecipeAndEngineWhileKeepingExerciseIdentity() {
        val smith = ExerciseMotionCatalog.resolve("smith_shoulder_press")!!
        val dumbbell = ExerciseMotionCatalog.resolve("dumbbell_shoulder_press")!!
        assertEquals(MeasurementRecipe.SHOULDER_PRESS, smith.recipe)
        assertEquals(smith.recipe, dumbbell.recipe)
        assertEquals(MeasurementEngine.TRANSLATION, smith.recipe.engine)
        assertNotEquals(smith.exerciseId, dumbbell.exerciseId)
        assertEquals(ExerciseMotionCatalog.resolve("flat_bench")!!.recipe,
            ExerciseMotionCatalog.resolve("incline_bench")!!.recipe)
    }

    @Test
    fun squatAndPulldownShareCycleEngineButNotPhaseMeaningOrCameraEvidence() {
        val squat = MeasurementRecipe.SQUAT
        val pull = ExerciseMotionCatalog.resolve("lat_pull_down")!!.recipe
        assertEquals(squat.engine, pull.engine)
        assertEquals(MusclePhase.ECCENTRIC, squat.departurePhase)
        assertEquals(MusclePhase.CONCENTRIC, pull.departurePhase)
        assertTrue(squat.cameraLandmarks.containsAll(setOf(23, 24, 25, 26, 27, 28)))
        assertTrue(pull.cameraLandmarks.containsAll(setOf(11, 12, 13, 14, 15, 16)))
        assertEquals(StartEvidence.CAMERA_BODY_POSE, squat.startEvidence)
    }

    @Test
    fun rowVariantsShareCycleButExplainSupportAndDirection() {
        val ids = listOf("lying_tbar_row", "barbell_row", "seated_row", "one_arm_dumbbell_row")
        val rows = ids.map { ExerciseMotionCatalog.resolve(it)!! }
        assertTrue(rows.all { it.recipe == MeasurementRecipe.ROW })
        assertTrue(rows.all { it.conditionDescription.isNotBlank() })
        assertEquals(4, rows.map { it.conditionDescription }.toSet().size)
    }

    @Test
    fun manualExerciseExplanationDoesNotPromiseSensorCounting() {
        val text = ExerciseMeasurementContracts.explanation("leg_press")
        assertTrue(text.contains("측정 방식 · 수동 입력"))
        assertTrue(text.contains("실제 수행 횟수를 입력"))
        assertTrue(!text.contains("1회 기준 ·"))
    }
}
