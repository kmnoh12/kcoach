package dev.kyungmin.kcoach.core

enum class WatchWorkoutAction { START_PREPARED, NEXT_SET }
enum class WatchWorkoutControlStatus { APPLIED, DUPLICATE, STALE, BUSY, NO_PLAN, REJECTED }

data class WatchWorkoutOffer(val sessionId: String, val routineName: String, val exerciseName: String,
    val setId: String, val attemptId: String, val planHash: String, val expiresAtMs: Long) {
    init {
        require(listOf(sessionId, setId, attemptId).all { it.matches(CONTROL_ID) })
        require(CapturePurpose.forSession(sessionId) == CapturePurpose.WORKOUT)
        require(planHash.matches(CONTROL_HASH) && expiresAtMs > 0 && routineName.length <= 200 && exerciseName.length <= 200)
    }
    fun encode(): String = WireCodec.encode(WireEnvelope(messageType = WireMessageType.HEARTBEAT,
        sessionId = sessionId, setId = setId, source = WireSource.PHONE, sentAtElapsedMs = 0, sentAtWallMs = 0,
        payload = mapOf("kind" to "WORKOUT_OFFER_V1", "routineName" to routineName, "exerciseName" to exerciseName,
            "attemptId" to attemptId, "planHash" to planHash, "expiresAtMs" to expiresAtMs.toString())))
    companion object {
        fun decode(value: String): WatchWorkoutOffer {
            require(value.length <= 12_000)
            val e = WireCodec.decode(value)
            require(e.source == WireSource.PHONE && e.messageType == WireMessageType.HEARTBEAT && e.payload["kind"] == "WORKOUT_OFFER_V1")
            return WatchWorkoutOffer(e.sessionId, e.payload.getValue("routineName"), e.payload.getValue("exerciseName"),
                requireNotNull(e.setId), e.payload.getValue("attemptId"), e.payload.getValue("planHash"), e.payload.getValue("expiresAtMs").toLong())
        }
    }
}

data class WatchWorkoutControl(val commandId: String, val action: WatchWorkoutAction, val sessionId: String,
    val setId: String, val attemptId: String, val planHash: String? = null, val side: String? = null, val placement: String? = null) {
    init {
        require(listOf(commandId, sessionId, setId, attemptId).all { it.matches(CONTROL_ID) })
        require(CapturePurpose.forSession(sessionId) == CapturePurpose.WORKOUT)
        require((action == WatchWorkoutAction.START_PREPARED) == (planHash != null))
        require(planHash == null || planHash.matches(CONTROL_HASH))
        require((side == null && placement == null) ||
            (action == WatchWorkoutAction.START_PREPARED && side in setOf("LEFT", "RIGHT") && placement in setOf("OUTSIDE", "INSIDE")))
    }
    fun encode(): String = WireCodec.encode(WireEnvelope(messageId = commandId, messageType = WireMessageType.HEARTBEAT,
        sessionId = sessionId, setId = setId, source = WireSource.WATCH, sentAtElapsedMs = 0, sentAtWallMs = 0,
        payload = mapOf("kind" to "WORKOUT_CONTROL_V1", "action" to action.name, "attemptId" to attemptId,
            "planHash" to planHash.orEmpty(), "side" to side.orEmpty(), "placement" to placement.orEmpty())))
    companion object {
        fun decode(value: String): WatchWorkoutControl {
            require(value.length <= 12_000)
            val e = WireCodec.decode(value)
            require(e.source == WireSource.WATCH && e.messageType == WireMessageType.HEARTBEAT && e.payload["kind"] == "WORKOUT_CONTROL_V1")
            return WatchWorkoutControl(e.messageId, WatchWorkoutAction.valueOf(e.payload.getValue("action")), e.sessionId,
                requireNotNull(e.setId), e.payload.getValue("attemptId"), e.payload.getValue("planHash").ifEmpty { null },
                e.payload.getValue("side").ifEmpty { null }, e.payload.getValue("placement").ifEmpty { null })
        }
    }
}

data class WatchWorkoutControlAck(val commandId: String, val sessionId: String, val setId: String,
    val attemptId: String, val status: WatchWorkoutControlStatus) {
    init { require(listOf(commandId, sessionId, setId, attemptId).all { it.matches(CONTROL_ID) }) }
    fun encode(): String = WireCodec.encode(WireEnvelope(messageType = WireMessageType.ACK, sessionId = sessionId,
        setId = setId, source = WireSource.PHONE, sentAtElapsedMs = 0, sentAtWallMs = 0,
        payload = mapOf("kind" to "WORKOUT_CONTROL_ACK_V1", "commandId" to commandId, "attemptId" to attemptId, "status" to status.name)))
    companion object {
        fun decode(value: String): WatchWorkoutControlAck {
            require(value.length <= 12_000)
            val e = WireCodec.decode(value)
            require(e.source == WireSource.PHONE && e.messageType == WireMessageType.ACK && e.payload["kind"] == "WORKOUT_CONTROL_ACK_V1")
            return WatchWorkoutControlAck(e.payload.getValue("commandId"), e.sessionId, requireNotNull(e.setId),
                e.payload.getValue("attemptId"), WatchWorkoutControlStatus.valueOf(e.payload.getValue("status")))
        }
    }
}
private val CONTROL_ID = Regex("[A-Za-z0-9._-]{1,160}")
private val CONTROL_HASH = Regex("[0-9a-f]{64}")
