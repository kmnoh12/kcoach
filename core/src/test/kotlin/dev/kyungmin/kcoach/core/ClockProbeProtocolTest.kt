package dev.kyungmin.kcoach.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClockProbeProtocolTest {
    @Test fun clockRoundTripKeepsIndependentEpochsAndRejectsAnotherRequestOrPhoneBoot() {
        val request = ClockProbeProtocol.request("capture", "probe", 2_000_000_000, "phone-boot", true)
        val reply = ClockProbeProtocol.reply(WireCodec.decode(WireCodec.encode(request)),
            1_010_000_000, 1_012_000_000, "watch-boot", true)
        val sample = ClockProbeProtocol.exchange(request, WireCodec.decode(WireCodec.encode(reply)), 2_022_000_000, "phone-boot")
        assertEquals(ClockExchange(2_000_000_000, 1_010_000_000, 1_012_000_000, 2_022_000_000), sample.exchange)
        assertTrue(sample.epochsVerified)
        assertEquals("watch-boot", sample.watchClockId)
        assertThrows(IllegalArgumentException::class.java) { ClockProbeProtocol.exchange(request, reply, 2_022_000_000, "phone-new-boot") }
        assertThrows(IllegalArgumentException::class.java) {
            ClockProbeProtocol.exchange(request.copy(messageId = "other-probe"), reply, 2_022_000_000, "phone-boot")
        }
    }
}
