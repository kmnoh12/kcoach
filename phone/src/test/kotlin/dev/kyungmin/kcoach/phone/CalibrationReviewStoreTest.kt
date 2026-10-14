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
class CalibrationReviewStoreTest {
    @Test fun countOnlyReviewKeepsExplicitZeroButCannotInventCycleBoundariesOrTrainingLabels() {
        val app = RuntimeEnvironment.getApplication()
        val captures = CalibrationCaptureStore(app)
        val capture = captures.begin("dumbbell_curl", "컬", true, "phone").getOrThrow()
        captures.finish(capture.id, false, "Interrupted").getOrThrow()
        val store = CalibrationReviewStore(app)
        val saved = store.saveCount(capture.id, 0, 0).getOrThrow()
        assertEquals(0, saved.countReference!!.reps)
        assertEquals(1L, saved.revision)
        assertTrue(CalibrationReviewStore(app).load(capture.id).getOrThrow().references.isEmpty())
        assertFalse(store.saveCount(capture.id, 9, 0).isSuccess)
        assertFalse(store.saveCycles(capture.id, listOf(RepCycle(100, 200, 300, MusclePhase.CONCENTRIC)), true, 1).isSuccess)
        assertEquals(0, store.load(capture.id).getOrThrow().countReference!!.reps)
    }
}
