package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepReferenceTest {
    @Test fun splittingAcrossAPauseAndMergingPreserveTheObservedPauseTime() {
        val original = RepCycle(1_000_000_000, 1_800_000_000, 3_000_000_000, MusclePhase.CONCENTRIC,
            listOf(RepPause(1_300_000_000, 1_400_000_000), RepPause(1_900_000_000, 2_100_000_000)))
        val split = original.split(1_500_000_000, 2_000_000_000, 2_500_000_000)
        assertEquals(200L, split[0].pauseMs)
        assertEquals(100L, split[1].pauseMs)
        assertEquals(original.pauseMs, split.sumOf { it.pauseMs })
        assertEquals(original.durationMs, split.sumOf { it.durationMs })
        val merged = split[0].merge(split[1], original.turnaroundNs)
        assertEquals(original.pauseMs, merged.pauseMs)
        assertEquals(original.concentricMs, merged.concentricMs)
        assertEquals(original.eccentricMs, merged.eccentricMs)
        assertEquals(original.startNs, merged.startNs)
        assertEquals(original.endNs, merged.endNs)
    }

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
