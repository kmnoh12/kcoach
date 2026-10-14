package dev.kyungmin.kcoach.core

import java.io.ByteArrayInputStream

data class ValidatedCapture(
    val captureId: String,
    val document: SensorCaptureDocument,
)

object CaptureValidator {
    const val MAX_CAPTURE_BYTES = 32 * 1024 * 1024

    fun validate(manifest: WireEnvelope, bytes: ByteArray): ValidatedCapture {
        require(manifest.messageType == WireMessageType.CAPTURE_MANIFEST) {
            "invalid capture manifest type"
        }
        require(manifest.source == WireSource.WATCH) { "invalid capture source" }
        require(bytes.size <= MAX_CAPTURE_BYTES) { "capture exceeds size limit" }

        val captureId = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty()
        require(captureId.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "invalid capture id" }
        val expectedSize = manifest.payload[WirePayloadKeys.ASSET_SIZE_BYTES]?.toLongOrNull()
            ?: error("missing asset size")
        require(expectedSize in 0..MAX_CAPTURE_BYTES.toLong()) { "invalid capture asset size" }
        require(bytes.size.toLong() == expectedSize) { "capture size mismatch" }
        val expectedVersion = manifest.payload[WirePayloadKeys.FORMAT_VERSION]?.toIntOrNull()
            ?: error("missing capture format version")
        require(expectedVersion in SensorCaptureFormat.SUPPORTED_VERSIONS) {
            "capture format version mismatch"
        }
        require(manifest.payload[WirePayloadKeys.COMPRESSION] == "NONE") {
            "unsupported capture compression"
        }
        val expectedSha = manifest.payload[WirePayloadKeys.SHA256].orEmpty()
        require(expectedSha.matches(Regex("[0-9a-fA-F]{64}"))) { "invalid capture sha256" }
        require(SensorCaptureFormat.sha256(bytes).equals(expectedSha, ignoreCase = true)) {
            "capture sha256 mismatch"
        }

        val document = SensorCaptureFormat.read(ByteArrayInputStream(bytes))
        require(document.formatVersion == expectedVersion) { "capture format version mismatch" }
        val expectedSamples = manifest.payload[WirePayloadKeys.SAMPLE_COUNT]?.toIntOrNull()
            ?: error("missing sample count")
        require(expectedSamples >= 0 && document.sampleCount == expectedSamples) {
            "capture sample count mismatch"
        }
        val expectedStart = manifest.payload[WirePayloadKeys.START_MONOTONIC_NS]?.toLongOrNull()
            ?: error("missing capture start timestamp")
        val expectedEnd = manifest.payload[WirePayloadKeys.END_MONOTONIC_NS]?.toLongOrNull()
            ?: error("missing capture end timestamp")
        require(document.startMonotonicNs.orZero() == expectedStart) { "capture start timestamp mismatch" }
        require(document.endMonotonicNs.orZero() == expectedEnd) { "capture end timestamp mismatch" }
        val observedRate = manifest.payload[WirePayloadKeys.SAMPLE_RATE_OBSERVED]?.toDoubleOrNull()
            ?: error("missing observed sample rate")
        require(observedRate.isFinite() && observedRate >= 0.0) { "invalid observed sample rate" }
        return ValidatedCapture(captureId, document)
    }

    private fun Long?.orZero(): Long = this ?: 0L
}
