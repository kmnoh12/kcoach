package dev.kyungmin.kcoach.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WristTrajectoryTrackerTest {
    private val g = 9.81
    private val stepNanos = 10_000_000L // 100 Hz

    /** World-frame movement of [distance] metres along [axis] in [seconds], zero velocity at both ends. */
    private fun movementAccel(axis: Vec3, distance: Double, seconds: Double, t: Double): Vec3 {
        val a = distance * 2 * PI / (seconds * seconds) * sin(2 * PI * t / seconds)
        return axis * a
    }

    private class Feed(val tracker: WristTrajectoryTracker) {
        var t = 0L
        val segments = mutableListOf<MotionSegment>()
        fun step(accelBody: Vec3, stepNanos: Long) {
            t += stepNanos
            tracker.onAccel(t, accelBody.x.toFloat(), accelBody.y.toFloat(), accelBody.z.toFloat())
            segments += tracker.onGyro(t, 0f, 0f, 0f)
        }
    }

    private fun Feed.still(seconds: Double, toBody: (Vec3) -> Vec3) {
        repeat((seconds * 100).toInt()) { step(toBody(Vec3(0.0, 0.0, g)), stepNanos) }
    }

    private fun Feed.move(axis: Vec3, distance: Double, seconds: Double, toBody: (Vec3) -> Vec3, bias: Vec3 = Vec3.ZERO) {
        val n = (seconds * 100).toInt()
        for (i in 1..n) {
            val world = movementAccel(axis, distance, seconds, i / 100.0) + Vec3(0.0, 0.0, g)
            step(toBody(world) + bias, stepNanos)
        }
    }

    private val flat: (Vec3) -> Vec3 = { it }

    @Test
    fun `flat watch moving 40cm along x yields one segment with that displacement`() {
        val feed = Feed(WristTrajectoryTracker())
        feed.still(1.0, flat)
        feed.move(Vec3(1.0, 0.0, 0.0), 0.4, 1.0, flat)
        feed.still(0.5, flat)

        assertEquals(1, feed.segments.size)
        val end = feed.segments.single().points.last()
        assertEquals(0.4, end.x, 0.03)
        assertEquals(0.0, end.y, 0.02)
        assertEquals(0.0, end.z, 0.02)
    }

    @Test
    fun `tilted watch still reports world frame displacement`() {
        // Watch rotated 90 degrees about x: world z is body y, world y is body -z.
        val tilted: (Vec3) -> Vec3 = { w -> Vec3(w.x, w.z, -w.y) }
        val feed = Feed(WristTrajectoryTracker())
        feed.still(1.0, tilted)
        feed.move(Vec3(0.0, 0.0, 1.0), 0.3, 0.8, tilted)
        feed.still(0.5, tilted)

        val end = feed.segments.single().points.last()
        assertEquals(0.3, end.z, 0.03)
        assertEquals(0.0, end.x, 0.02)
        assertEquals(0.0, end.y, 0.02)
    }

    @Test
    fun `constant accelerometer bias is mostly removed by the zero velocity update`() {
        val feed = Feed(WristTrajectoryTracker())
        feed.still(1.0, flat)
        feed.move(Vec3(1.0, 0.0, 0.0), 0.4, 1.0, flat, bias = Vec3(0.08, 0.0, 0.0))
        feed.still(0.5, flat)

        // Uncorrected, a 0.08 m/s² bias over 1 s adds 4 cm; the linear velocity
        // correction should keep the error well under that.
        val end = feed.segments.single().points.last()
        assertTrue(abs(end.x - 0.4) < 0.02, "x=${end.x}")
    }

    @Test
    fun `curl-like up pause down produces two segments in opposite directions`() {
        val feed = Feed(WristTrajectoryTracker())
        val up = Vec3(0.0, 0.0, 1.0)
        feed.still(1.0, flat)
        feed.move(up, 0.35, 0.9, flat)
        feed.still(0.4, flat)
        feed.move(up * -1.0, 0.35, 1.1, flat)
        feed.still(0.5, flat)

        assertEquals(2, feed.segments.size)
        assertEquals(0.35, feed.segments[0].points.last().z, 0.03)
        assertEquals(-0.35, feed.segments[1].points.last().z, 0.03)
        assertTrue(feed.segments[0].peakSpeedMps > feed.segments[1].peakSpeedMps)
    }

    @Test
    fun `probe frames round trip`() {
        val frames = listOf(
            ProbeWire.Samples(
                seq = 7,
                sentAtNanos = 123_456_789L,
                screenOn = true,
                samples = listOf(
                    ProbeWire.Sample(ProbeWire.SENSOR_ACCEL, 1L, 0.1f, 9.8f, -0.2f),
                    ProbeWire.Sample(ProbeWire.SENSOR_GYRO, 2L, 0.3f, -0.4f, 0.5f),
                ),
            ),
            ProbeWire.Ping(42L),
            ProbeWire.Pong(42L, 99L),
        )
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out -> frames.forEach { ProbeWire.write(out, it) } }
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(frames, List(frames.size) { ProbeWire.read(input) })
    }
}
