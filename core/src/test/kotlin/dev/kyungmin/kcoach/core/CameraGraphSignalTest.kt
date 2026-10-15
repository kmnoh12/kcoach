package dev.kyungmin.kcoach.core

import kotlin.test.*

class CameraGraphSignalTest {
    private fun pose(vararg joints: Pair<Int, Pair<Double, Double>>, confidence: Double = .95): List<CameraLandmark> {
        val list = MutableList(33) { CameraLandmark(.5, .5, 0.0, 0.0, 0.0) }
        joints.forEach { (i, p) -> list[i] = CameraLandmark(p.first, p.second, 0.0, confidence, confidence) }
        return list
    }

    @Test fun eachExercisePlotsTheSignalThatActuallyMovesAndNamesItHonestly() {
        assertEquals(CameraGraphSignal.FOREARM_DIRECTION, CameraGraphSignal.forExercise("external_rotation"))
        assertEquals("영상 속 팔뚝 방향", CameraGraphSignal.forExercise("cable_external_rotation").title)
        assertEquals(CameraGraphSignal.ELBOW_ANGLE, CameraGraphSignal.forExercise("seated_row"))
        assertEquals(CameraGraphSignal.WRIST_HEIGHT, CameraGraphSignal.forExercise("barbell_biceps_curl"))
        assertEquals(CameraGraphSignal.KNEE_ANGLE, CameraGraphSignal.forExercise("squat"))
    }

    @Test fun forearmDirectionSwingsFromInwardToOutwardAndIsSignedTheSameForBothArms() {
        // Left arm: elbow at (.5,.5); forearm pointing image-right vs image-left, then straight down.
        val right = pose(13 to (.5 to .5), 15 to (.7 to .5))
        val left = pose(13 to (.5 to .5), 15 to (.3 to .5))
        val down = pose(13 to (.5 to .5), 15 to (.5 to .7))
        val signal = CameraGraphSignal.FOREARM_DIRECTION
        assertEquals(90.0, signal.value(right, 100.0, 100.0, left = true)!!, 1e-6)
        assertEquals(-90.0, signal.value(left, 100.0, 100.0, left = true)!!, 1e-6)
        assertEquals(0.0, signal.value(down, 100.0, 100.0, left = true)!!, 1e-6)
        // The right arm mirrors, so the same physical motion has the same sign for either watch side.
        assertEquals(90.0, signal.value(pose(14 to (.5 to .5), 16 to (.3 to .5)), 100.0, 100.0, left = false)!!, 1e-6)
    }

    @Test fun elbowAngleIsMeasuredInFramePixelsNotNormalizedUnits() {
        val bent = pose(11 to (.5 to .2), 13 to (.5 to .5), 15 to (.8 to .5))
        // 640x480 frame: shoulder is 144px above the elbow, wrist 192px right; still a right angle.
        assertEquals(90.0, CameraGraphSignal.ELBOW_ANGLE.value(bent, 640.0, 480.0, true)!!, 0.01)
        val skewed = CameraGraphSignal.ELBOW_ANGLE.value(pose(11 to (.5 to .2), 13 to (.5 to .5), 15 to (.8 to .8)), 640.0, 480.0, true)!!
        // wrist 192px right and 144px down of the elbow: cos = -0.6, i.e. about 126.87 degrees (normalized units would give 135).
        assertEquals(126.87, skewed, 0.01)
    }

    @Test fun unseenOrOffFrameJointsLeaveAGapInsteadOfAValue() {
        assertNull(CameraGraphSignal.WRIST_HEIGHT.value(pose(15 to (.5 to .5), confidence = .4), 100.0, 100.0, true))
        assertNull(CameraGraphSignal.WRIST_HEIGHT.value(pose(15 to (.5 to .99)), 100.0, 100.0, true))
        assertEquals(.5, CameraGraphSignal.WRIST_HEIGHT.value(pose(16 to (.5 to .5)), 100.0, 100.0, left = false)!!, 1e-9)
    }
}
