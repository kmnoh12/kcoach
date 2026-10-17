package dev.kyungmin.kcoach.core

import kotlin.test.*

class CalibrationSafetyTest {
    private fun quality(frames: List<CameraPoseFrame>, watch: Int? = 10, user: Int? = 10) =
        CalibrationSafety.evaluate(frames, "dumbbell_curl", true, watch, user)
    @Test fun goodCaptureCanEnterLearningButMissingOrMismatchedCountsCannot() {
        val frames = CalibrationPoseFixture.repetitions()
        assertEquals(10, CameraRepProposals.propose(frames, "dumbbell_curl", true).size)
        assertTrue(quality(frames).eligible, quality(frames).failures.toString())
        assertFalse(quality(frames, 9).eligible)
        assertFalse(quality(frames, null).eligible)
        assertFalse(quality(frames, user = null).eligible)
        assertFalse(quality(CalibrationPoseFixture.repetitions(3), 3, 3).eligible)
    }
    @Test fun directionOcclusionMultiplePeopleAndTrackingJumpsPreventLearning() {
        val frames = CalibrationPoseFixture.repetitions()
        val front = frames.map { CalibrationPoseFixture.frame(it.modelTimestampMs, 0.0) }
        assertFalse(quality(front).eligible)
        val invisible = frames.map { f -> f.copy(imageLandmarks = listOf(f.imageLandmarks.single().map { it.copy(visibility = .2) })) }
        assertFalse(quality(invisible).eligible)
        val people = frames.toMutableList(); people[20] = people[20].copy(imageLandmarks = people[20].imageLandmarks + people[20].imageLandmarks,
            worldLandmarks = people[20].worldLandmarks + people[20].worldLandmarks)
        assertFalse(quality(people).eligible)
        val jump = frames.mapIndexed { i, f -> if (i < 30) f else f.copy(imageLandmarks = listOf(f.imageLandmarks.single().map { it.copy(x = it.x - .25) })) }
        assertTrue(quality(jump).failures.any { it.contains("크기") })
        assertFalse(quality(frames.filterIndexed { i, _ -> i !in 30..40 }).eligible)
    }
    @Test fun automaticRollbackGuardRejectsWorseSyntheticCountsAndAllowsGoodOnes() {
        assertTrue(CalibrationRegressionGuard.worse(10, 10, 8))
        assertTrue(CalibrationRegressionGuard.worse(10, 9, null))
        assertFalse(CalibrationRegressionGuard.worse(10, 9, 10))
        assertFalse(CalibrationRegressionGuard.worse(10, 10, 10))
        assertFalse(CalibrationRegressionGuard.worse(10, null, 8))
    }
    @Test fun calibrationPrefixKeepsPairedPreservationAndNeverStopsAtTheWatchTarget() {
        val purpose = CapturePurpose.forSession("calibration-paired-test")
        assertEquals(CapturePurpose.PAIRED_REFERENCE, purpose)
        assertTrue(purpose.preservePreparation); assertFalse(purpose.stopAtTarget)
        assertEquals(purpose, CapturePurpose.forSession("paired-old"))
        assertEquals(CapturePurpose.LEGACY_CALIBRATION, CapturePurpose.forSession("calibration-old"))
        val state = CalibrationCameraState("calibration-paired-test", "set", "attempt", 3, false, 10)
        assertEquals(state, CalibrationCameraState.decode(state.encode()))
        assertTrue(state.supersedes(state.copy(revision = 1, recording = true, confirmedReps = null)))
        assertFalse(state.copy(revision = 1).supersedes(state))
    }
}
