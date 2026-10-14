package dev.kyungmin.kcoach.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForearmRotationRepDetectorTest {
    @Test fun explicitlyFrozenV3ReplaysItsHistoricalStopBoundary() {
        val profile = ExerciseTrackingProfiles.resolve("external_rotation")
            .copy(id = requireNotNull(MotionFamily.FOREARM_ROTATION.legacyAlgorithmId))
        val detector = ForearmRotationRepDetector(TrackingProfileSnapshot.decode(TrackingProfileSnapshot.encode(profile)))
        var timestamp = 1_000_000_000L
        for (rate in listOf(1.45, -1.45)) repeat(36) { index ->
            detector.add(naturalSample(timestamp, rate, index)); timestamp += 20_000_000L
        }
        assertTrue(detector.finish() != null)
    }
    @Test fun manualStopWhileReturningDoesNotCompleteTheMovingLobe() {
        val detector = ForearmRotationRepDetector(ExerciseTrackingProfiles.resolve("external_rotation"))
        var timestamp = 1_000_000_000L
        var count = 0
        for (rate in listOf(1.45, -1.45)) repeat(36) { index ->
            if (detector.add(naturalSample(timestamp, rate, index)) != null) count++
            timestamp += 20_000_000L
        }
        assertEquals(0, count)
        assertEquals(null, detector.finish())
    }

    @Test fun twoRotationsWithSamePolarityAndAQuietGapCannotMakeAnOutAndBackRep() {
        val detector = ForearmRotationRepDetector(ExerciseTrackingProfiles.resolve("external_rotation"))
        var timestamp = 1_000_000_000L
        var count = 0
        repeat(2) {
            repeat(36) { index ->
                if (detector.add(naturalSample(timestamp, 1.45, index)) != null) count++
                timestamp += 20_000_000L
            }
            repeat(12) {
                if (detector.add(naturalSample(timestamp, 0.0, it)) != null) count++
                timestamp += 20_000_000L
            }
        }
        assertEquals(0, count)
    }
    @Test fun knownUnreliableRotationsCannotCompleteARep() {
        val detector = ForearmRotationRepDetector(ExerciseTrackingProfiles.resolve("external_rotation"))
        var t = 1_000_000_000L
        var count = 0
        repeat(3) {
            for (rate in listOf(1.45, -1.45)) repeat(36) { i ->
                if (detector.add(naturalSample(t, rate, i).copy(sensorAccuracy = 0)) != null) count++
                t += 20_000_000L
            }
            t += 180_000_000L
        }
        assertEquals(0, count)
        assertTrue(detector.finish() == null)
    }
    @Test
    fun countsNaturalSixtyDegreeRepsAtNormalTempo() {
        val profile = ExerciseTrackingProfiles.resolve("external_rotation")
        val detector = ForearmRotationRepDetector(profile)
        var timestamp = 1_000_000_000L
        var events = 0
        // 36 samples x 20 ms x 1.45 rad/s ~= 60 degrees per direction.
        repeat(5) {
            repeat(36) { index ->
                val sample = naturalSample(timestamp, 1.45, index)
                if (detector.add(sample) != null) events += 1
                timestamp += 20_000_000L
            }
            repeat(36) { index ->
                val sample = naturalSample(timestamp, -1.45, index)
                if (detector.add(sample) != null) events += 1
                timestamp += 20_000_000L
            }
            timestamp += 180_000_000L
        }
        repeat(12) { index ->
            if (detector.add(naturalSample(timestamp, 0.0, index)) != null) events += 1
            timestamp += 20_000_000L
        }
        if (detector.finish() != null) events += 1
        assertEquals(5, events)
    }

    @Test
    fun countsControlledOutAndBackRotationButNotAccelerationShaking() {
        val detector = ForearmRotationRepDetector()
        var timestamp = 1_000_000_000L
        var events = 0
        repeat(3) {
            repeat(20) {
                val gyro = 1.25
                if (detector.add(sample(timestamp, gyro, 0.0f)) != null) events += 1
                timestamp += 50_000_000L
            }
            repeat(10) {
                if (detector.add(sample(timestamp, 0.0, 0.0f)) != null) events += 1
                timestamp += 50_000_000L
            }
            repeat(20) {
                if (detector.add(sample(timestamp, -1.25, 0.0f)) != null) events += 1
                timestamp += 50_000_000L
            }
            repeat(10) {
                if (detector.add(sample(timestamp, 0.0, 0.0f)) != null) events += 1
                timestamp += 50_000_000L
            }
        }
        if (detector.finish() != null) events += 1
        assertEquals(3, events)

        val shakingDetector = ForearmRotationRepDetector()
        timestamp = 1_000_000_000L
        repeat(120) {
            val accelerationShock = if (it % 2 == 0) 7.0f else -7.0f
            assertTrue(shakingDetector.add(sample(timestamp, 0.08, accelerationShock)) == null)
            timestamp += 50_000_000L
        }
        assertTrue(shakingDetector.finish() == null)
    }

    @Test
    fun `counts the returned final rep after the wrist settles without finish`() {
        val detector = ForearmRotationRepDetector(ExerciseTrackingProfiles.resolve("external_rotation"))
        var timestamp = 1_000_000_000L
        var events = 0
        repeat(36) { index ->
            if (detector.add(naturalSample(timestamp, 1.45, index)) != null) events += 1
            timestamp += 20_000_000L
        }
        repeat(36) { index ->
            if (detector.add(naturalSample(timestamp, -1.45, index)) != null) events += 1
            timestamp += 20_000_000L
        }
        repeat(12) {
            if (detector.add(naturalSample(timestamp, 0.0, it)) != null) events += 1
            timestamp += 20_000_000L
        }

        assertEquals(1, events)
        assertEquals(1, detector.diagnostics().detectedReps)
        assertTrue(detector.finish() == null)
    }

    @Test
    fun `brief cable setup jerk does not pin counting to the wrong sensor axis`() {
        val detector = ForearmRotationRepDetector(ExerciseTrackingProfiles.resolve("external_rotation"))
        var timestamp = 1_000_000_000L
        var events = 0

        // A short X-axis handle/setup movement before the controlled Y-axis rep.
        repeat(2) {
            if (detector.add(vectorSample(timestamp, 1.2, 0.0, 0.0)) != null) events += 1
            timestamp += 20_000_000L
        }
        repeat(12) {
            if (detector.add(vectorSample(timestamp, 0.0, 0.0, 0.0)) != null) events += 1
            timestamp += 20_000_000L
        }
        repeat(42) {
            if (detector.add(vectorSample(timestamp, 0.05, 0.42, 0.04)) != null) events += 1
            timestamp += 20_000_000L
        }
        repeat(42) {
            if (detector.add(vectorSample(timestamp, -0.05, -0.42, -0.04)) != null) events += 1
            timestamp += 20_000_000L
        }
        repeat(12) {
            if (detector.add(vectorSample(timestamp, 0.0, 0.0, 0.0)) != null) events += 1
            timestamp += 20_000_000L
        }

        assertEquals(1, events)
        assertTrue(detector.diagnostics().axisLearningResets >= 1)
        assertEquals(1, detector.diagnostics().detectedReps)
    }

    @Test
    fun `slow external rotation counts while the watch axis curves through three dimensions`() {
        val detector = ForearmRotationRepDetector(ExerciseTrackingProfiles.resolve("external_rotation"))
        var timestamp = 1_000_000_000L
        var events = 0
        val samplesPerHalf = 50
        val rate = 0.34

        repeat(3) {
            repeat(samplesPerHalf) { index ->
                val angle = (PI / 2.0) * index / (samplesPerHalf - 1)
                val sample = vectorSample(
                    timestamp,
                    rate * sin(angle),
                    rate * cos(angle),
                    rate * 0.12 * sin(angle * 2.0),
                )
                if (detector.add(sample) != null) events += 1
                timestamp += 20_000_000L
            }
            repeat(samplesPerHalf) { index ->
                val angle = (PI / 2.0) * (samplesPerHalf - 1 - index) / (samplesPerHalf - 1)
                val sample = vectorSample(
                    timestamp,
                    -rate * sin(angle),
                    -rate * cos(angle),
                    -rate * 0.12 * sin(angle * 2.0),
                )
                if (detector.add(sample) != null) events += 1
                timestamp += 20_000_000L
            }
            repeat(12) {
                if (detector.add(vectorSample(timestamp, 0.0, 0.0, 0.0)) != null) events += 1
                timestamp += 20_000_000L
            }
        }

        assertEquals(3, events)
        assertEquals(3, detector.diagnostics().detectedReps)
    }

    private fun sample(timestampNs: Long, gyroX: Double, accelerationY: Float): SensorSample =
        SensorSample(
            timestampNs = timestampNs,
            accelerationX = 0.0f,
            accelerationY = accelerationY,
            accelerationZ = if (accelerationY == 0.0f) 9.80665f else 9.80665f,
            gyroscopeX = gyroX.toFloat(),
            gyroscopeY = 0.0f,
            gyroscopeZ = 0.0f,
        )

    private fun naturalSample(timestampNs: Long, gyroY: Double, index: Int): SensorSample =
        SensorSample(
            timestampNs = timestampNs,
            accelerationX = if (index % 2 == 0) 0.08f else -0.06f,
            accelerationY = 0.0f,
            accelerationZ = 9.80665f,
            gyroscopeX = if (index % 3 == 0) 0.08f else -0.05f,
            gyroscopeY = gyroY.toFloat(),
            gyroscopeZ = 0.04f,
        )

    private fun vectorSample(timestampNs: Long, gyroX: Double, gyroY: Double, gyroZ: Double) = SensorSample(
        timestampNs = timestampNs,
        accelerationX = 0.0f,
        accelerationY = 0.0f,
        accelerationZ = 9.80665f,
        gyroscopeX = gyroX.toFloat(),
        gyroscopeY = gyroY.toFloat(),
        gyroscopeZ = gyroZ.toFloat(),
    )
}
