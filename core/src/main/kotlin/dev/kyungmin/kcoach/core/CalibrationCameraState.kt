package dev.kyungmin.kcoach.core

/** Monotonic per-attempt phone state; delayed camera=true must not overwrite a confirmation. */
data class CalibrationCameraState(val sessionId: String, val setId: String, val attemptId: String,
    val revision: Long, val recording: Boolean, val confirmedReps: Int? = null) {
    init {
        require(CapturePurpose.forSession(sessionId) == CapturePurpose.PAIRED_REFERENCE)
        require(listOf(sessionId, setId, attemptId).all { it.matches(Regex("[A-Za-z0-9-]{1,160}")) })
        require(revision > 0 && (confirmedReps == null || confirmedReps in 0..9999) && (!recording || confirmedReps == null))
    }
    fun encode(): String = listOf("1", sessionId, setId, attemptId, revision, recording, confirmedReps ?: "").joinToString("|")
    fun supersedes(old: CalibrationCameraState): Boolean = sessionId == old.sessionId && setId == old.setId &&
        attemptId == old.attemptId && revision > old.revision
    companion object {
        fun decode(raw: String): CalibrationCameraState {
            require(raw.length <= 600)
            val f = raw.split('|'); require(f.size == 7 && f[0] == "1")
            return CalibrationCameraState(f[1], f[2], f[3], f[4].toLong(), f[5].toBooleanStrict(), f[6].takeIf { it.isNotEmpty() }?.toInt())
        }
    }
}
