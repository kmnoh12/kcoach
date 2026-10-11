package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun length() = sqrt(x * x + y * y + z * z)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

/** One movement between two still moments, positions in metres relative to its start (world frame, z up). */
data class MotionSegment(
    val startNanos: Long,
    val endNanos: Long,
    val points: List<Vec3>,
    val pathLengthM: Double,
    val displacementM: Double,
    val peakSpeedMps: Double,
) {
    val durationSec: Double get() = (endNanos - startNanos) / 1e9
}

/**
 * Wrist trajectory from a single watch IMU.
 *
 * Orientation comes from a Mahony filter (gyro integration corrected toward
 * gravity while the accelerometer is trustworthy). Position is integrated only
 * inside one movement: every still moment (top/bottom of a rep) resets velocity
 * to zero, and the residual end velocity is removed as a linear drift before
 * positions are recomputed. Long continuous motion is not tracked.
 */
class WristTrajectoryTracker(
    private val stillGyroRadPerSec: Double = 0.35,
    private val stillAccelDeviation: Double = 0.6,
    private val stillSamplesRequired: Int = 8,
    private val stillVelocityMps: Double = 0.15,
    private val minSegmentSec: Double = 0.2,
    private val maxSegmentSec: Double = 6.0,
    private val axisTimeConstantSec: Double = 2.0,
    private val velocityLeakSec: Double = 1.5,
    private val turnaroundHysteresisMps: Double = 0.08,
    private val reversalFraction: Double = 0.4,
    private val movingGravityGain: Double = 0.1,
    private val movingGravityWindow: Double = 3.0,
    private val tailMaxSec: Double = 0.6,
    private val tailMaxFraction: Double = 0.35,
) {
    private var q0 = 1.0
    private var q1 = 0.0
    private var q2 = 0.0
    private var q3 = 0.0
    private var initialized = false
    private var gravity = 9.81

    private var accel: Vec3? = null
    private var lastGyroNanos = 0L
    private var stillCount = 0
    private var moving = false
    private var velocity = Vec3.ZERO

    private val times = ArrayList<Long>()
    private val accels = ArrayList<Vec3>()

    /** Orientation as (w, x, y, z), body → world. */
    val orientation: DoubleArray get() = doubleArrayOf(q0, q1, q2, q3)
    val isMoving: Boolean get() = moving

    fun onAccel(timestampNanos: Long, x: Float, y: Float, z: Float) {
        accel = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
    }

    /**
     * Feed one gyro sample (rad/s). Returns movements that just finished
     * (usually none, at most two).
     *
     * A movement split at a direction reversal is held back for one step: if
     * what follows is a short, small piece that ends in stillness, it was the
     * high-pass filter's undershoot at the end of one stroke, not a real
     * return stroke, and the two are merged.
     */
    fun onGyro(timestampNanos: Long, x: Float, y: Float, z: Float): List<MotionSegment> {
        val end = step(timestampNanos, x, y, z) ?: return emptyList()
        val held = pending
        if (end.byReversal) {
            pending = end
            return listOfNotNull(held?.segment)
        }
        pending = null
        if (held == null) return listOfNotNull(end.segment)
        val heldSegment = held.segment ?: return listOfNotNull(end.segment)
        val tail = end.segment == null ||
            (end.segment.durationSec < tailMaxSec && end.segment.displacementM < tailMaxFraction * heldSegment.displacementM)
        if (!tail) return listOfNotNull(heldSegment, end.segment)
        // Recompute over the joined raw data so the zero-velocity correction
        // spans the whole stroke instead of the false reversal point.
        val merged = finishSegment(held.times + end.times.drop(1), held.accels + end.accels.drop(1))
        return listOfNotNull(merged ?: heldSegment)
    }

    private class SegmentEnd(val segment: MotionSegment?, val byReversal: Boolean, val times: List<Long>, val accels: List<Vec3>)

    private var pending: SegmentEnd? = null

    /** Returns how the current movement ended, or null while nothing ended. */
    private fun step(timestampNanos: Long, x: Float, y: Float, z: Float): SegmentEnd? {
        val a = accel ?: return null
        val dt = (timestampNanos - lastGyroNanos) / 1e9
        if (dt <= 0.0) return null // duplicate timestamp
        lastGyroNanos = timestampNanos
        if (dt > 0.1) {
            resetMotion()
            return null
        }
        val gx = x.toDouble()
        val gy = y.toDouble()
        val gz = z.toDouble()
        val aNorm = a.length()
        val gyroQuiet = sqrt(gx * gx + gy * gy + gz * gz) < stillGyroRadPerSec

        if (!initialized) {
            // Loose gate: people rarely stand perfectly still before a set.
            if (gyroQuiet && abs(aNorm - gravity) < 1.5) initializeFromTilt(a)
            return null
        }
        // Strong gravity correction when still; a weak one during movement,
        // where the lift's own acceleration averages out over a set but gyro
        // drift alone would tilt the estimate by tens of degrees.
        val gravityGain = when {
            !moving && gyroQuiet && abs(aNorm - gravity) < stillAccelDeviation -> 2.0
            abs(aNorm - gravity) < movingGravityWindow -> movingGravityGain
            else -> 0.0
        }
        updateOrientation(gx, gy, gz, a, aNorm, dt, gravityGain)

        // Still = no rotation and no linear acceleration once gravity is removed
        // in the world frame (a pure translation keeps |a| close to g).
        val linear = rotateToWorld(a) - Vec3(0.0, 0.0, gravity)
        val still = gyroQuiet && linear.length() < stillAccelDeviation
        stillCount = if (still) stillCount + 1 else 0
        if (still && !moving) gravity = 0.99 * gravity + 0.01 * aNorm

        if (!moving) {
            if (!still) {
                moving = true
                times.clear()
                accels.clear()
                velocity = Vec3.ZERO
            } else {
                return null
            }
        }
        if (accels.isNotEmpty()) velocity += (linear + accels.last()) * (0.5 * dt)
        times += timestampNanos
        accels += linear

        // A smooth rep passes through zero acceleration at full speed, so a
        // quiet window alone is not enough: also require the running velocity
        // to be near zero, or a long quiet window if drift kept it high.
        val ended = stillCount >= stillSamplesRequired &&
            (velocity.length() < stillVelocityMps || stillCount >= stillSamplesRequired * 5)
        if (ended) {
            moving = false
            resetTurnaround()
            return SegmentEnd(finishSegment(times, accels), false, times.toList(), accels.toList())
        }
        // Continuous reps never pause, but the wrist's velocity along the main
        // movement axis still reverses at the top and bottom: that reversal is
        // also a zero-velocity moment, so the movement is split there.
        if (detectTurnaround(linear, dt)) {
            val end = SegmentEnd(finishSegment(times, accels), true, times.toList(), accels.toList())
            times.clear()
            accels.clear()
            times += timestampNanos
            accels += linear
            velocity = Vec3.ZERO
            return end
        }
        if ((timestampNanos - times.first()) / 1e9 > maxSegmentSec) {
            resetMotion()
            pending = null
        }
        return null
    }

    private fun finishSegment(times: List<Long>, accels: List<Vec3>): MotionSegment? {
        if (times.size < 3) return null
        val duration = (times.last() - times.first()) / 1e9
        if (duration < minSegmentSec) return null

        val velocities = ArrayList<Vec3>(times.size)
        var v = Vec3.ZERO
        velocities += v
        for (i in 1 until times.size) {
            val dt = (times[i] - times[i - 1]) / 1e9
            v += (accels[i] + accels[i - 1]) * (0.5 * dt)
            velocities += v
        }
        // Zero-velocity update: the wrist is still at both ends, so any end
        // velocity is integration drift. Remove it linearly over the movement.
        val endVelocity = velocities.last()
        val t0 = times.first()
        val corrected = velocities.mapIndexed { i, vi -> vi - endVelocity * ((times[i] - t0) / 1e9 / duration) }

        val points = ArrayList<Vec3>(times.size)
        var p = Vec3.ZERO
        points += p
        var path = 0.0
        for (i in 1 until times.size) {
            val dt = (times[i] - times[i - 1]) / 1e9
            val step = (corrected[i] + corrected[i - 1]) * (0.5 * dt)
            p += step
            path += step.length()
            points += p
        }
        return MotionSegment(
            startNanos = times.first(),
            endNanos = times.last(),
            points = points,
            pathLengthM = path,
            displacementM = p.length(),
            peakSpeedMps = corrected.maxOf { it.length() },
        )
    }

    private fun resetMotion() {
        moving = false
        stillCount = 0
        times.clear()
        accels.clear()
        resetTurnaround()
    }

    private val linearReversal = ReversalDetector(axisTimeConstantSec, turnaroundHysteresisMps, reversalFraction, velocityLeakSec)

    private fun resetTurnaround() = linearReversal.reset()

    private fun detectTurnaround(linear: Vec3, dt: Double): Boolean {
        val reversed = linearReversal.update(linear, dt)
        debugTrace?.invoke(linear, Vec3.ZERO, linearReversal.value)
        return reversed
    }

    /**
     * Velocity reversals along the dominant movement axis: the slow mean of
     * the acceleration is removed (residual gravity leak, bias), the axis is
     * the dominant eigenvector of a decaying covariance (power iteration), and
     * the projected acceleration is leak-integrated into a velocity.
     */
    private class ReversalDetector(
        private val timeConstantSec: Double,
        private val hysteresis: Double,
        private val reversalFraction: Double,
        private val velocityLeakSec: Double,
    ) {
        private val covariance = DoubleArray(9)
        private var axis = Vec3(0.0, 0.0, 1.0)
        private var mean = Vec3.ZERO
        private var armedSign = 0
        private var armedPeak = 0.0
        var value = 0.0
            private set

        fun reset() {
            value = 0.0
            armedSign = 0
            armedPeak = 0.0
        }

        fun update(signal: Vec3, dt: Double): Boolean {
            val k = (dt / timeConstantSec).coerceAtMost(1.0)
            mean += (signal - mean) * k
            val hp = signal - mean
            val v = doubleArrayOf(hp.x, hp.y, hp.z)
            for (i in 0 until 3) for (j in 0 until 3) covariance[i * 3 + j] += k * (v[i] * v[j] - covariance[i * 3 + j])
            val next = Vec3(
                covariance[0] * axis.x + covariance[1] * axis.y + covariance[2] * axis.z,
                covariance[3] * axis.x + covariance[4] * axis.y + covariance[5] * axis.z,
                covariance[6] * axis.x + covariance[7] * axis.y + covariance[8] * axis.z,
            )
            if (next.length() > 1e-9) axis = next * (1.0 / next.length())

            val along = hp.x * axis.x + hp.y * axis.y + hp.z * axis.z
            value = value * (1.0 - dt / velocityLeakSec) + along * dt
            if (armedSign != 0 && value * armedSign > 0) armedPeak = maxOf(armedPeak, abs(value))
            // Filtering makes a single one-way move undershoot below zero, so a
            // reversal only counts when the new direction is a sizeable
            // fraction of the previous one (a real return stroke).
            val threshold = maxOf(hysteresis, reversalFraction * armedPeak)
            val sign = when {
                value > threshold -> 1
                value < -threshold -> -1
                armedSign == 0 && value > hysteresis -> 1
                armedSign == 0 && value < -hysteresis -> -1
                else -> 0
            }
            if (sign == 0 || sign == armedSign) return false
            val crossed = armedSign != 0
            armedSign = sign
            armedPeak = abs(value)
            return crossed
        }
    }

    internal var debugTrace: ((linear: Vec3, axis: Vec3, axisVelocity: Double) -> Unit)? = null

    private fun initializeFromTilt(a: Vec3) {
        // Shortest rotation taking the measured "up" (body) to world +z.
        val n = a * (1.0 / a.length())
        val w = 1.0 + n.z
        if (w < 1e-6) {
            q0 = 0.0; q1 = 1.0; q2 = 0.0; q3 = 0.0
        } else {
            val norm = sqrt(w * w + n.y * n.y + n.x * n.x)
            q0 = w / norm; q1 = n.y / norm; q2 = -n.x / norm; q3 = 0.0
        }
        if (abs(a.length() - 9.81) < stillAccelDeviation) gravity = a.length()
        initialized = true
    }

    private fun updateOrientation(gxIn: Double, gyIn: Double, gzIn: Double, a: Vec3, aNorm: Double, dt: Double, gain: Double) {
        var gx = gxIn
        var gy = gyIn
        var gz = gzIn
        if (gain > 0.0 && aNorm > 1e-6) {
            val ax = a.x / aNorm
            val ay = a.y / aNorm
            val az = a.z / aNorm
            val vx = 2 * (q1 * q3 - q0 * q2)
            val vy = 2 * (q0 * q1 + q2 * q3)
            val vz = q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3
            gx += gain * (ay * vz - az * vy)
            gy += gain * (az * vx - ax * vz)
            gz += gain * (ax * vy - ay * vx)
        }
        val h = 0.5 * dt
        val n0 = q0 + h * (-q1 * gx - q2 * gy - q3 * gz)
        val n1 = q1 + h * (q0 * gx + q2 * gz - q3 * gy)
        val n2 = q2 + h * (q0 * gy - q1 * gz + q3 * gx)
        val n3 = q3 + h * (q0 * gz + q1 * gy - q2 * gx)
        val norm = sqrt(n0 * n0 + n1 * n1 + n2 * n2 + n3 * n3)
        q0 = n0 / norm; q1 = n1 / norm; q2 = n2 / norm; q3 = n3 / norm
    }

    private fun rotateToWorld(v: Vec3): Vec3 {
        val x = (1 - 2 * (q2 * q2 + q3 * q3)) * v.x + 2 * (q1 * q2 - q0 * q3) * v.y + 2 * (q1 * q3 + q0 * q2) * v.z
        val y = 2 * (q1 * q2 + q0 * q3) * v.x + (1 - 2 * (q1 * q1 + q3 * q3)) * v.y + 2 * (q2 * q3 - q0 * q1) * v.z
        val z = 2 * (q1 * q3 - q0 * q2) * v.x + 2 * (q2 * q3 + q0 * q1) * v.y + (1 - 2 * (q1 * q1 + q2 * q2)) * v.z
        return Vec3(x, y, z)
    }

}
