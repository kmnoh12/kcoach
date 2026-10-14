package dev.kyungmin.kcoach.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CaptureClockMappingTest {
    @Test fun startAndEndExchangesBoundDriftAndRequireBothOriginalBootEpochs() {
        val start = ClockAlignment(1_000_000_000, 2_000_000_000, 10_000_000, "phone", "watch")
        val end = ClockAlignment(11_000_000_000, 12_001_000_000, 10_000_000, "phone", "watch")
        val mapping = CaptureClockMapping(start, end)
        assertEquals(7_000_500_000L, mapping.phoneTimeNs(6_000_000_000, "phone", "watch"))
        assertEquals(10_000_000L, mapping.uncertaintyNs)
        assertThrows(IllegalArgumentException::class.java) { mapping.phoneTimeNs(6_000_000_000, "phone-new-boot", "watch") }
        assertThrows(IllegalArgumentException::class.java) { CaptureClockMapping(start, end.copy(watchClockId = "watch-new-boot")) }
        assertThrows(IllegalArgumentException::class.java) { CaptureClockMapping(start, end.copy(phoneOriginNs = 14_000_000_000)) }
    }
}
