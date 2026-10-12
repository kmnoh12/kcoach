package dev.kyungmin.kcoach.core

sealed interface ProgressionDecision {
    data class AddReps(val targetTotalRepIncrease: Int, val reason: String) : ProgressionDecision
    data class IncreaseWeight(val incrementKg: Double, val reason: String) : ProgressionDecision
    data class Hold(val reason: String) : ProgressionDecision
    data class ReduceLoad(val percent: Int, val reason: String) : ProgressionDecision
}

class ProgressionEngine {
    fun decide(
        exercise: ExercisePlan,
        workingSets: List<CompletedSet>,
        minimumIncrementKg: Double,
    ): ProgressionDecision {
        require(workingSets.isNotEmpty())
        val reps = workingSets.map { it.reps }
        val averageRir = workingSets.mapNotNull { it.rir }.takeIf { it.isNotEmpty() }?.average()

        val allAtTop = reps.all { it >= exercise.repRange.last }
        if (allAtTop && (averageRir == null || averageRir >= 1.0)) {
            return ProgressionDecision.IncreaseWeight(
                incrementKg = minimumIncrementKg,
                reason = "모든 작업 세트를 목표 횟수까지 채웠어요",
            )
        }

        if (reps.any { it < exercise.repRange.first }) {
            return ProgressionDecision.Hold("무게를 유지하고 모든 작업 세트를 최소 횟수 이상으로 맞춰 보세요")
        }

        return ProgressionDecision.AddReps(
            targetTotalRepIncrease = 1,
            reason = "지금 무게에서 전체 반복을 1회만 늘려 보세요",
        )
    }
}
