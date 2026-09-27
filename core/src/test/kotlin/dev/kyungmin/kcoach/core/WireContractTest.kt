package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WireContractTest {
    @Test
    fun `v3 envelope round trips Korean text separators and nullable fields`() {
        val original = WireMessages.sessionStart(
            sessionId = "session-한글|1",
            routineId = "routine-1",
            exerciseSessionId = "exercise-session-1",
            exerciseId = "lat-pulldown",
            exerciseVariantId = "wide|grip",
            exerciseName = "랫풀다운 | 광배",
            setIndex = 2,
            targetReps = 10,
            weightKg = null,
            restSeconds = 90,
        )

        val decoded = WireCodec.decode(WireCodec.encode(original))

        assertEquals(original.protocolVersion, decoded.protocolVersion)
        assertEquals(original.messageId, decoded.messageId)
        assertEquals(original.messageType, decoded.messageType)
        assertEquals(original.sessionId, decoded.sessionId)
        assertEquals(original.exerciseSessionId, decoded.exerciseSessionId)
        assertEquals(original.setId, decoded.setId)
        assertEquals(original.source, decoded.source)
        assertEquals(original.payload, decoded.payload)
        assertNull(decoded.payloadDouble(WirePayloadKeys.WEIGHT_KG))
        assertEquals(2, decoded.payloadInt(WirePayloadKeys.SET_INDEX))
    }

    @Test
    fun `live state preserves typed payload values`() {
        val original = WireMessages.liveState(
            sessionId = "session-1",
            exerciseSessionId = "exercise-session-1",
            setId = "set-1",
            sequence = 17,
            phase = WirePhase.WORKING,
            watchReps = 6,
            targetReps = 10,
            heartRate = 128,
            recording = true,
            sampleCount = 1_420,
        )

        val decoded = WireCodec.decode(WireCodec.encode(original))

        assertEquals(WireMessageType.LIVE_STATE, decoded.messageType)
        assertEquals(17L, decoded.sequence)
        assertEquals(WirePhase.WORKING, decoded.payloadPhase())
        assertEquals(6, decoded.payloadInt(WirePayloadKeys.WATCH_REPS))
        assertEquals(128, decoded.payloadInt(WirePayloadKeys.HEART_RATE))
        assertEquals(true, decoded.payloadBoolean(WirePayloadKeys.RECORDING))
        assertEquals(1_420, decoded.payloadInt(WirePayloadKeys.SAMPLE_COUNT))
    }

    @Test
    fun `malformed or unsupported envelope is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decode("not-a-kcoach-envelope")
        }
        assertFailsWith<IllegalArgumentException> {
            WireCodec.decode("KCP3.2.HEARTBEAT.a.b..c.0.PHONE.1.2.")
        }
    }

    @Test
    fun `capture manifest carries exercise identity and set index`() {
        val manifest = WireMessages.captureManifest(
            sessionId = "session-1",
            exerciseSessionId = "exercise-session-1",
            setId = "session-1-set-2",
            captureId = "capture-1",
            formatVersion = 1,
            compression = "NONE",
            sampleCount = 10,
            sampleRateRequestedHz = 50.0,
            sampleRateObservedHz = 49.5,
            startMonotonicNs = 1_000L,
            endMonotonicNs = 2_000L,
            sha256 = "abc",
            assetSizeBytes = 420L,
            watchDetectedReps = 8,
            exerciseId = "lat_pull_down",
            exerciseName = "랫풀다운",
            setIndex = 2,
        )

        val decoded = WireCodec.decode(WireCodec.encode(manifest))

        assertEquals("lat_pull_down", decoded.payloadString(WirePayloadKeys.EXERCISE_ID))
        assertEquals("랫풀다운", decoded.payloadString(WirePayloadKeys.EXERCISE_NAME))
        assertEquals(2, decoded.payloadInt(WirePayloadKeys.SET_INDEX))
    }

    @Test
    fun `ack carries message and capture identity`() {
        val original = WireMessages.ack(
            source = WireSource.PHONE,
            sessionId = "session-1",
            acknowledgedMessageId = "message-1",
            captureId = "capture-1",
        )

        val decoded = WireCodec.decode(WireCodec.encode(original))

        assertEquals(WireMessageType.ACK, decoded.messageType)
        assertEquals("message-1", decoded.payloadString(WirePayloadKeys.ACK_MESSAGE_ID))
        assertEquals("capture-1", decoded.payloadString(WirePayloadKeys.ACK_CAPTURE_ID))
    }
}
