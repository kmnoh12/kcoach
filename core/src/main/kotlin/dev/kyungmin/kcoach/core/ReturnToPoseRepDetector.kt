package dev.kyungmin.kcoach.core

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Flexion/extension counter: leave the starting wrist tilt, then return to it.
 * Uses gravity-relative tilt only; it does not reconstruct a 3D path or joint angle.
 * 60° departure / 20° return are provisional motion hysteresis, not form criteria.
 */
internal class ReturnToPoseRepDetector(private val minimumRepMs: Long) {
    private val filtered = DoubleArray(3)
    private var baseline: DoubleArray? = null
    private var lastTimestampNs = 0L
    private var invalidGravitySinceNs = 0L
    private var waitForBottom = false
    private var departed = false
    private var startNs = 0L
    private var peakNs = 0L
    private var peakTilt = 0.0
    private var peakGyro = 0.0

    fun reset() {
        baseline = null
        lastTimestampNs = 0L
        invalidGravitySinceNs = 0L
        waitForBottom = false
        resetCycle()
    }

    fun add(sample: SensorSample): ProfiledMotionRepEvent? {
        val t = sample.timestampNs
        val values = doubleArrayOf(sample.accelerationX.toDouble(), sample.accelerationY.toDouble(), sample.accelerationZ.toDouble())
        val gyro = doubleArrayOf(sample.gyroscopeX.toDouble(), sample.gyroscopeY.toDouble(), sample.gyroscopeZ.toDouble())
        if (t <= 0L || values.any { !it.isFinite() } || gyro.any { !it.isFinite() }) {
            resetCycle(); waitForBottom = true
            return null
        }
        if (lastTimestampNs != 0L && t <= lastTimestampNs) {
            resetCycle(); waitForBottom = true
            return null
        }
        val dt = if (lastTimestampNs == 0L) 0.0 else (t - lastTimestampNs) / 1e9
        lastTimestampNs = t
        if (baseline == null) {
            val norm = norm(values)
            if (norm !in 6.0..14.0) return null
            values.copyInto(filtered)
            baseline = DoubleArray(3) { values[it] / norm }
            return null
        }
        if (dt > 0.45) {
            resetCycle(); waitForBottom = true
            values.copyInto(filtered)
        } else {
            val alpha = 1.0 - exp(-dt / 0.15)
            for (axis in 0..2) filtered[axis] += alpha * (values[axis] - filtered[axis])
        }
        val magnitude = norm(filtered)
        if (magnitude !in 6.0..14.0) {
            // Tolerate brief dynamic acceleration, but do not bridge a sustained
            // loss of the gravity estimate. Match the existing 450ms sample-gap boundary.
            if (invalidGravitySinceNs == 0L) invalidGravitySinceNs = t
            if (t - invalidGravitySinceNs >= 450_000_000L) {
                resetCycle()
                waitForBottom = true
            }
            return null
        }
        invalidGravitySinceNs = 0L
        val reference = baseline!!
        val tilt = acos((filtered.indices.sumOf { filtered[it] * reference[it] } / magnitude).coerceIn(-1.0, 1.0))
        if (waitForBottom) {
            if (tilt <= RETURN_TILT) waitForBottom = false
            return null
        }
        if (tilt > RETURN_TILT && startNs == 0L) startNs = t
        if (tilt > peakTilt) { peakTilt = tilt; peakNs = t }
        peakGyro = max(peakGyro, norm(gyro))
        if (tilt >= DEPARTURE_TILT) departed = true
        if (tilt > RETURN_TILT) return null

        val durationMs = if (startNs == 0L) 0L else (t - startNs) / 1_000_000L
        val event = if (departed && durationMs >= minimumRepMs) {
            ProfiledMotionRepEvent(startNs, peakNs, t, durationMs, peakGyro, peakTilt * 2.0, 0.0,
                // Internal excursion score only; this is not an accuracy probability.
                (peakTilt / (PI / 2)).coerceIn(0.0, 1.0))
        } else null
        resetCycle()
        return event
    }

    private fun resetCycle() {
        departed = false; startNs = 0L; peakNs = 0L; peakTilt = 0.0; peakGyro = 0.0
    }

    private fun norm(v: DoubleArray) = sqrt(v.sumOf { it * it })

    companion object {
        private const val DEPARTURE_TILT = PI / 3
        private const val RETURN_TILT = PI / 9
    }
}
