package dev.kyungmin.kcoach.core

import kotlin.test.*

class CameraObservedMetricsTest {
    private fun frames(mirror: Boolean = false): List<CameraPoseFrame> = (0..2).map { index ->
        val image = MutableList(33) { CameraLandmark(.5, .5, 0.0, 1.0, 1.0) }
        fun imagePoint(i: Int, x: Double, y: Double) { image[i] = CameraLandmark(x, y, 0.0, 1.0, 1.0) }
        imagePoint(11, .4, .3); imagePoint(12, .6, .3); imagePoint(23, .4, .7); imagePoint(24, .6, .7)
        imagePoint(13, .3, .45); imagePoint(15, .4, .6); imagePoint(14, .7, .45); imagePoint(16, .8, .6)
        val world = MutableList(33) { CameraLandmark(0.0, 0.0, 0.0, 1.0, 1.0) }
        fun worldPoint(i: Int, x: Double, y: Double) { world[i] = CameraLandmark(x, y, 0.0, 1.0, 1.0) }
        worldPoint(11, -.2, 0.0); worldPoint(13, -.4, .2); worldPoint(15, -.2, .4)
        worldPoint(12, .2, 0.0); worldPoint(14, .4, .2); worldPoint(16, .6, .4)
        CameraPoseFrame(index.toLong(), index * 100_000_000L, index * 100_000_000L,
            index * 100L, index * 100_000_000L, index * 100_000L, 200, 100, 0, mirror,
            CameraTimestampSource.PHONE_ELAPSED_REALTIME, "phone-epoch", listOf(image), listOf(world), true)
    }
    private val cycle = RepCycle(0, 100_000_000, 200_000_000, MusclePhase.CONCENTRIC)
    @Test fun previewMirroringNeverSwapsAnatomicalSidesAndBilateralNeedsExplicitReview() {
        for (mirror in listOf(false, true)) {
            val left = assertNotNull(CameraObservedAnalyzer.analyze(frames(mirror), listOf(cycle), true))
            assertEquals(90.0, left.cycles.single().minimumElbowAngleDegrees, 1e-6)
            assertNull(left.cycles.single().averageSimultaneousElbowAngleDifferenceDegrees)
            val right = assertNotNull(CameraObservedAnalyzer.analyze(frames(mirror), listOf(cycle), false, true))
            // acos is numerically sensitive near a fully straight 180-degree endpoint.
            assertEquals(180.0, right.cycles.single().minimumElbowAngleDegrees, 1e-5)
            assertEquals(90.0, right.cycles.single().averageSimultaneousElbowAngleDifferenceDegrees!!, 1e-5)
        }
    }
    @Test fun optionalWorldConfidenceIsNotFabricatedWhileImageConfidenceRemainsMandatory() {
        val noWorldScores = frames().map { frame -> frame.copy(worldLandmarks = listOf(frame.worldLandmarks.single().map {
            it.copy(visibility = null, presence = null)
        })) }
        assertNotNull(CameraObservedAnalyzer.analyze(noWorldScores, listOf(cycle), true))
        val noImageScores = noWorldScores.map { frame -> frame.copy(imageLandmarks = listOf(frame.imageLandmarks.single().map {
            it.copy(visibility = null, presence = null)
        })) }
        assertNull(CameraObservedAnalyzer.analyze(noImageScores, listOf(cycle), true))
    }
    @Test fun imageGeometryUsesPixelsForAspectRatioAndReportsOnlyObservedPlaneVariation() {
        val moved = frames().mapIndexed { index, frame ->
            if (index != 1) frame else frame.copy(imageLandmarks = listOf(frame.imageLandmarks.single().mapIndexed { i, point ->
                if (i in setOf(11, 12)) point.copy(x = point.x + .1) else point
            }))
        }
        val metrics = assertNotNull(CameraObservedAnalyzer.analyze(moved, listOf(cycle), true))
        assertEquals(26.565051, metrics.cycles.single().torsoTiltVariationDegrees, 1e-5)
        assertTrue(metrics.cycles.single().maximumElbowPositionChangeShoulderWidths > 0)
    }
    @Test fun torsoRelativeElbowPositionDoesNotMistakeWholeFrameTranslationForElbowSway() {
        val shifted = frames().mapIndexed { i, frame -> frame.copy(imageLandmarks = listOf(frame.imageLandmarks.single().map {
            it.copy(x = it.x + i * .02, y = it.y + i * .02)
        })) }
        assertEquals(0.0, assertNotNull(CameraObservedAnalyzer.analyze(shifted, listOf(cycle), true))
            .cycles.single().maximumElbowPositionChangeShoulderWidths, 1e-10)
    }
    @Test fun outOfFrameOccludedMultiplePersonUnknownClockOrMissingBodyCannotCreateMetrics() {
        fun check(change: (CameraPoseFrame) -> CameraPoseFrame) {
            val broken = frames().mapIndexed { i, frame -> if (i == 1) change(frame) else frame }
            assertNull(CameraObservedAnalyzer.analyze(broken, listOf(cycle), true))
        }
        check { frame -> frame.copy(imageLandmarks = listOf(frame.imageLandmarks.single().mapIndexed { i, p -> if (i == 13) p.copy(x = 1.1) else p })) }
        check { frame -> frame.copy(imageLandmarks = listOf(frame.imageLandmarks.single().mapIndexed { i, p -> if (i == 23) p.copy(visibility = .2) else p })) }
        check { frame -> frame.copy(imageLandmarks = frame.imageLandmarks + frame.imageLandmarks, worldLandmarks = frame.worldLandmarks + frame.worldLandmarks) }
        check { it.copy(timestampSource = CameraTimestampSource.UNKNOWN) }
        check { it.copy(phoneClockId = "other-epoch") }
        check { it.copy(videoFrameWritten = false) }
        check { frame -> frame.copy(worldLandmarks = listOf(frame.worldLandmarks.single().mapIndexed { i, p -> if (i == 13) p.copy(presence = .1) else p })) }
        assertNull(CameraObservedAnalyzer.analyze(frames().filterIndexed { i, _ -> i != 1 }, listOf(cycle), true))
    }
}
