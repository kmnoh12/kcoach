package dev.kyungmin.kcoach.core

import kotlin.test.*

class CameraCaptureEndTest {
    private val guide = CameraCaptureGuide("test", "", "", setOf(15), "", null)
    private fun image(x: Double = .5, confidence: Double = 1.0) = List(33) {
        CameraLandmark(x, .5, 0.0, confidence, confidence)
    }
    @Test fun stillStartDoesNotStopAndMotionThenHoldEndsAtBeginningOfHold() {
        val end = CameraCaptureEnd()
        for (n in 0L..20L) assertNull(end.observe(n * 250_000_000, image(), image(), guide, true))
        assertNull(end.observe(5_250_000_000, image(.7), image(), guide, true))
        for (n in 0L..9L) assertNull(end.observe(5_500_000_000 + n * 250_000_000, image(), image(), guide, true))
        assertEquals(5_500_000_000, end.observe(8_000_000_000, image(), image(), guide, true))
    }
    @Test fun occlusionGapsAndSlowContinuousMovementDoNotBecomeStillness() {
        val end = CameraCaptureEnd()
        end.observe(0, image(), image(), guide, true)
        for (n in 1L..30L) assertNull(end.observe(n * 100_000_000, image(.5 + n * .005), image(), guide, true))
        end.observe(3_100_000_000, image(.5), image(), guide, true)
        end.observe(3_200_000_000, image(.5, .64), image(), guide, true)
        assertNull(end.observe(5_900_000_000, image(), image(), guide, true))
        assertNull(end.observe(6_000_000_000, null, null, guide, true))
        assertNull(end.observe(6_100_000_000, image(), image(), guide, true))
    }
    @Test fun externalRotationWorksWithoutCameraMetricButKeepsTheElbowBand() {
        val rotation = CameraCaptureGuide.forExercise("external_rotation", true)
        assertNull(rotation.metric)
        fun pose(wristX: Double) = image().toMutableList().apply {
            this[11] = CameraLandmark(.4, .3, 0.0, 1.0, 1.0)
            this[13] = CameraLandmark(.4, .5, 0.0, 1.0, 1.0)
            this[15] = CameraLandmark(wristX, .5, 0.0, 1.0, 1.0)
        }
        val end = CameraCaptureEnd()
        end.observe(0, pose(.6), pose(.6), rotation, true)
        assertNull(end.observe(250_000_000, pose(.7), pose(.7), rotation, true))
        for (n in 0L..9L) assertNull(end.observe(500_000_000 + n * 250_000_000, pose(.6), pose(.6), rotation, true))
        assertEquals(500_000_000, end.observe(3_000_000_000, pose(.6), pose(.6), rotation, true))
    }
}
