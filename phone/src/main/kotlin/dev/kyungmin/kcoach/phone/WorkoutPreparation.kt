package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.ExerciseMotionCatalog
import dev.kyungmin.kcoach.core.StartWristContext

internal data class WorkoutPreparationQuestion(val exerciseId: String, val title: String,
    val description: String, val expectedVariant: String)

/** Only explicit answers for exercises in this routine can enter its frozen start context. */
internal object WorkoutPreparation {
    fun questions(exerciseIds: List<String>): List<WorkoutPreparationQuestion> =
        exerciseIds.map(ExerciseMotionCatalog::normalizeExerciseId).distinct().mapNotNull { id ->
            val expected = StartWristContext.expectedVariant(id) ?: return@mapNotNull null
            val (title, description) = when (id) {
                "external_rotation" -> "케이블 익스터널 로테이션" to "손바닥을 위로 향하고 전완을 수평으로 둔 채 시작하나요?"
                "crossover_reverse_fly" -> "케이블 리버스 플라이" to "손등이 위를 향하는 그립으로 시작하나요?"
                "dumbbell_lateral_raise" -> "덤벨 레터럴 레이즈" to "팔을 몸 옆으로 내린 자세에서 시작하나요?"
                else -> return@mapNotNull null
            }
            WorkoutPreparationQuestion(id, title, description, expected)
        }

    /** Every exercise that has a start-posture choice, for the settings screen. */
    fun allQuestions(): List<WorkoutPreparationQuestion> =
        questions(listOf("external_rotation", "crossover_reverse_fly", "dumbbell_lateral_raise"))

    fun selection(defaults: WorkoutWearingSelection?, exerciseIds: List<String>,
        answers: Map<String, String>): WorkoutWearingSelection? {
        val variants = questions(exerciseIds).associate { question ->
            val answer = answers[question.exerciseId] ?: "UNKNOWN"
            require(answer in StartWristContext.variantsFor(question.exerciseId))
            question.exerciseId to answer
        }
        // Side/placement never establish a watch's physical sensor-axis orientation.
        return defaults?.copy(deviceTop = null, startVariants = variants)
    }
}
