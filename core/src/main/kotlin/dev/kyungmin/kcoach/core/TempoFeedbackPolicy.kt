package dev.kyungmin.kcoach.core

/** Optional observations only. The caller supplies confirmed boundaries and validated phase times. */
class TempoFeedbackPolicy(config: Config = Config()) {
    enum class Metric { REPETITION, CONCENTRIC, ECCENTRIC }
    enum class Direction { SHORTER, LONGER }
    data class RangeMs(val minimum: Long, val maximum: Long) {
        init { require(minimum > 0 && maximum >= minimum) }
    }
    data class Config(
        val enabled: Boolean = false,
        val targets: Map<Metric, RangeMs> = emptyMap(),
        val consecutiveRepetitions: Int = 3,
        val minimumAlertIntervalMs: Long = 10_000,
    ) {
        init { require(consecutiveRepetitions > 0 && minimumAlertIntervalMs >= 0) }
    }
    data class Observation(
        val setId: String, val attemptId: String, val repetition: Int, val endNs: Long,
        val repetitionMs: Long?, val concentricMs: Long? = null, val eccentricMs: Long? = null,
        val phasesValidated: Boolean = false, val complete: Boolean = true,
        val qualitySufficient: Boolean = false,
    )
    data class Deviation(val metric: Metric, val observedMs: Long, val target: RangeMs,
        val direction: Direction, val consecutiveRepetitions: Int)
    data class Feedback(val setId: String, val attemptId: String, val repetition: Int,
        val endNs: Long, val deviations: List<Deviation>)

    private var settings = config.copy(targets = config.targets.toMap())
    private var scope: Pair<String, String>? = null
    private val closedAttempts = mutableSetOf<Pair<String, String>>()
    private var lastRepetition = 0
    private var lastEndNs = -1L
    private var lastAlertNs: Long? = null
    private val streaks = mutableMapOf<Metric, Pair<Direction, Int>>()

    /** Call on a real pause, including a pause with no sensor observations. Cooldown is retained. */
    fun pause() { streaks.clear() }

    fun configure(config: Config) {
        val frozen = config.copy(targets = config.targets.toMap())
        if (frozen != settings) { settings = frozen; streaks.clear() }
    }

    /** One instance per workout, serial calls, endNs in the same monotonic clock across attempts. */
    fun observe(input: Observation): Feedback? {
        if (input.setId.isBlank() || input.attemptId.isBlank() || input.repetition <= 0 || input.endNs < 0) {
            streaks.clear(); return null
        }
        val incoming = input.setId to input.attemptId
        if (incoming in closedAttempts || input.endNs <= lastEndNs) return null
        if (incoming != scope) {
            scope?.let(closedAttempts::add)
            scope = incoming; lastRepetition = 0; streaks.clear()
        }
        if (input.repetition <= lastRepetition) return null
        if (lastRepetition != 0 && input.repetition != lastRepetition + 1) streaks.clear()
        lastRepetition = input.repetition; lastEndNs = input.endNs
        if (!settings.enabled || !input.complete || !input.qualitySufficient || input.repetitionMs == null || input.repetitionMs <= 0) {
            streaks.clear(); return null
        }
        val measured = mapOf(Metric.REPETITION to input.repetitionMs,
            Metric.CONCENTRIC to input.concentricMs.takeIf { input.phasesValidated },
            Metric.ECCENTRIC to input.eccentricMs.takeIf { input.phasesValidated })
        val deviations = settings.targets.mapNotNull { (metric, target) ->
            val duration = measured[metric]
            if (duration == null || duration <= 0 || metric != Metric.REPETITION && duration > input.repetitionMs) {
                streaks.remove(metric); return@mapNotNull null
            }
            val direction = when {
                duration < target.minimum -> Direction.SHORTER
                duration > target.maximum -> Direction.LONGER
                else -> { streaks.remove(metric); return@mapNotNull null }
            }
            val previous = streaks[metric]
            val count = if (previous?.first == direction) (previous.second + 1).coerceAtMost(settings.consecutiveRepetitions) else 1
            streaks[metric] = direction to count
            if (count < settings.consecutiveRepetitions) null else Deviation(metric, duration, target, direction, count)
        }
        if (deviations.isEmpty()) return null
        val previousAlert = lastAlertNs
        // Division avoids overflow even for very large configured intervals.
        if (previousAlert != null && (input.endNs - previousAlert) / 1_000_000 < settings.minimumAlertIntervalMs) return null
        lastAlertNs = input.endNs
        return Feedback(input.setId, input.attemptId, input.repetition, input.endNs, deviations)
    }
}
