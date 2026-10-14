package dev.kyungmin.kcoach.core

enum class CapturePurpose(val preservePreparation: Boolean, val stopAtTarget: Boolean) {
    WORKOUT(false, false),
    LEGACY_CALIBRATION(false, true),
    PAIRED_REFERENCE(true, false);

    companion object {
        fun forSession(sessionId: String): CapturePurpose = when {
            sessionId.startsWith("paired-") -> PAIRED_REFERENCE
            sessionId.startsWith("calibration-") -> LEGACY_CALIBRATION
            else -> WORKOUT
        }
    }
}
