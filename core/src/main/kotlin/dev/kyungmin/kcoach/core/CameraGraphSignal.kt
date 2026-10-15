package dev.kyungmin.kcoach.core

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The replay graph plots the one image-space signal that actually moves in an exercise, so its title never claims a
 * quantity the exercise does not change (a cable external rotation barely lifts the wrist; it turns the forearm).
 * Values come from image landmarks in frame pixels; they describe the video, not true joint angles.
 */
enum class CameraGraphSignal(val title: String) {
    WRIST_HEIGHT("영상 속 손목 높이"),
    ELBOW_ANGLE("영상 속 팔꿈치 각도"),
    FOREARM_DIRECTION("영상 속 팔뚝 방향"),
    KNEE_ANGLE("영상 속 무릎 각도");

    private fun needed(left: Boolean): List<Int> = when (this) {
        WRIST_HEIGHT -> listOf(if (left) 15 else 16)
        ELBOW_ANGLE -> if (left) listOf(11, 13, 15) else listOf(12, 14, 16)
        FOREARM_DIRECTION -> if (left) listOf(13, 15) else listOf(14, 16)
        KNEE_ANGLE -> if (left) listOf(23, 25, 27) else listOf(24, 26, 28)
    }

    /** Null when a needed joint is unseen, so the graph shows a gap instead of a guess. */
    fun value(points: List<CameraLandmark>, width: Double, height: Double, left: Boolean): Double? {
        val joints = needed(left).map { index ->
            val p = points.getOrNull(index) ?: return null
            if (minOf(p.visibility ?: 0.0, p.presence ?: 0.0) < CameraCaptureGuide.MIN_CONFIDENCE ||
                p.x !in .02..0.98 || p.y !in .02..0.98) return null
            p
        }
        fun x(i: Int) = joints[i].x * width
        fun y(i: Int) = joints[i].y * height
        return when (this) {
            WRIST_HEIGHT -> 1 - joints[0].y
            ELBOW_ANGLE, KNEE_ANGLE -> {
                val ax = x(0) - x(1); val ay = y(0) - y(1); val bx = x(2) - x(1); val by = y(2) - y(1)
                val norm = hypot(ax, ay) * hypot(bx, by)
                if (norm < 1e-6) null else Math.toDegrees(acos(((ax * bx + ay * by) / norm).coerceIn(-1.0, 1.0)))
            }
            // Forearm pointing straight down is 0; sideways is +-90 whichever arm is measured.
            FOREARM_DIRECTION -> Math.toDegrees(atan2((x(1) - x(0)) * (if (left) 1.0 else -1.0), y(1) - y(0)))
        }
    }

    companion object {
        fun forExercise(exerciseId: String): CameraGraphSignal = when (ExerciseMeasurementContracts.resolve(exerciseId).recipe) {
            MeasurementRecipe.EXTERNAL_ROTATION -> FOREARM_DIRECTION
            MeasurementRecipe.ROW, MeasurementRecipe.CHEST_PRESS -> ELBOW_ANGLE
            MeasurementRecipe.SQUAT -> KNEE_ANGLE
            else -> WRIST_HEIGHT
        }
    }
}
