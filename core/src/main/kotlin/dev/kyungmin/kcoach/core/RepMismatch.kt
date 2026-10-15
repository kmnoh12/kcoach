package dev.kyungmin.kcoach.core

import kotlin.math.max
import kotlin.math.min

/** A repetition window on the reference video's presentation clock. */
data class RepSpan(val startMs: Int, val endMs: Int) {
    init { require(endMs > startMs) }
    val lengthMs: Int get() = endMs - startMs
}

/** Which source saw a repetition the other did not. A candidate is a question for the user, never a label. */
enum class MismatchSide { VIDEO_ONLY, WATCH_ONLY }

data class MismatchCandidate(val span: RepSpan, val side: MismatchSide)

object RepMismatchFinder {
    /** Two windows describe the same repetition when they overlap by at least this share of the shorter one. */
    const val MIN_OVERLAP_SHARE = 0.4

    /** Pairs video and watch windows one to one by overlap; whatever stays unpaired is returned in time order. */
    fun find(video: List<RepSpan>, watch: List<RepSpan>): List<MismatchCandidate> {
        val pairs = video.indices.flatMap { v -> watch.indices.map { w -> Triple(v, w, overlap(video[v], watch[w])) } }
            .filter { (v, w, shared) -> shared >= MIN_OVERLAP_SHARE * min(video[v].lengthMs, watch[w].lengthMs) && shared > 0 }
            .sortedByDescending { it.third }
        val usedVideo = mutableSetOf<Int>(); val usedWatch = mutableSetOf<Int>()
        pairs.forEach { (v, w, _) -> if (v !in usedVideo && w !in usedWatch) { usedVideo.add(v); usedWatch.add(w) } }
        return (video.indices.filter { it !in usedVideo }.map { MismatchCandidate(video[it], MismatchSide.VIDEO_ONLY) } +
            watch.indices.filter { it !in usedWatch }.map { MismatchCandidate(watch[it], MismatchSide.WATCH_ONLY) })
            .sortedBy { it.span.startMs }
    }

    private fun overlap(a: RepSpan, b: RepSpan): Int = max(0, min(a.endMs, b.endMs) - max(a.startMs, b.startMs))
}

/**
 * Maps phone-clock camera timestamps onto the video clock by interpolating between written frames.
 * Times outside the recorded frames by more than [toleranceNs] map to null instead of a guess.
 */
class VideoClockMap(frames: List<Pair<Long, Long>>, private val toleranceNs: Long = 150_000_000L) {
    private val points = frames.sortedBy { it.first }

    fun videoMs(cameraNs: Long): Int? {
        if (points.isEmpty()) return null
        if (cameraNs < points.first().first - toleranceNs || cameraNs > points.last().first + toleranceNs) return null
        val after = points.indexOfFirst { it.first >= cameraNs }
        val micros: Double = when {
            after < 0 -> points.last().second.toDouble()
            after == 0 || points[after].first == cameraNs -> points[after].second.toDouble()
            else -> {
                val (n0, u0) = points[after - 1]; val (n1, u1) = points[after]
                u0 + (u1 - u0) * ((cameraNs - n0).toDouble() / (n1 - n0))
            }
        }
        return (micros / 1000.0).toInt()
    }
}
