package dev.kyungmin.kcoach.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.HexFormat

class SensorCaptureClockTest {
    @Test fun originalAccelerometerGyroscopeAndCallbackTimesSurviveCaptureRoundTrip() {
        val record = SensorCaptureRecord(SensorSample(1_000_000_000, 1f, 2f, 3f, 4f, 5f, 6f, 3), 100,
            accelerationTimestampNs = 995_000_000, gyroscopeTimestampNs = 1_000_000_000,
            receivedElapsedNs = 1_008_000_000, accelerationAccuracy = 2, heartRateTimestampNs = 980_000_000)
        val output = ByteArrayOutputStream()
        SensorCaptureFormat.write(output, listOf(record), 50.0)
        val decoded = SensorCaptureFormat.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals(2, decoded.formatVersion)
        assertEquals(record, decoded.records.single())
        assertEquals(5_000_000L, decoded.records.single().gyroscopeTimestampNs!! - decoded.records.single().accelerationTimestampNs!!)
    }

    @Test fun oldKcs1RecordRemainsReadableAndMissingClockInformationIsNotInvented() {
        // Published v1 layout: one sample at 100ns, six zero floats and unavailable accuracy/HR.
        val bytes = HexFormat.of().parseHex("4b435331000000010000000140490000000000000000000000000064" + "00000000".repeat(6) + "ffffffffffffffff")
        val decoded = SensorCaptureFormat.read(ByteArrayInputStream(bytes))
        assertEquals(1, decoded.formatVersion)
        assertEquals(100L, decoded.records.single().sample.timestampNs)
        assertNull(decoded.records.single().accelerationTimestampNs)
        assertNull(decoded.records.single().receivedElapsedNs)
    }
}
