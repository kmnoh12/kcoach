package dev.kyungmin.kcoach.core

import kotlin.test.*

class RepMismatchTest {
    private fun s(a: Int, b: Int) = RepSpan(a, b)

    @Test fun matchingRepsProduceNoCandidates() {
        val video = listOf(s(0, 2000), s(2500, 4500), s(5000, 7000))
        val watch = listOf(s(100, 2100), s(2600, 4400), s(5100, 7200))
        assertTrue(RepMismatchFinder.find(video, watch).isEmpty())
    }

    @Test fun aRepOnlyTheVideoSawIsReportedAsVideoOnly() {
        val video = listOf(s(0, 2000), s(2500, 4500), s(5000, 7000))
        val watch = listOf(s(0, 2000), s(5000, 7000))
        assertEquals(listOf(MismatchCandidate(s(2500, 4500), MismatchSide.VIDEO_ONLY)), RepMismatchFinder.find(video, watch))
    }

    @Test fun aRepOnlyTheWatchCountedIsReportedAsWatchOnlyAndOrderedByTime() {
        val video = listOf(s(0, 2000), s(6000, 8000))
        val watch = listOf(s(0, 2000), s(3000, 5000), s(6000, 8000), s(9000, 11000))
        assertEquals(listOf(
            MismatchCandidate(s(3000, 5000), MismatchSide.WATCH_ONLY),
            MismatchCandidate(s(9000, 11000), MismatchSide.WATCH_ONLY),
        ), RepMismatchFinder.find(video, watch))
    }

    @Test fun oneWatchWindowPairsWithTheBestVideoWindowOnly() {
        val video = listOf(s(0, 2000), s(1800, 3800))
        val watch = listOf(s(1700, 3700))
        val result = RepMismatchFinder.find(video, watch)
        assertEquals(listOf(MismatchCandidate(s(0, 2000), MismatchSide.VIDEO_ONLY)), result)
    }

    @Test fun barelyTouchingWindowsAreNotTheSameRep() {
        val result = RepMismatchFinder.find(listOf(s(0, 2000)), listOf(s(1900, 3900)))
        assertEquals(2, result.size)
    }

    @Test fun clockMapInterpolatesAndRefusesFarOutsideTheFrames() {
        val map = VideoClockMap(listOf(1_000_000_000L to 0L, 2_000_000_000L to 1_000_000L))
        assertEquals(0, map.videoMs(1_000_000_000L))
        assertEquals(500, map.videoMs(1_500_000_000L))
        assertEquals(1000, map.videoMs(2_000_000_000L))
        assertEquals(0, map.videoMs(900_000_000L)) // inside the tolerance clamps to the first frame
        assertNull(map.videoMs(500_000_000L))
        assertNull(map.videoMs(3_000_000_000L))
        assertNull(VideoClockMap(emptyList()).videoMs(1L))
    }
}
