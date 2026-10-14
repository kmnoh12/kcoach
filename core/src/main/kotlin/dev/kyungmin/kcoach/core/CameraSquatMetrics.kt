package dev.kyungmin.kcoach.core

import kotlin.math.*

/** Joint angles use relative world points; pelvis travel uses the fixed camera's image plane. */
data class CameraSquatCycle(val startNs: Long, val turnaroundNs: Long, val endNs: Long, val frames: Int,
    val minimumKneeDegrees: Double, val maximumKneeDegrees: Double,
    val minimumHipDegrees: Double, val maximumHipDegrees: Double,
    val torsoTiltVariationDegrees: Double, val pelvisSidewaysTorsoLengths: Double,
    val pelvisDropTorsoLengths: Double, val returnOffsetTorsoLengths: Double,
    val descendingMs: Long, val ascendingMs: Long, val markedPauseMs: Long?) {
    init {
        require(startNs >= 0 && startNs < turnaroundNs && turnaroundNs < endNs && frames in 3..6000)
        require(minimumKneeDegrees in 0.0..180.0 && maximumKneeDegrees in minimumKneeDegrees..180.0)
        require(minimumHipDegrees in 0.0..180.0 && maximumHipDegrees in minimumHipDegrees..180.0)
        require(listOf(torsoTiltVariationDegrees, pelvisSidewaysTorsoLengths, pelvisDropTorsoLengths,
            returnOffsetTorsoLengths).all { it.isFinite() && it >= 0 })
        require(descendingMs >= 0 && ascendingMs >= 0 && (markedPauseMs == null || markedPauseMs >= 0))
        val elapsed = (endNs - startNs) / 1_000_000
        require(descendingMs <= (turnaroundNs - startNs) / 1_000_000 && ascendingMs <= (endNs - turnaroundNs) / 1_000_000)
        require(markedPauseMs == null || markedPauseMs <= elapsed)
        val total = descendingMs + ascendingMs + (markedPauseMs ?: 0)
        require(total <= elapsed && elapsed - total <= 2) // Only integer-millisecond rounding is lost.
    }
}
data class CameraSquatMetrics(val watchOnLeft: Boolean, val cycles: List<CameraSquatCycle>) {
    init { require(cycles.size in 1..600 && cycles.zipWithNext().all { (a, b) -> a.endNs <= b.startNs }) }
}

/** Human-reviewed intervals only. No form score, clinical ROM, load speed, or global 3D pelvis height. */
object CameraSquatAnalyzer {
    fun analyze(frames: List<CameraPoseFrame>, cycles: List<RepCycle>, left: Boolean): CameraSquatMetrics? {
        if (cycles.isEmpty() || cycles.size > 600 || frames.size > 6000 ||
            frames.zipWithNext().any { (a, b) -> a.cameraTimestampNs >= b.cameraTimestampNs } ||
            cycles.zipWithNext().any { (a, b) -> a.endNs > b.startNs }) return null
        val guide = CameraCaptureGuide.forExercise("squat", left)
        val shoulder = if (left) 11 else 12; val hip = if (left) 23 else 24
        val knee = if (left) 25 else 26; val ankle = if (left) 27 else 28
        val rows = cycles.map { cycle ->
            if (cycle.departurePhase != MusclePhase.ECCENTRIC) return null
            val observed = frames.filter { it.cameraTimestampNs in cycle.startNs..cycle.endNs }
            if (observed.size < 3 || observed.first().cameraTimestampNs != cycle.startNs ||
                observed.last().cameraTimestampNs != cycle.endNs || observed.none { it.cameraTimestampNs == cycle.turnaroundNs } ||
                observed.any { !CameraObservationQuality.imageLandmarksVisible(it, guide.required) } ||
                observed.zipWithNext().any { (a, b) -> b.cameraTimestampNs - a.cameraTimestampNs > 150_000_000 } ||
                observed.map { listOf(it.width, it.height, it.rotationDegrees, it.previewMirrored, it.phoneClockId) }.distinct().size != 1) return null
            if (listOf(observed.first(), observed.last()).any { frame ->
                val value = guide.observedMetric(frame.imageLandmarks.single(), frame.worldLandmarks.single(), left)
                value == null || !CameraMotion.SQUAT_KNEE_FLEXION.atRest(value)
            }) return null
            fun pixel(frame: CameraPoseFrame, index: Int) = frame.imageLandmarks.single()[index].let { it.x * frame.width to it.y * frame.height }
            fun center(frame: CameraPoseFrame, first: Int, second: Int): Pair<Double, Double> {
                val a = pixel(frame, first); val b = pixel(frame, second)
                return (a.first + b.first) / 2 to (a.second + b.second) / 2
            }
            val initialShoulder = center(observed.first(), 11, 12)
            val initialPelvis = center(observed.first(), 23, 24)
            val scale = hypot(initialShoulder.first - initialPelvis.first, initialShoulder.second - initialPelvis.second)
            if (scale < 1e-6) return null
            val kneeAngles = mutableListOf<Double>(); val hipAngles = mutableListOf<Double>()
            val pelvis = mutableListOf<Pair<Double, Double>>(); val tilts = mutableListOf<Double>()
            observed.forEach { frame ->
                val image = frame.imageLandmarks.single(); val world = frame.worldLandmarks.single()
                val points = world.mapIndexed { i, p -> PosePoint(p.x, p.y, p.z,
                    minOf(image[i].visibility ?: 0.0, image[i].presence ?: 0.0, p.visibility ?: 1.0, p.presence ?: 1.0)) }
                kneeAngles += UpperBodyPoseGeometry.angle(points, hip, knee, ankle) ?: return null
                hipAngles += UpperBodyPoseGeometry.angle(points, shoulder, hip, knee) ?: return null
                val s = center(frame, 11, 12); val h = center(frame, 23, 24)
                if (hypot(s.first - h.first, s.second - h.second) < 1e-6) return null
                tilts += Math.toDegrees(atan2(s.first - h.first, h.second - s.second)); pelvis += h
            }
            val endPelvis = pelvis.last()
            CameraSquatCycle(cycle.startNs, cycle.turnaroundNs, cycle.endNs, observed.size,
                kneeAngles.min(), kneeAngles.max(), hipAngles.min(), hipAngles.max(), tilts.max() - tilts.min(),
                pelvis.maxOf { abs(it.first - initialPelvis.first) } / scale,
                pelvis.maxOf { (it.second - initialPelvis.second).coerceAtLeast(0.0) } / scale,
                hypot(endPelvis.first - initialPelvis.first, endPelvis.second - initialPelvis.second) / scale,
                requireNotNull(cycle.eccentricMs), requireNotNull(cycle.concentricMs), cycle.pauseMs.takeIf { cycle.pauses.isNotEmpty() })
        }
        return CameraSquatMetrics(left, rows)
    }
}
