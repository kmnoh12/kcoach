package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.ExerciseMotionCatalog
import dev.kyungmin.kcoach.core.MeasurementRecipe
import dev.kyungmin.kcoach.core.ExerciseTrackingProfiles
import dev.kyungmin.kcoach.core.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MeasurementCatalogCoverageTest {
    @Test
    fun everyBuiltInExerciseIncludingFoldedVariantsHasAnExplicitMeasurementContract() {
        val visibleIds = WorkoutData.catalog.filterNot { it.id.startsWith("custom_") }.map { it.id }.toSet()
        val measurementIds = ExerciseMotionCatalog.explicitBindings.map { it.exerciseId }.toSet()
        assertEquals("An exercise must not silently rely on name inference", visibleIds, measurementIds)
        visibleIds.forEach { id ->
            if (ExerciseTrackingProfiles.resolve(id).mode != TrackingMode.MANUAL_REQUIRED) {
                assertNotEquals("A counted exercise needs an explained recipe: $id",
                    MeasurementRecipe.MANUAL, ExerciseMotionCatalog.resolve(id)!!.recipe)
            }
        }
    }
}
