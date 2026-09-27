package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CaptureValidationTest {
    @Test
    fun `valid manifest and capture are accepted`() {
        val fixture = fixture()

        val validated = CaptureValidator.validate(fixture.first, fixture.second)

        assertEquals("capture-1", validated.captureId)
        assertEquals(2, validated.document.sampleCount)
    }

    @Test
    fun `hash and sample count mismatches are rejected`() {
        val (manifest, bytes) = fixture()
        val badHash = manifest.copy(payload = manifest.payload + (WirePayloadKeys.SHA256 to "0".repeat(64)))
        val badCount = manifest.copy(payload = manifest.payload + (WirePayloadKeys.SAMPLE_COUNT to "3"))

        assertFailsWith<IllegalArgumentException> { CaptureValidator.validate(badHash, bytes) }
        assertFailsWith<IllegalArgumentException> { CaptureValidator.validate(badCount, bytes) }
    }

    @Test
    fun `non finite sensor values are rejected`() {
        val records = listOf(SensorCaptureRecord(SensorSample(1L, Float.NaN, 0f, 0f, 0f, 0f, 0f)))

        val error = assertFailsWith<IllegalArgumentException> {
            SensorCaptureFormat.write(ByteArrayOutputStream(), records, 50.0)
        }

        assertTrue(error.message.orEmpty().contains("non-finite"))
    }

    private fun fixture(): Pair<WireEnvelope, ByteArray> {
        val records = listOf(
            SensorCaptureRecord(SensorSample(1_000L, 0f, 1f, 2f, 3f, 4f, 5f), 100),
            SensorCaptureRecord(SensorSample(2_000L, 1f, 2f, 3f, 4f, 5f, 6f), 101),
        )
        val output = ByteArrayOutputStream()
        SensorCaptureFormat.write(output, records, 50.0)
        val bytes = output.toByteArray()
        val manifest = WireMessages.captureManifest(
            sessionId = "session-1",
            exerciseSessionId = "external_rotation",
            setId = "set-1",
            captureId = "capture-1",
            formatVersion = SensorCaptureFormat.FORMAT_VERSION,
            compression = "NONE",
            sampleCount = records.size,
            sampleRateRequestedHz = 50.0,
            sampleRateObservedHz = 50.0,
            startMonotonicNs = 1_000L,
            endMonotonicNs = 2_000L,
            sha256 = SensorCaptureFormat.sha256(bytes),
            assetSizeBytes = bytes.size.toLong(),
            watchDetectedReps = 0,
            exerciseId = "external_rotation",
            exerciseName = "External rotation",
            setIndex = 0,
        )
        return manifest to bytes
    }
}
