package dev.kyungmin.kcoach.core

enum class ManualSetOperation { COMPLETE, EDIT }
enum class ManualSetStatus { APPLIED, DUPLICATE, CONFLICT, STALE, NO_SESSION, REJECTED }

/** Actual performed reps are mandatory; there is intentionally no target-count fallback. */
data class ManualSetCommand(
    val commandId: String,
    val sessionId: String,
    val exerciseId: String,
    val setIndex: Int,
    val setId: String,
    val attemptId: String,
    val performedReps: Int,
    val expectedRevision: Long,
    val operation: ManualSetOperation,
) {
    init {
        require(commandId.matches(ID) && setId.matches(ID) && attemptId.matches(ID))
        require(sessionId.isNotBlank() && exerciseId.isNotBlank() && setIndex >= 0)
        require(performedReps in 0..9_999 && expectedRevision >= 0)
    }

    fun toEnvelope(elapsedMs: Long, wallMs: Long): WireEnvelope {
        require(elapsedMs >= 0 && wallMs >= 0)
        return WireEnvelope(messageId = commandId, messageType = WireMessageType.MANUAL_SET,
            sessionId = sessionId, setId = setId, source = WireSource.WATCH,
            sentAtElapsedMs = elapsedMs, sentAtWallMs = wallMs,
            payload = mapOf("exerciseId" to exerciseId, "setIndex" to setIndex.toString(),
                "attemptId" to attemptId, "performedReps" to performedReps.toString(),
                "expectedRevision" to expectedRevision.toString(), "operation" to operation.name))
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9._-]{1,160}")

        fun fromEnvelope(envelope: WireEnvelope): ManualSetCommand {
            require(envelope.messageType == WireMessageType.MANUAL_SET && envelope.source == WireSource.WATCH)
            fun field(key: String) = requireNotNull(envelope.payload[key]) { "Missing manual field: $key" }
            return ManualSetCommand(envelope.messageId, envelope.sessionId, field("exerciseId"),
                field("setIndex").toInt(), requireNotNull(envelope.setId), field("attemptId"),
                field("performedReps").toInt(), field("expectedRevision").toLong(),
                ManualSetOperation.valueOf(field("operation")))
        }
    }
}

data class ManualSetAck(
    val commandId: String,
    val sessionId: String,
    val setId: String,
    val attemptId: String,
    val status: ManualSetStatus,
    val currentRevision: Long,
    val currentReps: Int?,
) {
    init {
        require(commandId.isNotBlank() && sessionId.isNotBlank() && setId.isNotBlank() && attemptId.isNotBlank())
        require(currentRevision >= 0 && (currentReps == null || currentReps in 0..9_999))
    }

    fun encode(elapsedMs: Long, wallMs: Long): String = WireCodec.encode(WireEnvelope(
        messageType = WireMessageType.ACK, sessionId = sessionId, setId = setId, source = WireSource.PHONE,
        sentAtElapsedMs = elapsedMs, sentAtWallMs = wallMs,
        payload = mapOf("commandId" to commandId, "attemptId" to attemptId, "manualStatus" to status.name,
            "currentRevision" to currentRevision.toString(), "currentReps" to currentReps?.toString().orEmpty())))

    companion object {
        fun decode(encoded: String): ManualSetAck {
            val envelope = WireCodec.decode(encoded)
            require(envelope.messageType == WireMessageType.ACK && envelope.source == WireSource.PHONE)
            fun field(key: String) = requireNotNull(envelope.payload[key]) { "Missing manual acknowledgement: $key" }
            return ManualSetAck(field("commandId"), envelope.sessionId, requireNotNull(envelope.setId),
                field("attemptId"), ManualSetStatus.valueOf(field("manualStatus")),
                field("currentRevision").toLong(), field("currentReps").takeIf { it.isNotEmpty() }?.toInt())
        }
    }
}
