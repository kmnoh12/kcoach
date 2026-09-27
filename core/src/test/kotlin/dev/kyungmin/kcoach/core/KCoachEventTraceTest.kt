package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KCoachEventTraceTest {
    @Test
    fun `trace contains every correlation field`() {
        val trace = KCoachEventTrace.format(
            marker = KCoachEventMarker.CAPTURE_VALIDATED,
            sessionId = "session-1",
            exerciseId = "external_rotation",
            setIndex = 2,
            captureId = "capture-1",
            threadName = "analysis worker",
            timestampMs = 1234L,
        )

        assertEquals(
            "event=CAPTURE_VALIDATED sessionId=session-1 exerciseId=external_rotation " +
                "setIndex=2 captureId=capture-1 thread=analysis_worker timestamp=1234",
            trace,
        )
    }

    @Test
    fun `trace normalizes untrusted detail onto one log line`() {
        val trace = KCoachEventTrace.format(
            marker = KCoachEventMarker.EVENT_FAILED,
            detail = "bad\nfield=value",
            timestampMs = 9L,
        )

        assertTrue(trace.endsWith("timestamp=9 detail=bad_field:value"))
    }
}
