package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.math.hypot

data class CalibrationQuality(val orientationRatio: Double, val visibilityRatio: Double, val cameraReps: Int?,
    val failures: List<String>) {
    val eligible: Boolean get() = failures.isEmpty()
    val message: String get() = failures.firstOrNull()?.let { "$it 보정에 적용하지 않아요" } ?: "촬영 품질과 세 가지 횟수를 확인했어요"
}

/** Recomputed from immutable frames at training AND adoption; saved green is never authority. */
object CalibrationSafety {
    const val MIN_REPS = 8
    const val MIN_ORIENTATION_RATIO = .90
    const val MIN_VISIBILITY_RATIO = .95
    fun evaluate(frames: List<CameraPoseFrame>, exerciseId: String, left: Boolean,
        watchReps: Int?, confirmedReps: Int?): CalibrationQuality {
        val guide = CameraCaptureGuide.forExercise(exerciseId, left)
        val failures = linkedSetOf<String>()
        val tracker = CameraOrientationTracker()
        var oriented = 0; var visible = 0
        var previous: CameraLandmark? = null; var previousWidth: Double? = null
        frames.forEach { frame ->
            val image = frame.imageLandmarks.singleOrNull(); val world = frame.worldLandmarks.singleOrNull()
            val good = frame.uniquelyVisible && frame.videoFrameWritten && frame.error == null && image != null && world != null
            if (good && guide.visible(image!!)) visible++
            val direction = tracker.observe(frame.cameraTimestampNs, image.takeIf { good }, world.takeIf { good }, left,
                guide.viewTarget ?: CameraOrientationPolicy.target(exerciseId))
            if (good && direction.accepted && direction.rawYaw?.let {
                    abs(abs(it) - guide.viewTarget!!.degrees) <= CameraOrientationPolicy.TOLERANCE } == true) oriented++
            if (frame.imageLandmarks.size > 1) failures += "여러 사람이 함께 보여요."
            if (good) {
                val center = image!![23].copy(x = (image[23].x + image[24].x) / 2, y = (image[23].y + image[24].y) / 2)
                val width = hypot(world!![11].x - world[12].x, world[11].z - world[12].z)
                if (previous?.let { hypot(center.x - it.x, center.y - it.y) > .20 } == true ||
                    previousWidth?.let { width / it !in .65..1.55 } == true) failures += "사람 위치나 크기가 크게 바뀌었어요."
                previous = center; previousWidth = width.takeIf { it > .08 }
            }
        }
        if (frames.size < 30 || frames.zipWithNext().any { (a, b) ->
                b.cameraTimestampNs <= a.cameraTimestampNs || b.cameraTimestampNs - a.cameraTimestampNs > CameraOrientationPolicy.MAX_GAP_NS })
            failures += "촬영 프레임이 부족하거나 끊겼어요."
        val orientationRatio = if (frames.isEmpty()) 0.0 else oriented.toDouble() / frames.size
        val visibilityRatio = if (frames.isEmpty()) 0.0 else visible.toDouble() / frames.size
        if (orientationRatio < MIN_ORIENTATION_RATIO) failures += "몸 방향이 맞는 구간이 부족해요."
        if (visibilityRatio < MIN_VISIBILITY_RATIO) failures += "필수 관절이 가려진 구간이 많아요."
        val reps = guide.metric?.let { CameraRepProposals.propose(frames, exerciseId, left).size }
        if (reps == null) failures += "이 운동은 카메라 횟수를 검증할 수 없어요."
        if (confirmedReps == null || confirmedReps < MIN_REPS) failures += "확인한 반복이 ${MIN_REPS}회 이상 필요해요."
        if (watchReps == null || confirmedReps == null || reps == null || watchReps != confirmedReps || reps != confirmedReps)
            failures += "카메라·워치·확인 횟수가 일치하지 않아요."
        return CalibrationQuality(orientationRatio, visibilityRatio, reps, failures.toList())
    }
}

object CalibrationRegressionGuard {
    fun worse(expected: Int, generic: Int?, calibrated: Int?): Boolean = generic != null &&
        (calibrated == null || abs(calibrated - expected) > abs(generic - expected))
}
