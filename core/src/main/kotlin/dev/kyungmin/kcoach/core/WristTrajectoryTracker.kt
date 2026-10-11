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

    /** Feed one gyro sample (rad/s). Returns a finished segment when a movement just ended. */
    fun onGyro(timestampNanos: Long, x: Float, y: Float, z: Float): MotionSegment? {
        val a = accel ?: return null
        val dt = (timestampNanos - lastGyroNanos) / 1e9
        lastGyroNanos = timestampNanos
        if (dt <= 0.0 || dt > 0.1) {
            resetMotion()
            return null
        }
        val gx = x.toDouble()
        val gy = y.toDouble()
        val gz = z.toDouble()
        val aNorm = a.length()
        val gyroQuiet = sqrt(gx * gx + gy * gy + gz * gz) < stillGyroRadPerSec

        if (!initialized) {
            if (gyroQuiet && abs(aNorm - gravity) < stillAccelDeviation) initializeFromTilt(a)
            return null
        }
        // Inside a movement the accelerometer also carries the lift itself, so
        // orientation runs on the gyro alone (a few seconds of gyro drift is
        // small); gravity correction resumes once the wrist is still again.
        updateOrientation(gx, gy, gz, a, aNorm, dt, !moving && gyroQuiet && abs(aNorm - gravity) < stillAccelDeviation)

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
            return finishSegment()
        }
        if ((timestampNanos - times.first()) / 1e9 > maxSegmentSec) resetMotion()
        return null
    }

    private fun finishSegment(): MotionSegment? {
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
    }

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
        gravity = a.length()
        initialized = true
    }

    private fun updateOrientation(gxIn: Double, gyIn: Double, gzIn: Double, a: Vec3, aNorm: Double, dt: Double, trustGravity: Boolean) {
        var gx = gxIn
        var gy = gyIn
        var gz = gzIn
        val gain = if (trustGravity) 2.0 else 0.0
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
