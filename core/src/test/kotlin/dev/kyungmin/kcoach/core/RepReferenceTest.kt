package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepReferenceTest {
    @Test
    fun measuredPhaseTimesAreNotHalfOfTheWholeRepetition() {
        val cycle = RepCycle(1_000_000_000L, 1_300_000_000L, 2_000_000_000L, MusclePhase.CONCENTRIC)
        assertEquals(300L, cycle.concentricMs)
        assertEquals(700L, cycle.eccentricMs)
        val squat = cycle.copy(departurePhase = MusclePhase.ECCENTRIC)
        assertEquals(700L, squat.concentricMs)
        assertEquals(300L, squat.eccentricMs)
        assertNull(cycle.copy(departurePhase = MusclePhase.UNKNOWN).concentricMs)
    }

    @Test
    fun cameraAndWatchCandidatesCannotTeachTheModelWithoutHumanReview() {
        val label = RepReference("capture", "set", "attempt",
            RepCycle(1_000_000_000L, 1_300_000_000L, 2_000_000_000L, MusclePhase.CONCENTRIC),
            ReferenceSource.CAMERA_CANDIDATE, alignmentUncertaintyNs = 1_000_000L)
        assertFalse(label.eligibleForLearning)
        assertFalse(label.copy(source = ReferenceSource.WATCH_CANDIDATE).eligibleForLearning)
        assertFalse(label.copy(source = ReferenceSource.REVIEWED_VIDEO, reviewedAtMs = 100L).eligibleForLearning)
        assertTrue(label.copy(source = ReferenceSource.REVIEWED_VIDEO, reviewedAtMs = 100L,
            alignmentVerified = true, sensorCoverageComplete = true).eligibleForLearning)
        assertFalse(label.copy(source = ReferenceSource.REVIEWED_VIDEO, reviewedAtMs = 100L,
            alignmentUncertaintyNs = 100_000_000L).eligibleForLearning)
    }

    @Test
    fun holdsAreSeparatedFromMovementTime() {
        val cycle = RepCycle(1_000_000_000L, 1_400_000_000L, 2_000_000_000L,
            MusclePhase.CONCENTRIC, listOf(RepPause(1_250_000_000L, 1_500_000_000L)))
        assertEquals(250L, cycle.concentricMs)
        assertEquals(500L, cycle.eccentricMs)
        assertEquals(250L, cycle.pauseMs)
        assertEquals(1000L, cycle.durationMs)
    }
}
