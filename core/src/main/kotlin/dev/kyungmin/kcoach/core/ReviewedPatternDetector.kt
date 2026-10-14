package dev.kyungmin.kcoach.core

/** Emission time is separate from the inferred return boundary for real latency evaluation. */
data class ReviewedPatternDetection(val cycle: RepCycle, val emittedAtNs: Long, val distance: Double,
    val profileId: String, val profileVersion: Long)

/** A frozen, causal shadow candidate. No future sample, target count or template update is accepted. */
class ReviewedPatternDetector(exerciseId: String, compatibility: PatternCompatibility,
    private val profile: PatternRuntimeProfile) {
    constructor(exerciseId: String, compatibility: PatternCompatibility, profile: ReviewedPatternProfile) :
        this(exerciseId, compatibility, PatternRuntimeProfile.from(profile))

    private val phase = ExerciseMeasurementContracts.resolve(exerciseId).departurePhase
    private val samples = ArrayDeque<SensorCaptureRecord>()
    private var lastReceivedNs: Long? = null
    private var lastSearchNs: Long? = null
    private var lastEmittedEndNs = 0L
    private var pending: PatternWindowMatch? = null

    init {
        require(compatibility == profile.compatibility && ExerciseMeasurementContracts.resolve(exerciseId).recipe == compatibility.recipe)
        require(ExerciseTrackingProfiles.resolve(exerciseId).mode != TrackingMode.MANUAL_REQUIRED)
    }

    fun add(sample: SensorSample): ReviewedPatternDetection? {
        val previous = lastReceivedNs
        val valid = sample.timestampNs >= 0 && SignalAxis.entries.all { it.value(sample).isFinite() } && sample.sensorAccuracy != 0
        if (!valid || previous != null && sample.timestampNs <= previous) { samples.clear(); pending = null; lastSearchNs = null; return null }
        if (previous != null && sample.timestampNs - previous > 60_000_000) { samples.clear(); pending = null; lastSearchNs = null }
        lastReceivedNs = sample.timestampNs
        samples.addLast(SensorCaptureRecord(sample))
        while (samples.size > 2_000 || samples.first().sample.timestampNs < sample.timestampNs - profile.maximumDurationNs - 200_000_000)
            samples.removeFirst()
        if (lastSearchNs == null || sample.timestampNs - lastSearchNs!! >= 100_000_000) {
            lastSearchNs = sample.timestampNs
            val records = samples.toList()
            var duration = ((profile.minimumDurationNs + 99_999_999) / 100_000_000) * 100_000_000
            var best: PatternWindowMatch? = null
            while (duration <= profile.maximumDurationNs && sample.timestampNs - duration >= records.first().sample.timestampNs) {
                val start = sample.timestampNs - duration
                if (start >= lastEmittedEndNs) {
                    val match = PatternWindowMatcher.match(records, start, sample.timestampNs, phase, profile)
                    if (match != null && (best == null || match.distance < best.distance)) best = match
                }
                duration += 100_000_000
            }
            if (best != null && (pending == null || best.distance < pending!!.distance)) pending = best
        }
        val chosen = pending ?: return null
        if (sample.timestampNs - chosen.cycle.endNs < 120_000_000) return null
        pending = null
        lastEmittedEndNs = chosen.cycle.endNs
        return ReviewedPatternDetection(chosen.cycle, sample.timestampNs, chosen.distance, profile.id, profile.version)
    }
}
