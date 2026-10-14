package dev.kyungmin.kcoach.core

import kotlin.math.sqrt

internal data class PatternWindowMatch(val cycle: RepCycle, val distance: Double)

/** Shared matching rules for offline windows and causal, already-delivered windows. */
internal object PatternWindowMatcher {
    fun match(records: List<SensorCaptureRecord>, start: Long, end: Long, phase: MusclePhase,
        profile: PatternRuntimeProfile): PatternWindowMatch? {
        val frames = PatternSignal.frames(records, start, null, end)
            .map { frame -> frame.mapIndexed { axis, value -> value / profile.scales[axis] } }
        val endpoint = sqrt(profile.activeAxes.sumOf { axis ->
            val d = frames.last()[axis] - profile.template.last()[axis]; d * d
        } / profile.activeAxes.size)
        val amplitude = sqrt(frames.sumOf { frame -> profile.activeAxes.sumOf { frame[it] * frame[it] } } /
            (frames.size * profile.activeAxes.size))
        if (endpoint > 0.45 || amplitude !in 0.45..1.9) return null
        val match = BoundedPatternDtw.compare(profile.template, frames, profile.activeAxes)
        if (match.distance > profile.maximumDistance || match.turnIndex !in 4..28) return null
        val turn = observedTime(records, start + (end - start) * match.turnIndex / 32)
        val from = observedTime(records, start)
        val until = observedTime(records, end)
        return if (from < turn && turn < until) PatternWindowMatch(RepCycle(from, turn, until, phase), match.distance) else null
    }

    private fun observedTime(records: List<SensorCaptureRecord>, target: Long): Long {
        val exact = records.binarySearch { it.sample.timestampNs.compareTo(target) }
        if (exact >= 0) return records[exact].sample.timestampNs
        val next = (-exact - 1).coerceAtMost(records.lastIndex)
        val prev = (next - 1).coerceAtLeast(0)
        return if (records[next].sample.timestampNs - target < target - records[prev].sample.timestampNs)
            records[next].sample.timestampNs else records[prev].sample.timestampNs
    }
}
