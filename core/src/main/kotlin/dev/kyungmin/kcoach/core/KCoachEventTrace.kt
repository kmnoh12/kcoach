package dev.kyungmin.kcoach.core

enum class KCoachEventMarker {
    SET_COMPLETE_RECEIVED,
    SET_STATE_MUTATED,
    SNAPSHOT_SAVED,
    REST_COMMAND_SENT,
    ACTIVE_UI_UPDATED,
    CAPTURE_MANIFEST_RECEIVED,
    CAPTURE_ASSET_RECEIVED,
    CAPTURE_VALIDATED,
    ANALYSIS_STARTED,
    ANALYSIS_FINISHED,
    RESULT_SAVED,
    CAPTURE_ACK_SENT,
    CAPTURE_ACK_RECEIVED,
    EVENT_FAILED,
}

/** Stable, grep-friendly event markers shared by the phone and watch runtimes. */
object KCoachEventTrace {
    fun format(
        marker: KCoachEventMarker,
        sessionId: String = "",
        exerciseId: String = "",
        setIndex: Int? = null,
        captureId: String = "",
        threadName: String = Thread.currentThread().name,
        timestampMs: Long = System.currentTimeMillis(),
        detail: String = "",
    ): String = buildString {
        append("event=").append(marker.name)
        append(" sessionId=").append(sanitize(sessionId))
        append(" exerciseId=").append(sanitize(exerciseId))
        append(" setIndex=").append(setIndex?.toString().orEmpty())
        append(" captureId=").append(sanitize(captureId))
        append(" thread=").append(sanitize(threadName))
        append(" timestamp=").append(timestampMs)
        if (detail.isNotBlank()) append(" detail=").append(sanitize(detail))
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("\\s+"), "_")
        .replace('=', ':')
        .take(240)
}
