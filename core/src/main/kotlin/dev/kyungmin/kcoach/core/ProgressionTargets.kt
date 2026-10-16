package dev.kyungmin.kcoach.core

enum class ProgressionSpeed(val confirmations: Int) { SLOW(3), NORMAL(2), FAST(1) }
data class ProgressionSettings(val enabled: Boolean = true, val speed: ProgressionSpeed = ProgressionSpeed.NORMAL)
enum class ProgressionLoad { EXTERNAL, BODYWEIGHT, WEIGHTED_BODYWEIGHT, ASSISTANCE, TIME }
data class ProgressionKey(val routineId: String, val exerciseId: String, val exerciseIndex: Int, val equipment: String)
data class ProgressionSetTarget(
    val positionKey: String, val weightKg: Double?, val targetReps: Int,
    val isWarmup: Boolean = false, val upperReps: Int? = null, val lowerReps: Int? = null,
)
data class ProgressionPlan(
    val key: ProgressionKey, val sets: List<ProgressionSetTarget>, val pattern: String,
    val load: ProgressionLoad = ProgressionLoad.EXTERNAL,
)
data class IncrementCandidate(val kg: Double?, val needsConfirmation: Boolean) {
    init { require(kg == null || (kg.isFinite() && kg > 0)) }
}

object ProgressionDefaults {
    // Uses the movement-pattern vocabulary shared with ExerciseTaxonomy. Unknown patterns hold.
    private val compound = setOf("vertical_pull", "row", "chest_press", "shoulder_press", "leg_press", "squat", "deadlift", "lunge")
    private val isolation = setOf("chest_fly", "lateral_raise", "rear_delt", "curl", "triceps_pushdown", "triceps_extension", "leg_curl", "leg_extension", "hip", "pullover", "rotation")
    fun upper(pattern: String): Int? = when (pattern) { in compound -> 10; in isolation -> 12; else -> null }
    fun lower(pattern: String, upper: Int): Int = (upper - if (pattern in compound) 4 else 6).coerceAtLeast(1)
    fun increment(equipment: String, observedWeights: List<Double> = emptyList(), confirmedKg: Double? = null): IncrementCandidate {
        if (confirmedKg != null) {
            require(confirmedKg.isFinite() && confirmedKg > 0)
            return IncrementCandidate(confirmedKg, false)
        }
        return when (equipment) {
            "바벨" -> IncrementCandidate(5.0, false)
            "덤벨" -> IncrementCandidate(2.0, false)
            "머신", "케이블", "핀 머신" -> IncrementCandidate(observedWeights.filter { it.isFinite() && it >= 0 }.distinct().sorted().zipWithNext { a, b -> b - a }.minOrNull(), true)
            else -> IncrementCandidate(null, true)
        }
    }
}

/** Auxiliary only; callers must supply total mass for weighted pull-ups. */
object EstimatedOneRepMax {
    fun epley(weightKg: Double?, reps: Int): Double? =
        weightKg?.takeIf { it.isFinite() && it > 0 && reps in 2..10 }?.let { it * (1 + reps / 30.0) }
}
