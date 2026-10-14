package dev.kyungmin.kcoach.core

import kotlin.math.*

data class CameraObservedCycle(
    val startNs: Long, val endNs: Long, val frames: Int,
    val minimumElbowAngleDegrees: Double, val maximumElbowAngleDegrees: Double,
    val torsoTiltVariationDegrees: Double, val maximumElbowPositionChangeShoulderWidths: Double,
    val averageSimultaneousElbowAngleDifferenceDegrees: Double?,
) {
    init {
        require(startNs >= 0 && endNs > startNs && frames >= 3)
        require(minimumElbowAngleDegrees in 0.0..180.0 && maximumElbowAngleDegrees in minimumElbowAngleDegrees..180.0)
        require(torsoTiltVariationDegrees.isFinite() && torsoTiltVariationDegrees >= 0 &&
            maximumElbowPositionChangeShoulderWidths.isFinite() && maximumElbowPositionChangeShoulderWidths >= 0)
        require(averageSimultaneousElbowAngleDifferenceDegrees == null || averageSimultaneousElbowAngleDifferenceDegrees in 0.0..180.0)
    }
}
data class CameraObservedMetrics(val watchOnLeft: Boolean, val simultaneousArmsReviewed: Boolean,
    val cycles: List<CameraObservedCycle>) {
    init {
        require(cycles.size in 1..600 && cycles.zipWithNext().all { (a, b) -> a.endNs <= b.startNs })
        require(simultaneousArmsReviewed || cycles.all { it.averageSimultaneousElbowAngleDifferenceDegrees == null })
    }
}

object CameraObservationQuality {
    fun imageLandmarksVisible(frame: CameraPoseFrame, indices: Set<Int>): Boolean = frame.uniquelyVisible &&
        frame.videoFrameWritten && frame.timestampSource == CameraTimestampSource.PHONE_ELAPSED_REALTIME && indices.all { index ->
            if (index !in 0..32) return@all false
            val point = frame.imageLandmarks.single()[index]
            point.x in 0.0..1.0 && point.y in 0.0..1.0 && (point.visibility ?: 0.0) >= .65 && (point.presence ?: 0.0) >= .65
        }
}

/** Camera estimates in their stated coordinates; no posture score, global IMU trajectory, or clinical ROM. */
object CameraObservedAnalyzer {
    fun analyze(frames: List<CameraPoseFrame>, reviewedCycles: List<RepCycle>, watchOnLeft: Boolean,
        simultaneousArmsReviewed: Boolean = false): CameraObservedMetrics? {
        if (reviewedCycles.isEmpty() || reviewedCycles.size > 600 || frames.size > 10_000 ||
            frames.zipWithNext().any { (a, b) -> a.cameraTimestampNs >= b.cameraTimestampNs } ||
            reviewedCycles.zipWithNext().any { (a, b) -> a.endNs > b.startNs }) return null
        val shoulder = if (watchOnLeft) 11 else 12
        val elbow = if (watchOnLeft) 13 else 14
        val wrist = if (watchOnLeft) 15 else 16
        val required = setOf(11, 12, 23, 24, shoulder, elbow, wrist) + if (simultaneousArmsReviewed) setOf(13, 14, 15, 16) else emptySet()
        fun angle(points: List<CameraLandmark>, a: Int, b: Int, c: Int): Double? {
            // The matching image landmarks must carry confidence above. World metadata is optional;
            // reject a low confidence if supplied, rather than manufacturing a missing world score.
            if (setOf(a, b, c).any { points[it].visibility?.let { v -> v < .65 } == true ||
                    points[it].presence?.let { v -> v < .65 } == true }) return null
            val first = doubleArrayOf(points[a].x - points[b].x, points[a].y - points[b].y, points[a].z - points[b].z)
            val second = doubleArrayOf(points[c].x - points[b].x, points[c].y - points[b].y, points[c].z - points[b].z)
            val firstNorm = sqrt(first.sumOf { it * it }); val secondNorm = sqrt(second.sumOf { it * it })
            if (firstNorm < 1e-6 || secondNorm < 1e-6) return null
            return Math.toDegrees(acos((first.indices.sumOf { first[it] * second[it] } / firstNorm / secondNorm).coerceIn(-1.0, 1.0)))
        }
        val metrics = reviewedCycles.map { cycle ->
            val visible = frames.filter { it.cameraTimestampNs in cycle.startNs..cycle.endNs }
            if (visible.size < 3 || visible.first().cameraTimestampNs != cycle.startNs || visible.last().cameraTimestampNs != cycle.endNs ||
                visible.none { it.cameraTimestampNs == cycle.turnaroundNs } || visible.any { !CameraObservationQuality.imageLandmarksVisible(it, required) } ||
                visible.zipWithNext().any { (a, b) -> b.cameraTimestampNs - a.cameraTimestampNs > 150_000_000 } ||
                visible.map { listOf(it.width, it.height, it.rotationDegrees, it.previewMirrored, it.phoneClockId) }.distinct().size != 1) return null
            val elbowAngles = visible.map { frame -> angle(frame.worldLandmarks.single(), shoulder, elbow, wrist) ?: return null }
            val torsoTilts = mutableListOf<Double>()
            val elbowPositions = mutableListOf<Pair<Double, Double>>()
            visible.forEach { frame ->
                // Normalized x/y use different dimensions. Convert both axes to pixels before geometry.
                val image = frame.imageLandmarks.single()
                fun x(i: Int) = image[i].x * frame.width
                fun y(i: Int) = image[i].y * frame.height
                val sx = (x(11) + x(12)) / 2; val sy = (y(11) + y(12)) / 2
                val hx = (x(23) + x(24)) / 2; val hy = (y(23) + y(24)) / 2
                val shoulderWidth = hypot(x(11) - x(12), y(11) - y(12))
                val torsoLength = hypot(sx - hx, sy - hy)
                // A near side-on shoulder projection cannot provide a stable shoulder-width denominator.
                if (torsoLength < 1e-6 || shoulderWidth / torsoLength < .15) return null
                torsoTilts.add(Math.toDegrees(atan2(sx - hx, hy - sy)))
                val ux = (sx - hx) / torsoLength; val uy = (sy - hy) / torsoLength
                val dx = x(elbow) - sx; val dy = y(elbow) - sy
                elbowPositions.add((dx * -uy + dy * ux) / shoulderWidth to (dx * ux + dy * uy) / shoulderWidth)
            }
            val firstPosition = elbowPositions.first()
            val elbowChange = elbowPositions.maxOf { hypot(it.first - firstPosition.first, it.second - firstPosition.second) }
            // Unwrap image-plane angles around the first observed angle before computing a range.
            val firstTilt = torsoTilts.first()
            val unwrapped = torsoTilts.map { angle -> firstTilt + ((angle - firstTilt + 540) % 360 - 180) }
            val bilateral = if (simultaneousArmsReviewed) visible.map { frame ->
                val p = frame.worldLandmarks.single()
                abs((angle(p, 11, 13, 15) ?: return null) - (angle(p, 12, 14, 16) ?: return null))
            }.average() else null
            CameraObservedCycle(cycle.startNs, cycle.endNs, visible.size, elbowAngles.min(), elbowAngles.max(),
                unwrapped.max() - unwrapped.min(), elbowChange, bilateral)
        }
        return CameraObservedMetrics(watchOnLeft, simultaneousArmsReviewed, metrics)
    }
}
