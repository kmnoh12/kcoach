package dev.kyungmin.kcoach.phone

enum class SetType(
    val storageValue: String,
    val displayName: String,
) {
    NORMAL("NORMAL", "일반"),
    WARMUP("WARMUP", "웜업"),
    SUPER("SUPER", "슈퍼"),
    DROP("DROP", "드랍"),
    FAILURE("FAILURE", "실패"),
    ASSISTED("ASSISTED", "보조");

    companion object {
        fun fromStorage(value: String?): SetType = entries.firstOrNull {
            it.storageValue == value || it.displayName == value
        } ?: NORMAL
    }
}

data class PlannedSet(
    var weightKg: Double?,
    var targetReps: Int,
    var completed: Boolean = false,
    var setType: SetType = SetType.NORMAL,
    var gripType: String = "노멀",
    var rpe: String? = null,
)

fun List<PlannedSet>.workSetLabel(index: Int): String {
    val set = getOrNull(index) ?: return ""
    if (set.setType == SetType.WARMUP) return "W"
    return take(index + 1).count { it.setType != SetType.WARMUP }.toString()
}

fun List<PlannedSet>.workSetCount(): Int = count { it.setType != SetType.WARMUP }

fun List<PlannedSet>.completedWorkSetCount(): Int = count {
    it.setType != SetType.WARMUP && it.completed
}

fun adjustedIndexAfterMove(current: Int, from: Int, to: Int): Int = when {
    current == from -> to
    from < to && current in (from + 1)..to -> current - 1
    to < from && current in to until from -> current + 1
    else -> current
}

data class ExerciseDefinition(
    val id: String,
    val name: String,
    val muscles: String,
    val bodyCategory: String,
    val equipment: String,
    val favorite: Boolean = false,
)

data class RoutineExercise(
    val exercise: ExerciseDefinition,
    val sets: MutableList<PlannedSet>,
    var restSeconds: Int = 90,
)

data class WorkoutRoutine(
    val id: String,
    val name: String,
    val muscles: String,
    val lastPerformed: String,
    val exercises: MutableList<RoutineExercise>,
)

data class WorkoutRecord(
    val date: String,
    val title: String,
    val minutes: Int,
    val sets: Int,
    val volumeKg: Int,
)

object WorkoutData {
    private fun exercise(
        id: String,
        name: String,
        muscles: String,
        body: String,
        equipment: String,
        favorite: Boolean = false,
    ) = ExerciseDefinition(id, name, muscles, body, equipment, favorite)

    val catalog = mutableListOf(
        exercise("pull_up", "풀업", "등, 이두", "등", "맨몸", true),
        exercise("assisted_pull_up", "어시스티드 머신 풀업", "등, 이두", "등", "머신"),
        exercise("lying_tbar_row", "머신 라잉 티바 로우", "등, 이두", "등", "머신", true),
        exercise("lat_pull_down", "랫 풀 다운", "등, 이두", "등", "머신", true),
        exercise("seated_row", "머신 시티드 로우", "등, 이두", "등", "머신", true),
        exercise("ez_biceps_curl", "이지바 바이셉 컬", "이두", "이두", "바벨", true),
        exercise("barbell_biceps_curl", "바벨 바이셉 컬", "이두", "이두", "바벨"),
        exercise("preacher_curl", "머신 프리처 컬", "이두", "이두", "머신", true),
        exercise("incline_curl", "덤벨 인클라인 컬", "이두", "이두", "덤벨", true),
        exercise("incline_hammer_curl", "덤벨 인클라인 해머 컬", "이두, 전완", "이두", "덤벨", true),
        exercise("dumbbell_pullover", "라잉 덤벨 풀오버", "가슴, 등", "가슴", "덤벨", true),
        exercise("flat_bench", "바벨 플랫 벤치 프레스", "가슴, 삼두", "가슴", "바벨", true),
        exercise("incline_bench", "바벨 인클라인 벤치 프레스", "가슴, 삼두", "가슴", "바벨", true),
        exercise("chest_press", "머신 체스트 프레스", "가슴, 삼두", "가슴", "머신"),
        exercise("machine_fly", "머신 플라이", "가슴", "가슴", "머신", true),
        exercise("cable_pushdown", "케이블 트라이셉스 푸시 다운", "삼두", "삼두", "케이블", true),
        exercise("cable_overhead_extension", "케이블 오버헤드 트라이셉 익스텐션", "삼두", "삼두", "케이블", true),
        exercise("rope_extension", "케이블 로프 트라이셉 익스텐션", "삼두", "삼두", "케이블", true),
        exercise("rope_overhead_extension", "케이블 로프 오버헤드 트라이셉 익스텐션", "삼두", "삼두", "케이블"),
        exercise("dumbbell_triceps_extension", "덤벨 트라이셉스 익스텐션", "삼두", "삼두", "덤벨", true),
        exercise("ez_lying_extension", "이지바 라잉 트라이셉스 익스텐션", "삼두", "삼두", "바벨"),
        exercise("external_rotation", "케이블 익스터널 로테이션", "어깨", "어깨", "케이블"),
        exercise("crossover_reverse_fly", "케이블 크로스오버 리버스 플라이", "어깨, 등", "어깨", "케이블"),
        exercise("smith_shoulder_press", "스미스 머신 오버헤드 숄더 프레스", "어깨, 삼두", "어깨", "머신", true),
        exercise("machine_shoulder_press", "머신 숄더 프레스", "어깨, 삼두", "어깨", "머신", true),
        exercise("dumbbell_lateral_raise", "덤벨 레터럴 레이즈", "어깨", "어깨", "덤벨", true),
        exercise("one_arm_cable_lateral", "원 암 케이블 레터럴 레이즈", "어깨", "어깨", "케이블", true),
        exercise("machine_reverse_fly", "머신 리버스 플라이", "어깨, 등", "어깨", "머신", true),
        exercise("leg_press", "머신 레그 프레스", "하체", "하체", "머신", true),
        exercise("seated_leg_curl", "시티드 레그 컬", "하체", "하체", "머신", true),
        exercise("leg_extension", "레그 익스텐션", "하체", "하체", "머신", true),
        exercise("hip_adduction", "힙 어덕션", "하체", "하체", "머신"),
        exercise("hip_abduction", "힙 어브덕션", "하체", "하체", "머신"),
    )

    private fun ex(id: String) = checkNotNull(catalog.find { it.id == id })

    private fun sets(vararg values: Pair<Double?, Int>) =
        values.mapTo(mutableListOf()) { PlannedSet(weightKg = it.first, targetReps = it.second) }

    fun defaultRoutines(): MutableList<WorkoutRoutine> = mutableListOf(
        WorkoutRoutine(
            id = "shoulders",
            name = "어깨전문",
            muscles = "어깨, 등",
            lastPerformed = "28일 전",
            exercises = mutableListOf(
                RoutineExercise(ex("external_rotation"), sets(5.0 to 25, 5.0 to 25), 45),
                RoutineExercise(ex("crossover_reverse_fly"), sets(10.0 to 20), 60),
                RoutineExercise(ex("smith_shoulder_press"), sets(30.0 to 20, 30.0 to 10, 30.0 to 10), 120),
                RoutineExercise(ex("machine_shoulder_press"), sets(27.0 to 20, 45.0 to 12, 45.0 to 10, 54.0 to 7), 120),
                RoutineExercise(ex("dumbbell_lateral_raise"), sets(10.0 to 15, 7.0 to 20, 5.0 to 20, 7.0 to 10, 5.0 to 15, 3.0 to 40), 60),
                RoutineExercise(ex("one_arm_cable_lateral"), sets(5.0 to 20, 5.0 to 20, 5.0 to 20), 60),
                RoutineExercise(ex("machine_reverse_fly"), sets(20.0 to 20, 30.0 to 15, 30.0 to 15, 40.0 to 10), 75),
            ),
        ),
        WorkoutRoutine(
            id = "back_biceps",
            name = "등·이두",
            muscles = "등, 이두",
            lastPerformed = "32일 전",
            exercises = mutableListOf(
                RoutineExercise(ex("pull_up"), sets(null to 10, null to 8), 120),
                RoutineExercise(ex("assisted_pull_up"), sets(50.0 to 10, 30.0 to 10), 90),
                RoutineExercise(ex("lying_tbar_row"), sets(40.0 to 12, 40.0 to 12, 40.0 to 12, 55.0 to 7, 55.0 to 7), 120),
                RoutineExercise(ex("lat_pull_down"), sets(40.0 to 12, 47.0 to 10, 55.0 to 10, 55.0 to 8, 40.0 to 12), 90),
                RoutineExercise(ex("seated_row"), sets(50.0 to 12, 60.0 to 10, 68.0 to 10), 90),
                RoutineExercise(ex("ez_biceps_curl"), sets(20.0 to 12, 20.0 to 10, 25.0 to 5), 75),
                RoutineExercise(ex("preacher_curl"), sets(22.0 to 12, 18.0 to 12), 60),
            ),
        ),
        WorkoutRoutine(
            id = "chest_triceps",
            name = "가슴·삼두",
            muscles = "가슴, 삼두",
            lastPerformed = "5일 전",
            exercises = mutableListOf(
                RoutineExercise(ex("dumbbell_pullover"), sets(15.0 to 10, 15.0 to 10), 60),
                RoutineExercise(ex("flat_bench"), sets(20.0 to 15, 60.0 to 12, 60.0 to 12, 60.0 to 12, 60.0 to 12), 150),
                RoutineExercise(ex("incline_bench"), sets(50.0 to 12, 50.0 to 12, 50.0 to 12, 50.0 to 12), 120),
                RoutineExercise(ex("machine_fly"), sets(25.0 to 15, 30.0 to 12, 40.0 to 10, 40.0 to 10), 75),
                RoutineExercise(ex("cable_pushdown"), sets(35.0 to 12, 45.0 to 10, 45.0 to 10, 45.0 to 10), 75),
                RoutineExercise(ex("cable_overhead_extension"), sets(25.0 to 12, 25.0 to 12, 30.0 to 10), 75),
            ),
        ),
        WorkoutRoutine(
            id = "arms_legs",
            name = "팔·하체",
            muscles = "이두, 삼두, 하체",
            lastPerformed = "22일 전",
            exercises = mutableListOf(
                RoutineExercise(ex("pull_up"), sets(null to 10, null to 8, null to 8), 120),
                RoutineExercise(ex("ez_biceps_curl"), sets(20.0 to 12, 25.0 to 10, 30.0 to 7, 20.0 to 12), 75),
                RoutineExercise(ex("incline_curl"), sets(10.0 to 12, 10.0 to 12), 60),
                RoutineExercise(ex("incline_hammer_curl"), sets(10.0 to 12, 10.0 to 12), 60),
                RoutineExercise(ex("dumbbell_triceps_extension"), sets(18.0 to 15, 18.0 to 12, 20.0 to 10, 20.0 to 10), 75),
                RoutineExercise(ex("rope_extension"), sets(25.0 to 12, 20.0 to 15, 20.0 to 15), 60),
                RoutineExercise(ex("leg_press"), sets(77.1 to 12, 104.3 to 10, 113.4 to 10, 131.5 to 7, 131.5 to 7), 150),
                RoutineExercise(ex("seated_leg_curl"), sets(36.0 to 12, 45.0 to 10, 45.0 to 10), 90),
                RoutineExercise(ex("leg_extension"), sets(40.0 to 15, 59.0 to 10, 68.0 to 8), 90),
            ),
        ),
    )

    val records = listOf(
        WorkoutRecord("2026-07-22", "가슴·삼두", 69, 22, 10_085),
        WorkoutRecord("2026-07-05", "팔·하체", 75, 29, 13_547),
        WorkoutRecord("2026-06-29", "어깨전문", 58, 23, 7_168),
        WorkoutRecord("2026-06-28", "팔·하체", 77, 27, 14_285),
        WorkoutRecord("2026-06-26", "가슴·삼두", 67, 24, 9_895),
        WorkoutRecord("2026-06-25", "등·이두", 68, 22, 9_461),
    )
}
