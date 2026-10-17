package dev.kyungmin.kcoach.core

import kotlin.math.*
import kotlin.test.*

internal object CalibrationPoseFixture {
    fun frame(ms: Long, yaw: Double = -CameraOrientationPolicy.OBLIQUE, angle: Double = 165.0): CameraPoseFrame {
        val image = MutableList(33) { CameraLandmark(.5, .5, 0.0, 1.0, 1.0) }
        image[11] = image[11].copy(x = .6, y = .25); image[12] = image[12].copy(x = .4, y = .25)
        image[23] = image[23].copy(x = .58, y = .6); image[24] = image[24].copy(x = .42, y = .6)
        image[13] = image[13].copy(x = .67, y = .4); image[15] = image[15].copy(x = .72, y = .55)
        val w = MutableList(33) { CameraLandmark(0.0, 0.0, 0.0, 1.0, 1.0) }
        w[11] = w[11].copy(x = .2, y = -.5); w[12] = w[12].copy(x = -.2, y = -.5)
        w[23] = w[23].copy(x = .16); w[24] = w[24].copy(x = -.16)
        w[13] = w[13].copy(x = .22, y = -.2)
        val a = Math.toRadians(angle)
        w[15] = w[15].copy(x = .22 + .3 * sin(a), y = -.2 - .3 * cos(a))
        val r = Math.toRadians(yaw)
        val world = w.map { p -> p.copy(x = p.x * cos(r) - p.z * sin(r), z = p.x * sin(r) + p.z * cos(r)) }
        return CameraPoseFrame(ms, ms * 1_000_000, ms * 1_000_000, ms, ms * 1_000_000, ms * 1000,
            640, 480, 0, true, CameraTimestampSource.PHONE_ELAPSED_REALTIME, "clock", listOf(image), listOf(world), true)
    }
    fun repetitions(n: Int = 10): List<CameraPoseFrame> {
        val angles = List(12) { 165.0 } + (0 until n).flatMap { listOf(165.0, 165.0, 120.0, 60.0, 90.0, 165.0, 165.0, 165.0) }
        return angles.mapIndexed { i, angle -> frame(1000L + i * 100, angle = angle) }
    }
}

class CameraOrientationTest {
    @Test fun visibleObliqueSetupAllowsModerateModelYawError() {
        for (yaw in listOf(-28.0, -62.0)) {
            val f = CalibrationPoseFixture.frame(1000, yaw)
            assertTrue(CameraCaptureGuide.forExercise("dumbbell_curl", true)
                .diagnose(f.imageLandmarks.single(), f.worldLandmarks.single(), true).ready)
        }
    }
    private val guide = CameraCaptureGuide.forExercise("dumbbell_curl", true)
    private fun diagnosis(yaw: Double): StartDiagnosis {
        val f = CalibrationPoseFixture.frame(1000, yaw)
        return guide.diagnose(f.imageLandmarks.single(), f.worldLandmarks.single(), true)
    }
    @Test fun frontObliqueSideAndWrongSideHaveDistinctReadinessAndMessages() {
        assertEquals("몸을 더 돌려 주세요", diagnosis(0.0).blocker)
        assertFalse(diagnosis(0.0).ready)
        assertTrue(diagnosis(-45.0).ready)
        assertNull(diagnosis(-45.0).blocker)
        assertEquals("자세 확인 · 곧 촬영을 시작해요", diagnosis(-45.0).status(true))
        assertEquals("조금만 덜 돌려 주세요", diagnosis(-90.0).blocker)
        assertFalse(diagnosis(45.0).ready)
        assertEquals("워치를 찬 팔이 카메라 가까이 오게 돌려 주세요", diagnosis(45.0).blocker)
        val right = CalibrationPoseFixture.frame(1000, 45.0)
        val rightGuide = CameraCaptureGuide.forExercise("dumbbell_curl", false)
        val image = right.imageLandmarks.single().toMutableList(); val world = right.worldLandmarks.single().toMutableList()
        image[14] = image[13].copy(x = .3); image[16] = image[15].copy(x = .25)
        world[14] = world[13].copy(x = -world[13].x, z = -world[13].z)
        world[16] = world[15].copy(x = -world[15].x, z = -world[15].z)
        assertTrue(rightGuide.startReady(image, world, false))
    }
    @Test fun occlusionAndNonfiniteOrMissingTorsoImmediatelyRemoveGreen() {
        val f = CalibrationPoseFixture.frame(1000)
        val image = f.imageLandmarks.single().toMutableList()
        image[15] = image[15].copy(visibility = .3)
        assertEquals("팔이 몸에 가려져요", guide.diagnose(image, f.worldLandmarks.single(), true).blocker)
        image[23] = image[23].copy(y = 1.1)
        assertFalse(guide.startReady(image, f.worldLandmarks.single(), true))
        assertFailsWith<IllegalArgumentException> { f.worldLandmarks.single()[12].copy(z = Double.NaN) }
        val hidden = f.worldLandmarks.single().toMutableList(); hidden[15] = hidden[15].copy(z = .4)
        image[15] = image[15].copy(x = .5, y = .4, visibility = 1.0); image[23] = image[23].copy(y = .6)
        assertEquals("팔이 몸에 가려져요", guide.diagnose(image, hidden, true).blocker)
    }
    @Test fun medianWarmupHysteresisAndGapsDoNotBorrowStaleGreen() {
        val tracker = CameraOrientationTracker(); var ms = 1000L
        fun observe(yaw: Double): CameraOrientation {
            val f = CalibrationPoseFixture.frame(ms, yaw); ms += 50
            return tracker.observe(f.cameraTimestampNs, f.imageLandmarks.single(), f.worldLandmarks.single(), true, guide.viewTarget!!)
        }
        repeat(4) { assertFalse(observe(-45.0).accepted) }
        assertTrue(observe(-45.0).accepted)
        repeat(12) { assertTrue(observe(-66.0).accepted) }
        assertTrue(observe(-70.0).accepted) // One yaw spike does not reset a stable hold.
        repeat(9) { observe(-70.0) }; assertFalse(observe(-70.0).accepted)
        repeat(9) { observe(-45.0) }; assertTrue(observe(-45.0).accepted)
        assertFalse(observe(-90.0).accepted)
        tracker.reset(); repeat(9) { assertFalse(observe(-66.0).accepted) }
        repeat(9) { observe(-45.0) }; assertTrue(observe(-45.0).accepted)
        ms += 1000; assertFalse(observe(-45.0).accepted)
        assertFalse(tracker.observe(ms * 1_000_000, null, null, true, guide.viewTarget!!).accepted)
    }
    @Test fun stableGreenNeverSurvivesAnOccludedArmOrTorso() {
        for (joint in listOf(11, 15, 23)) {
            val tracker = CameraOrientationTracker()
            repeat(9) { i ->
                val f = CalibrationPoseFixture.frame(1000L + i * 50)
                tracker.observe(f.cameraTimestampNs, f.imageLandmarks.single(), f.worldLandmarks.single(), true, guide.viewTarget!!)
            }
            val f = CalibrationPoseFixture.frame(1500, -62.0)
            val hidden = f.imageLandmarks.single().toMutableList()
            hidden[joint] = hidden[joint].copy(visibility = .3)
            assertFalse(tracker.observe(f.cameraTimestampNs, hidden, f.worldLandmarks.single(), true, guide.viewTarget!!).accepted)
            val next = CalibrationPoseFixture.frame(1550)
            assertFalse(tracker.observe(next.cameraTimestampNs, next.imageLandmarks.single(), next.worldLandmarks.single(), true, guide.viewTarget!!).accepted)
        }
    }
    @Test fun sharedTargetsKeepFrontAndRearIntentAndRejectTwistedTorso() {
        assertEquals(CameraOrientationPolicy.FRONT, CameraOrientationPolicy.target("hip_adduction").degrees)
        assertEquals(CameraOrientationPolicy.REAR_OBLIQUE, CameraOrientationPolicy.target("lat_pull_down").degrees)
        for (id in listOf("dumbbell_curl", "squat", "leg_press", "external_rotation")) {
            assertEquals(CameraOrientationPolicy.OBLIQUE, CameraCaptureGuide.forExercise(id, true).viewTarget!!.degrees)
            assertTrue(CameraCaptureGuide.forExercise(id, true).direction.contains("45°"))
        }
        val rear = CalibrationPoseFixture.frame(1000, -135.0)
        assertTrue(CameraTorsoOrientation.evaluate(rear.imageLandmarks.single(), rear.worldLandmarks.single(), true,
            CameraOrientationPolicy.target("lat_pull_down")).accepted)
        val f = CalibrationPoseFixture.frame(1000); val world = f.worldLandmarks.single().toMutableList()
        world[23] = world[23].copy(z = 0.0); world[24] = world[24].copy(z = 0.0)
        assertFalse(guide.startReady(f.imageLandmarks.single(), world, true))
    }
}
