package dev.kyungmin.kcoach.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SensorCaptureFormatTest {
    @Test
    fun `capture round trips motion samples and sparse heart rate`() {
        val records = listOf(
            SensorCaptureRecord(
                sample = SensorSample(
                    timestampNs = 1_000_000_000,
                    accelerationX = 0.1f,
                    accelerationY = 9.8f,
                    accelerationZ = -0.2f,
                    gyroscopeX = 0.3f,
                    gyroscopeY = -0.4f,
                    gyroscopeZ = 0.5f,
                    sensorAccuracy = 3,
                ),
                heartRateBpm = 121,
            ),
            SensorCaptureRecord(
                sample = SensorSample(
                    timestampNs = 1_020_000_000,
                    accelerationX = 0.2f,
                    accelerationY = 9.7f,
                    accelerationZ = -0.1f,
                    gyroscopeX = 0.4f,
                    gyroscopeY = -0.3f,
                    gyroscopeZ = 0.6f,
                ),
            ),
        )
        val output = ByteArrayOutputStream()

        SensorCaptureFormat.write(output, records, sampleRateRequestedHz = 50.0)
        val document = SensorCaptureFormat.read(ByteArrayInputStream(output.toByteArray()))

        assertEquals(2, document.formatVersion)
        assertEquals(50.0, document.sampleRateRequestedHz)
        assertEquals(2, document.sampleCount)
        assertEquals(1_000_000_000L, document.startMonotonicNs)
        assertEquals(1_020_000_000L, document.endMonotonicNs)
        assertEquals(records, document.records)
        assertTrue(document.validateTimestamps().isEmpty())
    }

    @Test
    fun `capture output is deterministic and has sha256`() {
        val records = listOf(
            SensorCaptureRecord(SensorSample(100L, 1f, 2f, 3f, 4f, 5f, 6f)),
        )
        val first = ByteArrayOutputStream()
        val second = ByteArrayOutputStream()

        SensorCaptureFormat.write(first, records, 25.0)
        SensorCaptureFormat.write(second, records, 25.0)

        assertContentEquals(first.toByteArray(), second.toByteArray())
        assertEquals(
            SensorCaptureFormat.sha256(first.toByteArray()),
            SensorCaptureFormat.sha256(second.toByteArray()),
        )
    }

    @Test
    fun `non increasing timestamps are rejected before persistence`() {
        val records = listOf(
            SensorCaptureRecord(SensorSample(100L, 0f, 0f, 0f, 0f, 0f, 0f)),
            SensorCaptureRecord(SensorSample(100L, 0f, 0f, 0f, 0f, 0f, 0f)),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            SensorCaptureFormat.write(ByteArrayOutputStream(), records, 50.0)
        }

        assertTrue(error.message.orEmpty().contains("strictly increasing"))
    }

    @Test
    fun `truncated capture is rejected`() {
        val records = listOf(
            SensorCaptureRecord(SensorSample(100L, 0f, 0f, 0f, 0f, 0f, 0f)),
        )
        val output = ByteArrayOutputStream()
        SensorCaptureFormat.write(output, records, 50.0)
        val truncated = output.toByteArray().copyOf(output.size() - 1)

        assertFailsWith<IllegalArgumentException> {
            SensorCaptureFormat.read(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun `replay visits records in timestamp order and reports duration`() {
        val document = SensorCaptureDocument(
            formatVersion = 1,
            sampleRateRequestedHz = 50.0,
            records = listOf(
                SensorCaptureRecord(SensorSample(1_000_000_000L, 0f, 0f, 0f, 0f, 0f, 0f)),
                SensorCaptureRecord(SensorSample(1_025_000_000L, 1f, 0f, 0f, 0f, 0f, 0f)),
                SensorCaptureRecord(SensorSample(1_050_000_000L, 2f, 0f, 0f, 0f, 0f, 0f)),
            ),
        )
        val visited = mutableListOf<Long>()

        val summary = SensorCaptureReplay.replay(document) { _, record ->
            visited += record.sample.timestampNs
        }

        assertEquals(listOf(1_000_000_000L, 1_025_000_000L, 1_050_000_000L), visited)
        assertEquals(3, summary.sampleCount)
        assertEquals(50L, summary.durationMs)
        assertTrue(summary.timestampErrors.isEmpty())
    }
}
