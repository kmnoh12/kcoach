package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.RecordHit
import dev.kyungmin.kcoach.core.RecordKind
import dev.kyungmin.kcoach.phone.ui.RecordTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecordPresentationTest {
    private val heaviest = RecordHit("bench", RecordKind.HEAVIEST_WEIGHT, 85.0, 80.0)
    private val reps = RecordHit("bench", RecordKind.SAME_WEIGHT_REPS, 6.0, 5.0, weightKg = 80.0)
    private val estimate = RecordHit("bench", RecordKind.ESTIMATED_ONE_REP_MAX, 103.0, 101.0)

    @Test fun aHeavierTopWeightIsTheBigMomentWithTheJump() {
        val ui = RecordPresentation.record("벤치 프레스", heaviest, pounds = false)
        assertEquals(RecordTier.MAX_WEIGHT, ui.tier)
        assertEquals("85kg", ui.headline)
        assertEquals("이전 80kg · +5kg", ui.detail)
    }

    @Test fun moreRepsAtTheSameWeightNamesTheWeight() {
        val ui = RecordPresentation.record("벤치 프레스", reps, pounds = false)
        assertEquals(RecordTier.REPS, ui.tier)
        assertEquals("80kg 6회", ui.headline)
        assertEquals("이 무게 최고 횟수 · 이전 5회", ui.detail)
    }

    @Test fun anEstimateIsAQuietLine() {
        val ui = RecordPresentation.record("벤치 프레스", estimate, pounds = false)
        assertEquals(RecordTier.ESTIMATE, ui.tier)
        assertEquals("추정 1RM 101 → 103kg", ui.headline)
    }

    @Test fun poundsUsersSeePounds() {
        val ui = RecordPresentation.record("벤치 프레스", heaviest, pounds = true)
        assertEquals("187.4lb", ui.headline)
    }

    @Test fun theSameRecordIsNotCelebratedTwiceInOneWorkout() {
        val tracker = RecordBannerTracker()
        assertNotNull(tracker.pick(listOf(estimate)))
        assertNull("same level again", tracker.pick(listOf(estimate)))
        assertNotNull("a stronger kind is worth another note", tracker.pick(listOf(reps)))
        assertNull("a weaker kind after a stronger one stays quiet", tracker.pick(listOf(estimate)))
        assertNotNull(tracker.pick(listOf(heaviest)))
        assertNotNull("a bigger value of the same kind", tracker.pick(listOf(heaviest.copy(value = 90.0))))
        assertNull(tracker.pick(listOf(heaviest)))
        tracker.reset()
        assertNotNull(tracker.pick(listOf(estimate)))
    }

    @Test fun exercisesAreTrackedSeparately() {
        val tracker = RecordBannerTracker()
        assertNotNull(tracker.pick(listOf(heaviest)))
        assertNotNull(tracker.pick(listOf(heaviest.copy(exerciseId = "squat"))))
    }
}
