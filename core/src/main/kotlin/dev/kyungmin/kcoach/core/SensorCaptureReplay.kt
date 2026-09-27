package dev.kyungmin.kcoach.core

/** Deterministic replay result used by analyzer tests and offline inspection. */
data class CaptureReplaySummary(
    val sampleCount: Int,
    val durationMs: Long,
    val timestampErrors: List<String>,
)

object SensorCaptureReplay {
    fun replay(
        document: SensorCaptureDocument,
        onRecord: (index: Int, record: SensorCaptureRecord) -> Unit,
    ): CaptureReplaySummary {
        document.records.forEachIndexed(onRecord)
        val durationMs = if (document.records.size >= 2) {
            ((document.records.last().sample.timestampNs - document.records.first().sample.timestampNs) / 1_000_000L)
                .coerceAtLeast(0L)
        } else {
            0L
        }
        return CaptureReplaySummary(
            sampleCount = document.sampleCount,
            durationMs = durationMs,
            timestampErrors = document.validateTimestamps(),
        )
    }
}
