package dev.kyungmin.kcoach.phone

import android.content.Context

/** Outcome of deleting several saved captures; [blocked] maps a kept capture to why, as a clause ("…서"). */
internal data class CaptureDeletionResult(val deleted: List<String>, val blocked: Map<String, String>)

/**
 * Deletes saved reference captures the user no longer wants. Evidence a saved calibration candidate still verifies against
 * is never removed, because that would silently break the candidate's later checks.
 */
internal class CalibrationCaptureDeletion(context: Context) {
    private val captures = CalibrationCaptureStore(context)
    private val candidates = PatternCandidateStore(context)

    /** Null when [id] may be deleted, otherwise the reason as a clause. An unreadable capture folder may be removed. */
    fun blocker(id: String): String? {
        val capture = captures.load(id).getOrNull() ?: return null
        if (capture.status == CalibrationCaptureStatus.RECORDING) return "촬영 중이라"
        val used = candidates.list().getOrNull().orEmpty().any { candidate -> candidate.profile.sources.any { it.captureId == id } }
        return if (used) "보정 후보에 쓰여서" else null
    }

    fun delete(ids: Collection<String>): CaptureDeletionResult {
        val deleted = mutableListOf<String>(); val blocked = linkedMapOf<String, String>()
        ids.forEach { id ->
            val reason = blocker(id)
            when {
                reason != null -> blocked[id] = reason
                captures.delete(id).isSuccess -> deleted += id
                else -> blocked[id] = "삭제하지 못해서"
            }
        }
        return CaptureDeletionResult(deleted, blocked)
    }
}
