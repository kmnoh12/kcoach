package dev.kyungmin.kcoach.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class CurlCycleRegressionTest {
    @Test fun `ten continuous bottom top bottom curls are ten reps`() {
        assertCount(10, curlSamples(10, 1.0, 1.0))
    }

    @Test fun `controlled three second lowering still completes each curl`() {
        assertCount(5, curlSamples(5, 1.0, 3.0))
    }

    @Test fun `pausing halfway up does not turn one lift into a completed repetition`() {
        var t = 1_000_000_000L
        var angle = 0.0
        val samples = buildList {
            repeat(25) { add(SensorSample(t, 0f, 0f, 9.8f, 0f, 0f, 0f)); t += 20_000_000L }
            repeat(2) {
                repeat(50) { angle += 0.87 * 0.02; add(SensorSample(t, 0f, (9.80665 * sin(angle)).toFloat(), (9.80665 * cos(angle)).toFloat(), 0.87f, 0f, 0f)); t += 20_000_000L }
                repeat(25) { add(SensorSample(t, 0f, (9.80665 * sin(angle)).toFloat(), (9.80665 * cos(angle)).toFloat(), 0f, 0f, 0f)); t += 20_000_000L }
            }
        }
        assertCount(0, samples)
    }

    @Test fun `normal continuous brisk curls do not need an artificial rest between reps`() {
        assertCount(10, curlSamples(10, 0.6, 0.6))
    }

    @Test fun `a small reversal near the top is not a return to the bottom`() {
        var t = 1_000_000_000L
        var angle = 0.0
        val samples = buildList {
            repeat(25) { add(SensorSample(t, 0f, 0f, 9.8f, 0f, 0f, 0f)); t += 20_000_000L }
            for (rate in listOf(1.75f, 0f, -0.2f, 0f)) repeat(50) {
                angle += rate * 0.02
                add(SensorSample(t, 0f, (9.80665 * sin(angle)).toFloat(), (9.80665 * cos(angle)).toFloat(), rate, 0f, 0f)); t += 20_000_000L
            }
        }
        assertCount(0, samples)
    }

    @Test fun `top hold and slow lowering do not expire a valid repetition`() {
        assertCount(5, curlSamples(5, 1.0, 3.0, topHoldSeconds = 2.5))
    }

    @Test fun `watch axes do not change the bottom top bottom definition`() {
        val rotated = curlSamples(5, 1.0, 3.0).map { s -> s.copy(
            accelerationX = s.accelerationZ, accelerationY = s.accelerationX, accelerationZ = s.accelerationY,
            gyroscopeX = s.gyroscopeZ, gyroscopeY = s.gyroscopeX, gyroscopeZ = s.gyroscopeY,
        ) }
        assertCount(5, rotated)
    }

    @Test fun `a sensor gap cannot complete a partly observed curl`() {
        val samples = curlSamples(3, 1.0, 1.0).mapIndexed { index, s ->
            if (index >= 60) s.copy(timestampNs = s.timestampNs + 1_000_000_000L) else s
        }
        assertCount(2, samples)
    }

    @Test fun `invalid acceleration interval cannot bridge an unobserved curl`() {
        for ((reps, expected) in listOf(1 to 0, 3 to 2)) {
            val samples = curlSamples(reps, 1.0, 1.0)
            val insertAt = 75
            val gapNs = 1_000_000_000L
            val invalidStart = samples[insertAt].timestampNs
            val invalid = (0 until 50).map { i ->
                SensorSample(invalidStart + i * 20_000_000L, 0f, 0f, 0f, 0f, 0f, 0f)
            }
            val interrupted = samples.take(insertAt) + invalid + samples.drop(insertAt).map {
                it.copy(timestampNs = it.timestampNs + gapNs)
            }
            assertCount(expected, interrupted)
            val phone = PhoneRepAnalyzer.analyze(
                SensorCaptureDocument(1, 50.0, interrupted.map(::SensorCaptureRecord)), "barbell_biceps_curl",
            )
            assertEquals(expected, phone.result.phoneReanalyzedReps)
        }
    }

    @Test fun `brief acceleration dropout does not discard an otherwise observed curl`() {
        val samples = curlSamples(1, 1.0, 1.0)
        val insertAt = 75
        val invalidStart = samples[insertAt].timestampNs
        val invalid = (0 until 10).map { i ->
            SensorSample(invalidStart + i * 20_000_000L, 0f, 0f, 0f, 0f, 0f, 0f)
        }
        val interrupted = samples.take(insertAt) + invalid + samples.drop(insertAt).map {
            it.copy(timestampNs = it.timestampNs + 200_000_000L)
        }
        assertCount(1, interrupted)
    }

    @Test fun `phone reanalysis uses the same slow curl cycle count`() {
        val samples = curlSamples(5, 1.0, 3.0)
        val result = PhoneRepAnalyzer.analyze(SensorCaptureDocument(1, 50.0, samples.map(::SensorCaptureRecord)), "barbell_biceps_curl").result
        assertEquals(5, result.phoneReanalyzedReps)
    }

    private fun assertCount(expected: Int, samples: List<SensorSample>) {
        val profile = ExerciseTrackingProfiles.resolve("barbell_biceps_curl")
        val detector = ProfiledMotionRepDetector(profile)
        detector.beginCounting()
        val count = samples.count { detector.add(it) != null } + if (detector.finish() != null) 1 else 0
        assertEquals(expected, count, "A repetition returns from the top to the starting bottom position")
    }

    companion object {
        /** A 100-degree elbow rotation with smooth acceleration, gravity and gyro in one frame. */
        fun curlSamples(reps: Int, upSeconds: Double, downSeconds: Double, topHoldSeconds: Double = 0.0): List<SensorSample> = buildList {
            var time = 1_000_000_000L
            val amplitude = 100.0 * PI / 180.0
            fun sample(angle: Double, angularRate: Double) {
                add(SensorSample(time, 0f, (9.80665 * sin(angle)).toFloat(), (9.80665 * cos(angle)).toFloat(), angularRate.toFloat(), 0f, 0f))
                time += 20_000_000L
            }
            repeat(25) { sample(0.0, 0.0) }
            repeat(reps) {
                val upSteps = (upSeconds * 50).toInt()
                for (i in 1..upSteps) {
                    val phase = i.toDouble() / upSteps
                    sample(amplitude * (1 - cos(PI * phase)) / 2, amplitude * PI * sin(PI * phase) / (2 * upSeconds))
                }
                repeat((topHoldSeconds * 50).toInt()) { sample(amplitude, 0.0) }
                val downSteps = (downSeconds * 50).toInt()
                for (i in 1..downSteps) {
                    val phase = i.toDouble() / downSteps
                    sample(amplitude * (1 + cos(PI * phase)) / 2, -amplitude * PI * sin(PI * phase) / (2 * downSeconds))
                }
            }
            repeat(25) { sample(0.0, 0.0) }
        }
    }
}
