package dev.kyungmin.kcoach.core


/** Offline shadow result. A distance is signal similarity, never posture accuracy or calibrated probability. */
data class ReviewedPatternAnalysis(val estimatedCount: Int?, val cycles: List<RepCycle>, val distances: List<Double>,
    val rejectionReason: String?, val profileId: String, val profileVersion: Long, val engineId: String)

/**
 * Complete-window six-axis DTW candidate, inspired by ExerSense's template comparison.
 * Human interval training, unit-separated normalization, bounded paths and complete-return
 * checks are app adaptations. This offline search is not the watch's causal detector.
 */
object ReviewedPatternAnalyzer {
    private const val GRID_NS = 100_000_000L
    private data class Window(val cycle: RepCycle, val distance: Double)

    fun analyze(document: SensorCaptureDocument, exerciseId: String, compatibility: PatternCompatibility,
                profile: ReviewedPatternProfile): ReviewedPatternAnalysis {
        fun result(cycles: List<Window>, reason: String?, unavailable: Boolean = false) = ReviewedPatternAnalysis(
            if (unavailable) null else cycles.size, cycles.map { it.cycle }, cycles.map { it.distance }, reason,
            profile.id, profile.version, profile.engineId)
        if (ExerciseTrackingProfiles.resolve(exerciseId).mode == TrackingMode.MANUAL_REQUIRED)
            return result(emptyList(), "manual_required", true)
        val contract = ExerciseMeasurementContracts.resolve(exerciseId)
        if (compatibility != profile.compatibility || contract.recipe != compatibility.recipe)
            return result(emptyList(), "incompatible_profile", true)
        if (document.records.size < 10 || document.records.size > 30_000 || document.validateTimestamps().isNotEmpty())
            return result(emptyList(), "insufficient_or_invalid_samples", true)
        val runtime = PatternRuntimeProfile.from(profile)
        val records = document.records
        val first = records.first().sample.timestampNs
        val last = records.last().sample.timestampNs
        if (last - first > 300_000_000_000L) return result(emptyList(), "capture_too_long", true)
        val gaps = records.zipWithNext().filter { (a, b) -> b.sample.timestampNs - a.sample.timestampNs > 60_000_000 }
        val windows = mutableListOf<Window>()
        val minDuration = ((profile.minimumDurationNs + GRID_NS - 1) / GRID_NS) * GRID_NS
        val maxDuration = (profile.maximumDurationNs / GRID_NS) * GRID_NS
        var start = first
        while (start + minDuration <= last) {
            var duration = minDuration
            while (duration <= maxDuration && start + duration <= last) {
                val end = start + duration
                if (gaps.none { (a, b) -> b.sample.timestampNs > start && a.sample.timestampNs < end }) {
                    PatternWindowMatcher.match(records, start, end, contract.departurePhase, runtime)?.let {
                        windows += Window(it.cycle, it.distance)
                    }
                }
                duration += GRID_NS
            }
            start += GRID_NS
        }
        // Maximum-weight non-overlapping intervals retain complete repetitions and discard
        // nearby competing boundaries. There is no input target count and no gap filling.
        val ordered = windows.sortedWith(compareBy<Window> { it.cycle.endNs }.thenBy { it.cycle.startNs })
        val best = DoubleArray(ordered.size + 1)
        val previous = IntArray(ordered.size)
        val selected = BooleanArray(ordered.size)
        ordered.forEachIndexed { index, window ->
            var low = 0; var high = index
            while (low < high) {
                val middle = (low + high) / 2
                if (ordered[middle].cycle.endNs <= window.cycle.startNs) low = middle + 1 else high = middle
            }
            val priorCount = low
            previous[index] = priorCount
            val weight = 2.0 - window.distance / profile.maximumDistance
            val take = best[priorCount] + weight
            if (take > best[index]) { best[index + 1] = take; selected[index] = true }
            else best[index + 1] = best[index]
        }
        val picked = mutableListOf<Window>()
        var count = ordered.size
        while (count > 0) {
            val index = count - 1
            if (selected[index]) { picked += ordered[index]; count = previous[index] } else count--
        }
        return result(picked.asReversed(), if (gaps.isEmpty()) null else "sensor_gap")
    }

}
