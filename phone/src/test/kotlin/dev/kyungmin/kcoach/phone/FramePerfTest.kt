package dev.kyungmin.kcoach.phone

import org.junit.Assert.*
import org.junit.Test

class FramePerfTest {
    private val ms = 1_000_000L

    @Test fun reportsFrameRateGapsAndEachStageOncePerSecond() {
        val perf = FramePerf()
        var line: String? = null
        // 10 fps for just over a second: 100 ms apart, one 250 ms hiccup, store stage dominating.
        var camera = 0L
        for (i in 0..11) {
            camera += if (i == 5) 250 * ms else 100 * ms
            line = perf.add(camera, camera, convert = 5 * ms, detect = 30 * ms, encode = 10 * ms, store = 40 * ms, total = 90 * ms) ?: line
        }
        val text = requireNotNull(line)
        assertTrue(text, text.contains("모델 30") && text.contains("저장 40") && text.contains("최대 250ms"))
        assertEquals(text, perf.latest)
    }

    @Test fun staysSilentInsideTheFirstSecond() {
        val perf = FramePerf()
        assertNull(perf.add(0, 0, 1, 1, 1, 1, 4))
        assertNull(perf.add(500 * ms, 500 * ms, 1, 1, 1, 1, 4))
    }
}
