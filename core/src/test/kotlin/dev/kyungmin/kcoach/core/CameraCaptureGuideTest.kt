package dev.kyungmin.kcoach.core

import kotlin.test.*
import kotlin.math.cos
import kotlin.math.sin

class CameraCaptureGuideTest {
    @Test fun longReadyHoldDoesNotBecomeTheFirstRepetitionOrDiscardTheActualMovement() {
        val ready = (1000L..16000L step 200).map { frame(it, 160.0) }
        val motion = listOf(frame(16200, 120.0), frame(16400, 60.0), frame(16600, 100.0), frame(16800, 160.0))
        val proposed = CameraRepProposals.propose(ready + motion, "dumbbell_curl", true)
        assertEquals(1, proposed.size)
        assertEquals(16000_000_000L, proposed.single().startNs)
        assertEquals(16400_000_000L, proposed.single().turnaroundNs)
        assertEquals(16800_000_000L, proposed.single().endNs)
    }
    private fun frame(timeMs: Long, angle: Double, visible: Boolean = true): CameraPoseFrame {
        val image = List(33) { CameraLandmark(0.5, 0.5, 0.0, if (visible) 1.0 else 0.0, 1.0) }
        val world = MutableList(33) { CameraLandmark(0.0, 0.0, 0.0, 1.0, 1.0) }
        world[11] = CameraLandmark(0.0, 1.0, 0.0, 1.0, 1.0)
        world[13] = CameraLandmark(0.0, 0.0, 0.0, 1.0, 1.0)
        val radians = Math.toRadians(angle)
        world[15] = CameraLandmark(sin(radians), cos(radians), 0.0, 1.0, 1.0)
        val ns = timeMs * 1_000_000L
        return CameraPoseFrame(timeMs, ns, ns, timeMs, ns, timeMs * 1000, 640, 480, 0, true,
            CameraTimestampSource.PHONE_ELAPSED_REALTIME, "fixture-clock", listOf(image), listOf(world), true)
    }
    @Test fun allReviewedExercisesHaveAnExplicitGuideAndRequiredAnatomicalJoints() {
        assertEquals(43, ExerciseMeasurementContracts.standard.size)
        ExerciseMeasurementContracts.standard.keys.forEach { id ->
            for (left in listOf(true, false)) {
                val guide = CameraCaptureGuide.forExercise(id, left)
                assertTrue(guide.id.contains(id)); assertTrue(guide.direction.isNotBlank())
                assertTrue(guide.required.isNotEmpty()); assertTrue(guide.startDescription.isNotBlank())
                assertTrue(guide.required.all { it in 0..32 })
            }
        }
    }
    @Test fun mirroredPreviewDoesNotSwapTheObservedAnatomicalArmAndOcclusionBlocksReady() {
        val guide = CameraCaptureGuide.forExercise("dumbbell_curl", true)
        val points = frame(1000, 160.0).imageLandmarks.single().toMutableList()
        assertTrue(guide.visible(points)); points[16] = points[16].copy(visibility = 0.0)
        assertTrue(guide.visible(points)); points[15] = points[15].copy(presence = 0.0)
        assertFalse(guide.visible(points)); points[15] = points[15].copy(presence = 1.0, x = 1.2)
        assertFalse(guide.visible(points))
    }
    @Test fun completeCandidateKeepsActualFrameBoundariesAndIncompleteOrHiddenMotionDoesNotCount() {
        val frames = listOf(frame(1000, 160.0), frame(1200, 160.0), frame(1400, 120.0), frame(1600, 60.0),
            frame(1800, 100.0), frame(2000, 160.0))
        val proposed = CameraRepProposals.propose(frames, "dumbbell_curl", true)
        assertEquals(1, proposed.size); assertEquals(1200_000_000L, proposed.single().startNs)
        assertEquals(1600_000_000L, proposed.single().turnaroundNs); assertEquals(2000_000_000L, proposed.single().endNs)
        assertEquals(MusclePhase.CONCENTRIC, proposed.single().departurePhase)
        assertTrue(CameraRepProposals.propose(frames.dropLast(1), "dumbbell_curl", true).isEmpty())
        assertTrue(CameraRepProposals.propose(frames.mapIndexed { i, f -> if (i == 3) frame(1600, 60.0, false) else f }, "dumbbell_curl", true).isEmpty())
    }
    @Test fun gapCannotBeFilledWithInventedRestOrBoundaries() {
        val frames = listOf(frame(1000, 160.0), frame(1200, 160.0), frame(1400, 120.0), frame(2600, 60.0), frame(2800, 160.0))
        assertTrue(CameraRepProposals.propose(frames, "dumbbell_curl", true).isEmpty())
    }
}
