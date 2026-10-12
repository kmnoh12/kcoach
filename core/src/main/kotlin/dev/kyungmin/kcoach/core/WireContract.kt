package dev.kyungmin.kcoach.core

import java.util.Base64
import java.util.UUID

/** Versioned, transport-agnostic contract shared by the phone and Wear OS app. */
const val CURRENT_WIRE_PROTOCOL_VERSION = 3

enum class WireMessageType {
    HELLO,
    HEARTBEAT,
    SESSION_START,
    SET_START,
    PAUSE,
    RESUME,
    SET_END,
    REST_START,
    REST_ADJUST,
    SESSION_STOP,
    SESSION_COMPLETE,
    LIVE_STATE,
    REP_EVENT,
    CAPTURE_MANIFEST,
    CAPTURE_ASSET,
    ACK,
    ERROR,
}

enum class WireSource {
    PHONE,
    WATCH,
}

enum class WirePhase {
    IDLE,
    READY,
    WORKING,
    SET_COMPLETE,
    RESTING,
    REST_COMPLETE,
    PAUSED,
    UPLOADING,
    COMPLETE,
    STOPPED,
    INTERRUPTED,
}

data class WireEnvelope(
    val protocolVersion: Int = CURRENT_WIRE_PROTOCOL_VERSION,
    val messageId: String = UUID.randomUUID().toString(),
    val messageType: WireMessageType,
    val sessionId: String,
    val exerciseSessionId: String? = null,
    val setId: String? = null,
    val sequence: Long = 0L,
    val source: WireSource,
    val sentAtElapsedMs: Long,
    val sentAtWallMs: Long,
    val payload: Map<String, String> = emptyMap(),
)

/**
 * A small dependency-free codec for Data Layer messages.
 *
 * The envelope uses a fixed field order and URL-safe base64 for all free-form
 * strings. This keeps Korean text, separators, and exercise names containing
 * punctuation safe without adding a JSON dependency to the shared JVM module.
 */
object WireCodec {
    private const val MAGIC = "KCP3"
    private const val FIELD_SEPARATOR = "."
    private const val PAYLOAD_SEPARATOR = ","
    private const val PAYLOAD_ASSIGNMENT = "="

    fun encode(envelope: WireEnvelope): String {
        require(envelope.protocolVersion == CURRENT_WIRE_PROTOCOL_VERSION) {
            "Unsupported wire protocol version: ${envelope.protocolVersion}"
        }
        val fields = listOf(
            MAGIC,
            envelope.protocolVersion.toString(),
            envelope.messageType.name,
            encodeText(envelope.messageId),
            encodeText(envelope.sessionId),
            encodeText(envelope.exerciseSessionId.orEmpty()),
            encodeText(envelope.setId.orEmpty()),
            envelope.sequence.toString(),
            envelope.source.name,
            envelope.sentAtElapsedMs.toString(),
            envelope.sentAtWallMs.toString(),
            encodePayload(envelope.payload),
        )
        return fields.joinToString(FIELD_SEPARATOR)
    }

    fun decode(encoded: String): WireEnvelope {
        val fields = encoded.split(FIELD_SEPARATOR)
        require(fields.size == 12 && fields[0] == MAGIC) { "Invalid KCoach wire envelope" }
        val version = fields[1].toIntOrNull() ?: error("Invalid wire protocol version")
        require(version == CURRENT_WIRE_PROTOCOL_VERSION) { "Unsupported wire protocol version: $version" }
        val messageType = enumValueOrNull<WireMessageType>(fields[2]) ?: error("Unknown message type: ${fields[2]}")
        val source = enumValueOrNull<WireSource>(fields[8]) ?: error("Unknown source: ${fields[8]}")
        return WireEnvelope(
            protocolVersion = version,
            messageId = decodeText(fields[3]),
            messageType = messageType,
            sessionId = decodeText(fields[4]),
            exerciseSessionId = decodeText(fields[5]).ifBlank { null },
            setId = decodeText(fields[6]).ifBlank { null },
            sequence = fields[7].toLongOrNull() ?: error("Invalid sequence"),
            source = source,
            sentAtElapsedMs = fields[9].toLongOrNull() ?: error("Invalid elapsed timestamp"),
            sentAtWallMs = fields[10].toLongOrNull() ?: error("Invalid wall timestamp"),
            payload = decodePayload(fields[11]),
        )
    }

    private fun encodePayload(payload: Map<String, String>): String = payload
        .toSortedMap()
        .entries
        .joinToString(PAYLOAD_SEPARATOR) { entry ->
            require(entry.key.isNotBlank() && !entry.key.contains(PAYLOAD_SEPARATOR)) { "Invalid payload key" }
            "${entry.key}$PAYLOAD_ASSIGNMENT${encodeText(entry.value)}"
        }

    private fun decodePayload(encoded: String): Map<String, String> {
        if (encoded.isBlank()) return emptyMap()
        return encoded.split(PAYLOAD_SEPARATOR).associate { item ->
            val assignmentIndex = item.indexOf(PAYLOAD_ASSIGNMENT)
            require(assignmentIndex > 0) { "Invalid payload item" }
            val key = item.substring(0, assignmentIndex)
            val value = decodeText(item.substring(assignmentIndex + 1))
            key to value
        }
    }

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String =
        if (value.isBlank()) "" else String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }
}

object WirePayloadKeys {
    const val ROUTINE_ID = "routineId"
    const val EXERCISE_ID = "exerciseId"
    const val EXERCISE_SESSION_ID = "exerciseSessionId"
    const val EXERCISE_VARIANT_ID = "exerciseVariantId"
    const val EXERCISE_NAME = "exerciseName"
    const val SET_INDEX = "setIndex"
    const val ATTEMPT_ID = "attemptId"
    const val TARGET_REPS = "targetReps"
    const val WEIGHT_KG = "weightKg"
    const val REST_SECONDS = "restSeconds"
    const val REST_END_AT_MS = "restEndAtMs"
    const val PHASE = "phase"
    const val WATCH_REPS = "watchReps"
    const val HEART_RATE = "heartRate"
    const val RECORDING = "recording"
    const val SAMPLE_COUNT = "sampleCount"
    const val CONFIDENCE = "confidence"
    const val CAPTURE_ID = "captureId"
    const val FORMAT_VERSION = "formatVersion"
    const val COMPRESSION = "compression"
    const val SAMPLE_RATE_REQUESTED = "sampleRateRequested"
    const val SAMPLE_RATE_OBSERVED = "sampleRateObserved"
    const val START_MONOTONIC_NS = "startMonotonicNs"
    const val END_MONOTONIC_NS = "endMonotonicNs"
    const val SHA256 = "sha256"
    const val ASSET_SIZE_BYTES = "assetSizeBytes"
    const val ACK_MESSAGE_ID = "ackMessageId"
    const val ACK_CAPTURE_ID = "ackCaptureId"
    const val ERROR_CODE = "errorCode"
    const val ERROR_MESSAGE = "errorMessage"
}

object WireMessages {
    fun hello(source: WireSource, sessionId: String = "transport"): WireEnvelope =
        base(WireMessageType.HELLO, source, sessionId)

    fun heartbeat(source: WireSource, sessionId: String): WireEnvelope =
        base(WireMessageType.HEARTBEAT, source, sessionId)

    fun sessionStart(
        sessionId: String,
        routineId: String,
        exerciseSessionId: String,
        exerciseId: String,
        exerciseVariantId: String?,
        exerciseName: String,
        setIndex: Int,
        targetReps: Int,
        weightKg: Double?,
        restSeconds: Int,
    ): WireEnvelope = base(
        type = WireMessageType.SESSION_START,
        source = WireSource.PHONE,
        sessionId = sessionId,
        exerciseSessionId = exerciseSessionId,
        setId = "${sessionId}-set-$setIndex",
        payload = mapOf(
            WirePayloadKeys.ROUTINE_ID to routineId,
            WirePayloadKeys.EXERCISE_ID to exerciseId,
            WirePayloadKeys.EXERCISE_SESSION_ID to exerciseSessionId,
            WirePayloadKeys.EXERCISE_VARIANT_ID to exerciseVariantId.orEmpty(),
            WirePayloadKeys.EXERCISE_NAME to exerciseName,
            WirePayloadKeys.SET_INDEX to setIndex.toString(),
            WirePayloadKeys.TARGET_REPS to targetReps.toString(),
            WirePayloadKeys.WEIGHT_KG to weightKg?.toString().orEmpty(),
            WirePayloadKeys.REST_SECONDS to restSeconds.toString(),
        ),
    )

    fun liveState(
        sessionId: String,
        exerciseSessionId: String,
        setId: String,
        sequence: Long,
        phase: WirePhase,
        watchReps: Int,
        targetReps: Int,
        heartRate: Int?,
        recording: Boolean,
        sampleCount: Int,
    ): WireEnvelope = base(
        type = WireMessageType.LIVE_STATE,
        source = WireSource.WATCH,
        sessionId = sessionId,
        exerciseSessionId = exerciseSessionId,
        setId = setId,
        sequence = sequence,
        payload = mapOf(
            WirePayloadKeys.PHASE to phase.name,
            WirePayloadKeys.WATCH_REPS to watchReps.toString(),
            WirePayloadKeys.TARGET_REPS to targetReps.toString(),
            WirePayloadKeys.HEART_RATE to heartRate?.toString().orEmpty(),
            WirePayloadKeys.RECORDING to recording.toString(),
            WirePayloadKeys.SAMPLE_COUNT to sampleCount.toString(),
        ),
    )

    fun ack(
        source: WireSource,
        sessionId: String,
        acknowledgedMessageId: String,
        captureId: String? = null,
    ): WireEnvelope = base(
        type = WireMessageType.ACK,
        source = source,
        sessionId = sessionId,
        payload = buildMap {
            put(WirePayloadKeys.ACK_MESSAGE_ID, acknowledgedMessageId)
            captureId?.let { put(WirePayloadKeys.ACK_CAPTURE_ID, it) }
        },
    )

    fun captureManifest(
        sessionId: String,
        exerciseSessionId: String,
        setId: String,
        captureId: String,
        formatVersion: Int,
        compression: String,
        sampleCount: Int,
        sampleRateRequestedHz: Double,
        sampleRateObservedHz: Double,
        startMonotonicNs: Long,
        endMonotonicNs: Long,
        sha256: String,
        assetSizeBytes: Long,
        watchDetectedReps: Int? = null,
        exerciseId: String = exerciseSessionId,
        exerciseName: String = exerciseId,
        setIndex: Int = 0,
        attemptId: String = "",
    ): WireEnvelope = base(
        type = WireMessageType.CAPTURE_MANIFEST,
        source = WireSource.WATCH,
        sessionId = sessionId,
        exerciseSessionId = exerciseSessionId,
        setId = setId,
        payload = mapOf(
            WirePayloadKeys.CAPTURE_ID to captureId,
            WirePayloadKeys.ATTEMPT_ID to attemptId,
            WirePayloadKeys.EXERCISE_ID to exerciseId,
            WirePayloadKeys.EXERCISE_NAME to exerciseName,
            WirePayloadKeys.SET_INDEX to setIndex.toString(),
            WirePayloadKeys.FORMAT_VERSION to formatVersion.toString(),
            WirePayloadKeys.COMPRESSION to compression,
            WirePayloadKeys.SAMPLE_COUNT to sampleCount.toString(),
            WirePayloadKeys.SAMPLE_RATE_REQUESTED to sampleRateRequestedHz.toString(),
            WirePayloadKeys.SAMPLE_RATE_OBSERVED to sampleRateObservedHz.toString(),
            WirePayloadKeys.START_MONOTONIC_NS to startMonotonicNs.toString(),
            WirePayloadKeys.END_MONOTONIC_NS to endMonotonicNs.toString(),
            WirePayloadKeys.SHA256 to sha256,
            WirePayloadKeys.ASSET_SIZE_BYTES to assetSizeBytes.toString(),
            WirePayloadKeys.WATCH_REPS to watchDetectedReps?.toString().orEmpty(),
        ),
    )

    private fun base(
        type: WireMessageType,
        source: WireSource,
        sessionId: String,
        exerciseSessionId: String? = null,
        setId: String? = null,
        sequence: Long = 0L,
        payload: Map<String, String> = emptyMap(),
    ): WireEnvelope = WireEnvelope(
        messageType = type,
        sessionId = sessionId,
        exerciseSessionId = exerciseSessionId,
        setId = setId,
        sequence = sequence,
        source = source,
        sentAtElapsedMs = System.nanoTime() / 1_000_000L,
        sentAtWallMs = System.currentTimeMillis(),
        payload = payload,
    )
}

fun WireEnvelope.payloadString(key: String): String? = payload[key]?.ifBlank { null }
fun WireEnvelope.payloadInt(key: String): Int? = payloadString(key)?.toIntOrNull()
fun WireEnvelope.payloadLong(key: String): Long? = payloadString(key)?.toLongOrNull()
fun WireEnvelope.payloadDouble(key: String): Double? = payloadString(key)?.toDoubleOrNull()
fun WireEnvelope.payloadBoolean(key: String): Boolean? = payloadString(key)?.toBooleanStrictOrNull()
fun WireEnvelope.payloadPhase(key: String = WirePayloadKeys.PHASE): WirePhase? =
    payloadString(key)?.let { value -> enumValues<WirePhase>().firstOrNull { it.name == value } }
