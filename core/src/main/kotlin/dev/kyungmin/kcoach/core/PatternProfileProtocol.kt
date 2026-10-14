package dev.kyungmin.kcoach.core

import java.util.Base64

enum class PatternProfileOperation { SHADOW, ACTIVATE, ROLLBACK, DISABLE }
enum class PatternProfileStatus { STAGED, APPLIED, DISABLED, CONFLICT, WRONG_DEVICE, REJECTED, SUPERSEDED }

/** Immutable reports remain on the phone. The controller must verify both before issuing activation. */
data class PatternValidationEvidence(val phoneReportHash: String, val watchReportHash: String,
    val previousActiveHash: String? = null) {
    init { require(listOfNotNull(phoneReportHash, watchReportHash, previousActiveHash).all { it.matches(HASH) }) }
    companion object { private val HASH = Regex("[0-9a-f]{64}") }
}

data class PatternProfileCommand(val commandId: String, val operation: PatternProfileOperation,
    val runtime: PatternRuntimeProfile, val expectedRevision: Long, val evidence: PatternValidationEvidence? = null) {
    init {
        require(commandId.matches(Regex("[A-Za-z0-9._-]{1,100}")) && expectedRevision >= 0)
        require(operation == PatternProfileOperation.SHADOW || evidence != null) { "Activation needs phone and actual-watch validation receipts" }
    }
    val runtimeHash: String get() = SensorCaptureFormat.sha256(PatternRuntimeCodec.encode(runtime))
    fun encode(): String = WireCodec.encode(WireEnvelope(messageId = commandId, messageType = WireMessageType.HEARTBEAT,
        sessionId = "pattern-profiles", source = WireSource.PHONE, sentAtElapsedMs = 0, sentAtWallMs = 0,
        payload = mapOf("kind" to "PATTERN_PROFILE_V1", "operation" to operation.name,
            "runtime" to Base64.getEncoder().encodeToString(PatternRuntimeCodec.encode(runtime)), "runtimeHash" to runtimeHash,
            "expectedRevision" to expectedRevision.toString(), "phoneReportHash" to evidence?.phoneReportHash.orEmpty(),
            "watchReportHash" to evidence?.watchReportHash.orEmpty(), "previousActiveHash" to evidence?.previousActiveHash.orEmpty())))

    companion object {
        fun decode(encoded: String): PatternProfileCommand {
            require(encoded.length <= 12_000)
            val envelope = WireCodec.decode(encoded)
            require(envelope.source == WireSource.PHONE && envelope.messageType == WireMessageType.HEARTBEAT &&
                envelope.sessionId == "pattern-profiles" && envelope.payload["kind"] == "PATTERN_PROFILE_V1")
            fun field(key: String) = envelope.payload.getValue(key)
            val bytes = Base64.getDecoder().decode(field("runtime"))
            require(SensorCaptureFormat.sha256(bytes) == field("runtimeHash"))
            val phone = field("phoneReportHash")
            val evidence = if (phone.isEmpty()) null else PatternValidationEvidence(phone, field("watchReportHash"), field("previousActiveHash").ifEmpty { null })
            return PatternProfileCommand(envelope.messageId, PatternProfileOperation.valueOf(field("operation")),
                PatternRuntimeCodec.decode(bytes), field("expectedRevision").toLong(), evidence)
        }
    }
}

data class PatternProfileApplication(val sessionId: String, val setId: String, val attemptId: String,
    val watchClockId: String, val sampleNs: Long, val processedAtNs: Long) {
    init {
        require(listOf(sessionId, setId, attemptId, watchClockId).all { it.isNotBlank() })
        require(sampleNs > 0 && processedAtNs >= sampleNs)
    }
}

data class PatternProfileAck(val commandId: String, val operation: PatternProfileOperation, val profileId: String,
    val profileVersion: Long, val candidateHash: String, val runtimeHash: String, val slotRevision: Long,
    val deviceId: String, val status: PatternProfileStatus, val duplicate: Boolean = false,
    val application: PatternProfileApplication? = null) {
    init {
        require(commandId.isNotBlank() && profileId.isNotBlank() && deviceId.isNotBlank() && profileVersion > 0 && slotRevision >= 0)
        require(candidateHash.matches(Regex("[0-9a-f]{64}")) && runtimeHash.matches(Regex("[0-9a-f]{64}")))
        require(status !in setOf(PatternProfileStatus.APPLIED, PatternProfileStatus.DISABLED) || application != null)
        require(status != PatternProfileStatus.DISABLED || operation == PatternProfileOperation.DISABLE)
        require(status != PatternProfileStatus.APPLIED || operation != PatternProfileOperation.DISABLE)
    }
    fun encode(): String = WireCodec.encode(WireEnvelope(messageType = WireMessageType.ACK, sessionId = "pattern-profiles",
        source = WireSource.WATCH, sentAtElapsedMs = 0, sentAtWallMs = 0,
        payload = mapOf("kind" to "PATTERN_PROFILE_ACK_V1", "commandId" to commandId, "operation" to operation.name,
            "profileId" to profileId, "profileVersion" to profileVersion.toString(), "candidateHash" to candidateHash,
            "runtimeHash" to runtimeHash, "slotRevision" to slotRevision.toString(), "deviceId" to deviceId,
            "status" to status.name, "duplicate" to duplicate.toString(),
            "sessionId" to application?.sessionId.orEmpty(), "setId" to application?.setId.orEmpty(),
            "attemptId" to application?.attemptId.orEmpty(), "watchClockId" to application?.watchClockId.orEmpty(),
            "sampleNs" to application?.sampleNs?.toString().orEmpty(), "processedAtNs" to application?.processedAtNs?.toString().orEmpty())))
    companion object {
        fun decode(encoded: String): PatternProfileAck {
            require(encoded.length <= 12_000)
            val envelope = WireCodec.decode(encoded)
            require(envelope.source == WireSource.WATCH && envelope.messageType == WireMessageType.ACK &&
                envelope.sessionId == "pattern-profiles" && envelope.payload["kind"] == "PATTERN_PROFILE_ACK_V1")
            fun field(key: String) = envelope.payload.getValue(key)
            val application = field("sampleNs").takeIf { it.isNotEmpty() }?.let {
                PatternProfileApplication(field("sessionId"), field("setId"), field("attemptId"), field("watchClockId"), it.toLong(), field("processedAtNs").toLong())
            }
            return PatternProfileAck(field("commandId"), PatternProfileOperation.valueOf(field("operation")), field("profileId"),
                field("profileVersion").toLong(), field("candidateHash"), field("runtimeHash"), field("slotRevision").toLong(),
                field("deviceId"), PatternProfileStatus.valueOf(field("status")), field("duplicate").toBooleanStrict(), application)
        }
    }
}
