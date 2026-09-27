package dev.kyungmin.kcoach.core

/**
 * One detector configuration per observable wrist-motion family.
 *
 * Exercise names select a family; they do not own a private copy of these
 * thresholds. A family value should only change from shared Watch8 evidence.
 */
data class MotionFamilyTemplate(
    val family: MotionFamily,
    val signal: TrackingSignal,
    val minimumRepMs: Long,
    val maximumRepMs: Long,
    val minimumSeparationMs: Long,
    val minimumRelativeRom: Double,
    val minimumFeatureRate: Double,
    val minimumLobeDisplacement: Double,
    val minimumLobeMs: Long,
    val maximumLobeMs: Long,
    val minimumTrajectorySpread: Double = 0.0,
)

data class ExerciseMotionBinding(
    val exerciseId: String,
    val label: String,
    val family: MotionFamily,
    val capabilityTier: CapabilityTier,
    val grip: String = "exercise_default_recorded",
)

object MotionFamilyTemplates {
    private val templates = listOf(
        MotionFamilyTemplate(
            MotionFamily.FLEXION_EXTENSION, TrackingSignal.ROTATION_ENERGY,
            350L, 4_500L, 450L, 0.12, 0.16, 0.08, 120L, 1_700L,
        ),
        MotionFamilyTemplate(
            MotionFamily.PRESS_RETURN, TrackingSignal.COMBINED_MOTION,
            400L, 5_000L, 550L, 0.13, 0.12, 0.07, 110L, 1_800L,
        ),
        MotionFamilyTemplate(
            MotionFamily.PULL_RETURN, TrackingSignal.COMBINED_MOTION,
            500L, 6_000L, 650L, 0.15, 0.14, 0.07, 120L, 2_200L,
        ),
        MotionFamilyTemplate(
            MotionFamily.MULTI_AXIS_RETURN, TrackingSignal.COMBINED_MOTION,
            450L, 5_000L, 550L, 0.14, 0.12, 0.07, 110L, 2_000L, 0.04,
        ),
        MotionFamilyTemplate(
            MotionFamily.FOREARM_ROTATION, TrackingSignal.FOREARM_ROTATION,
            400L, 4_500L, 400L, 0.18, 0.12, 0.22, 100L, 2_100L,
        ),
        MotionFamilyTemplate(
            MotionFamily.UNOBSERVABLE_MANUAL, TrackingSignal.UNOBSERVABLE_FROM_WRIST,
            0L, 0L, 0L, 0.0, 0.0, 0.0, 0L, 0L,
        ),
    ).associateBy { it.family }

    fun get(family: MotionFamily): MotionFamilyTemplate = checkNotNull(templates[family])
}

/**
 * Catalog entries only bind an exercise to a shared family and capability.
 * Repeated detector thresholds deliberately do not belong here.
 */
object ExerciseMotionCatalog {
    val explicitBindings: List<ExerciseMotionBinding> = listOf(
        binding("pull_up", "풀업", MotionFamily.PULL_RETURN, CapabilityTier.C_PERSONALIZED_AUTO_COUNT_BETA, "same_bar_grip"),
        binding("assisted_pull_up", "어시스티드 풀업", MotionFamily.PULL_RETURN, CapabilityTier.C_PERSONALIZED_AUTO_COUNT_BETA, "same_machine_grip"),
        binding("lying_tbar_row", "라잉 티바 로우", MotionFamily.PULL_RETURN),
        binding("lat_pull_down", "랫 풀다운", MotionFamily.PULL_RETURN),
        binding("seated_row", "시티드 로우", MotionFamily.PULL_RETURN),
        binding("ez_biceps_curl", "이지바 바이셉 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.A_WRIST_DIRECT_AUTO, "same_bar_grip"),
        binding("barbell_biceps_curl", "바벨 바이셉 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.A_WRIST_DIRECT_AUTO, "same_bar_grip"),
        binding("preacher_curl", "프리처 컬", MotionFamily.FLEXION_EXTENSION),
        binding("incline_curl", "인클라인 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.A_WRIST_DIRECT_AUTO),
        binding("incline_hammer_curl", "인클라인 해머 컬", MotionFamily.FLEXION_EXTENSION, CapabilityTier.A_WRIST_DIRECT_AUTO, "neutral_grip"),
        binding("dumbbell_pullover", "덤벨 풀오버", MotionFamily.MULTI_AXIS_RETURN),
        binding("flat_bench", "플랫 벤치프레스", MotionFamily.PRESS_RETURN, grip = "same_grip_width"),
        binding("incline_bench", "인클라인 벤치프레스", MotionFamily.PRESS_RETURN, grip = "same_grip_width"),
        binding("chest_press", "체스트프레스", MotionFamily.PRESS_RETURN),
        binding("machine_fly", "머신 플라이", MotionFamily.MULTI_AXIS_RETURN),
        binding("cable_pushdown", "케이블 푸시다운", MotionFamily.PRESS_RETURN),
        binding("cable_overhead_extension", "케이블 오버헤드 익스텐션", MotionFamily.FLEXION_EXTENSION),
        binding("rope_extension", "로프 익스텐션", MotionFamily.PRESS_RETURN),
        binding("rope_overhead_extension", "로프 오버헤드 익스텐션", MotionFamily.FLEXION_EXTENSION),
        binding("dumbbell_triceps_extension", "덤벨 트라이셉스 익스텐션", MotionFamily.FLEXION_EXTENSION),
        binding("ez_lying_extension", "이지바 라잉 익스텐션", MotionFamily.PRESS_RETURN),
        binding("external_rotation", "케이블 외회전", MotionFamily.FOREARM_ROTATION, CapabilityTier.C_PERSONALIZED_AUTO_COUNT_BETA),
        binding("crossover_reverse_fly", "크로스오버 리버스 플라이", MotionFamily.MULTI_AXIS_RETURN),
        binding("smith_shoulder_press", "스미스 숄더프레스", MotionFamily.PRESS_RETURN),
        binding("machine_shoulder_press", "머신 숄더프레스", MotionFamily.PRESS_RETURN),
        binding("dumbbell_lateral_raise", "덤벨 레터럴 레이즈", MotionFamily.MULTI_AXIS_RETURN),
        binding("one_arm_cable_lateral", "원 암 케이블 레터럴", MotionFamily.MULTI_AXIS_RETURN),
        binding("machine_reverse_fly", "머신 리버스 플라이", MotionFamily.MULTI_AXIS_RETURN),
        manual("leg_press", "레그프레스"),
        manual("seated_leg_curl", "시티드 레그컬"),
        manual("leg_extension", "레그 익스텐션"),
        manual("hip_adduction", "힙 어덕션"),
        manual("hip_abduction", "힙 어브덕션"),
    )

    private val byExercise = explicitBindings.associateBy { it.exerciseId }
    private val aliases = mapOf(
        "cable_external_rotation" to "external_rotation",
        "cable_external_rot" to "external_rotation",
    )

    fun normalizeExerciseId(exerciseId: String): String {
        val normalized = exerciseId.trim().lowercase()
        return aliases[normalized] ?: normalized
    }

    fun resolve(exerciseId: String, exerciseName: String = ""): ExerciseMotionBinding? {
        val normalizedId = normalizeExerciseId(exerciseId)
        return byExercise[normalizedId] ?: infer(normalizedId, exerciseName)
    }

    /**
     * Conservative fallback for user-added/future exercises. Manual-only terms
     * win before motion terms, preventing names such as leg press or leg curl
     * from being promoted just because they contain "press" or "curl".
     */
    private fun infer(exerciseId: String, exerciseName: String): ExerciseMotionBinding? {
        val searchable = "$exerciseId ${exerciseName.lowercase()}"
        if (MANUAL_ONLY_TERMS.any(searchable::contains)) {
            return manual(exerciseId.ifBlank { "unknown" }, exerciseName.ifBlank { "손목 관측 불가 운동" })
        }
        val family = when {
            ROTATION_TERMS.any(searchable::contains) -> MotionFamily.FOREARM_ROTATION
            MULTI_AXIS_TERMS.any(searchable::contains) -> MotionFamily.MULTI_AXIS_RETURN
            FLEXION_TERMS.any(searchable::contains) -> MotionFamily.FLEXION_EXTENSION
            PULL_TERMS.any(searchable::contains) -> MotionFamily.PULL_RETURN
            PRESS_TERMS.any(searchable::contains) -> MotionFamily.PRESS_RETURN
            else -> return null
        }
        val tier = if (family == MotionFamily.FOREARM_ROTATION) {
            CapabilityTier.C_PERSONALIZED_AUTO_COUNT_BETA
        } else {
            CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS
        }
        return binding(
            exerciseId.ifBlank { "unknown" },
            exerciseName.ifBlank { exerciseId.replace('_', ' ') },
            family,
            tier,
        )
    }

    private fun binding(
        exerciseId: String,
        label: String,
        family: MotionFamily,
        tier: CapabilityTier = CapabilityTier.B_AUTO_COUNT_LIMITED_METRICS,
        grip: String = "exercise_default_recorded",
    ) = ExerciseMotionBinding(exerciseId, label, family, tier, grip)

    private fun manual(exerciseId: String, label: String) = binding(
        exerciseId, label, MotionFamily.UNOBSERVABLE_MANUAL, CapabilityTier.D_MANUAL_REQUIRED,
    )

    private val MANUAL_ONLY_TERMS = listOf(
        "leg_", "leg ", "legpress", "legcurl", "legextension", "레그", "squat", "스쿼트", "lunge", "런지",
        "hip_", "hip ", "힙", "calf", "카프", "하체",
    )
    private val ROTATION_TERMS = listOf("external_rotation", "external rotation", "외회전", "익스터널 로테이션")
    private val MULTI_AXIS_TERMS = listOf("fly", "플라이", "raise", "레이즈", "pullover", "풀오버")
    private val FLEXION_TERMS = listOf("curl", "컬", "overhead_extension", "overhead extension", "오버헤드 익스텐션", "triceps_extension", "트라이셉스 익스텐션", "kickback", "킥백")
    private val PULL_TERMS = listOf("pull_up", "pull up", "풀업", "chin_up", "chin up", "친업", "pull_down", "pull down", "pulldown", "풀 다운", "풀다운", "row", "로우")
    private val PRESS_TERMS = listOf("press", "프레스", "pushdown", "push down", "푸시다운", "푸시 다운", "lying_extension", "라잉 익스텐션")
}
