package dev.kyungmin.kcoach.core

import kotlin.test.*

class CameraHiddenWristTest {
    private fun image(wristConfidence: Double) = List(33) { i ->
        CameraLandmark(.5, .5, 0.0, if (i == 15) wristConfidence else 1.0, if (i == 15) wristConfidence else 1.0)
    }
    // Upper arm straight up from the shoulder, forearm folded back down behind the head.
    private val world = List(33) { CameraLandmark(0.0, 0.0, 0.0, 1.0, 1.0) }.toMutableList().apply {
        this[11] = CameraLandmark(0.0, 0.0, 0.0, 1.0, 1.0)
        this[13] = CameraLandmark(0.0, -0.3, 0.0, 1.0, 1.0)
        this[15] = CameraLandmark(-0.1, -0.1, 0.0, 1.0, 1.0)
    }

    @Test fun overheadExtensionStartsWithAWristTheHeadHides() {
        val guide = CameraCaptureGuide.forExercise("cable_overhead_extension", true).copy(viewTarget = null)
        assertTrue(guide.diagnose(image(.3), world, true, orientation = null).ready)
        assertFalse(guide.diagnose(image(.2), world, true, orientation = null).ready)
    }

    @Test fun otherLiftsStillNeedAClearlySeenWrist() {
        val guide = CameraCaptureGuide.forExercise("dumbbell_curl", true).copy(viewTarget = null)
        assertFalse(guide.diagnose(image(.3), world, true, orientation = null).ready)
    }
}
