package dev.kyungmin.kcoach.core

data class ClockProbeSample(val exchange: ClockExchange, val phoneClockId: String,
    val watchClockId: String, val epochsVerified: Boolean)

/** Four boot-relative nanosecond timestamps, bound to one request and capture. */
object ClockProbeProtocol {
    fun request(captureId: String, probeId: String, phoneSendNs: Long, phoneClockId: String,
                epochVerified: Boolean): WireEnvelope {
        require(captureId.isNotBlank() && probeId.isNotBlank() && phoneClockId.isNotBlank() && phoneSendNs >= 0)
        return WireEnvelope(messageId = probeId, messageType = WireMessageType.HEARTBEAT, sessionId = captureId,
            source = WireSource.PHONE, sentAtElapsedMs = phoneSendNs / 1_000_000, sentAtWallMs = 0,
            payload = mapOf("clockProbe" to "REQUEST", "phoneSendNs" to phoneSendNs.toString(),
                "phoneClockId" to phoneClockId, "phoneEpochVerified" to epochVerified.toString()))
    }

    fun reply(request: WireEnvelope, watchReceiveNs: Long, watchSendNs: Long, watchClockId: String,
              epochVerified: Boolean): WireEnvelope {
        require(request.source == WireSource.PHONE && request.messageType == WireMessageType.HEARTBEAT && request.payload["clockProbe"] == "REQUEST")
        require(request.sessionId.isNotBlank() && request.messageId.isNotBlank() && request.payload.getValue("phoneSendNs").toLong() >= 0)
        require(request.payload.getValue("phoneClockId").isNotBlank())
        require(watchReceiveNs >= 0 && watchSendNs >= watchReceiveNs && watchClockId.isNotBlank())
        return WireEnvelope(messageType = WireMessageType.HEARTBEAT, sessionId = request.sessionId,
            source = WireSource.WATCH, sentAtElapsedMs = watchSendNs / 1_000_000, sentAtWallMs = 0,
            payload = request.payload + mapOf("clockProbe" to "REPLY", "requestId" to request.messageId,
                "watchReceiveNs" to watchReceiveNs.toString(), "watchSendNs" to watchSendNs.toString(),
                "watchClockId" to watchClockId, "watchEpochVerified" to epochVerified.toString()))
    }

    fun exchange(request: WireEnvelope, reply: WireEnvelope, phoneReceiveNs: Long, currentPhoneClockId: String): ClockProbeSample {
        require(request.source == WireSource.PHONE && request.messageType == WireMessageType.HEARTBEAT && request.payload["clockProbe"] == "REQUEST")
        require(reply.source == WireSource.WATCH && reply.messageType == WireMessageType.HEARTBEAT && reply.payload["clockProbe"] == "REPLY")
        require(reply.sessionId == request.sessionId && reply.payload["requestId"] == request.messageId)
        require(currentPhoneClockId == request.payload["phoneClockId"] && reply.payload["phoneClockId"] == currentPhoneClockId)
        require(reply.payload["phoneSendNs"] == request.payload["phoneSendNs"] && reply.payload["phoneEpochVerified"] == request.payload["phoneEpochVerified"])
        val watchClockId = reply.payload.getValue("watchClockId").also { require(it.isNotBlank()) }
        return ClockProbeSample(ClockExchange(request.payload.getValue("phoneSendNs").toLong(),
            reply.payload.getValue("watchReceiveNs").toLong(), reply.payload.getValue("watchSendNs").toLong(), phoneReceiveNs),
            currentPhoneClockId, watchClockId, request.payload.getValue("phoneEpochVerified").toBooleanStrict() &&
                reply.payload.getValue("watchEpochVerified").toBooleanStrict())
    }
}
