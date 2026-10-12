package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kyungmin.kcoach.phone.WorkoutData
import dev.kyungmin.kcoach.phone.WorkoutRecord
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders screens to PNG without an emulator: `gradle :phone:recordRoborazziDebug`, then read build/outputs/roborazzi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { content() }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun home() {
        val routines = WorkoutData.defaultRoutines()
        val rows = routines.mapIndexed { i, r -> RoutineRowUi2(r, if (i == 0) null else i * 3) }
        val ui = HomeUi(
            week = listOf(
                WeekDayUi("월", 0.8, false, false), WeekDayUi("화", null, false, false),
                WeekDayUi("수", 0.5, false, false), WeekDayUi("목", null, true, false),
                WeekDayUi("금", null, false, true), WeekDayUi("토", null, false, true), WeekDayUi("일", null, false, true),
            ),
            weekCount = 2, rows = rows, longestRest = rows.lastOrNull(),
        )
        val actions = HomeActions({}, {}, {}, {}, {}, {}, {})
        shot("home") { HomeScreen(ui, null, actions) }
    }

    @Test
    fun stats() = shot("stats") {
        StatsScreen(
            StatsUi(
                "10월",
                listOf("4회" to "운동", "270분" to "총 시간", "96세트" to "총 세트", "40,809kg" to "총 볼륨"),
                listOf("등·이두" to "32세트", "가슴·삼두" to "26세트", "어깨전문" to "24세트"),
            ),
        )
    }

    @Test
    fun statsEmpty() = shot("stats_empty") { StatsScreen(StatsUi("10월", emptyList(), emptyList())) }

    @Test
    fun menu() = shot("menu") { MenuScreen("0.7.1", MenuActions({}, { null }, {})) }

    @Test
    fun records() {
        val month = java.time.YearMonth.now()
        val records = listOf(
            WorkoutRecord("${month}-03", "어깨전문", 74, 24, 9120),
            WorkoutRecord("${month}-05", "등·이두", 68, 22, 7840),
            WorkoutRecord("${month}-09", "가슴·삼두", 81, 26, 10450),
        )
        shot("records") { RecordsScreen(records, month, "가슴·삼두", RecordsActions({}, {})) }
    }

    @Test
    fun library() = shot("library") {
        val context = androidx.compose.ui.platform.LocalContext.current
        val entries = WorkoutData.catalog.map { item ->
            val (order, title) = dev.kyungmin.kcoach.core.ExerciseTaxonomy.section(item.bodyCategory, item.pattern)
            LibraryEntry(item, order, title, dev.kyungmin.kcoach.core.ExerciseTaxonomy.bodyRank(item.bodyCategory))
        }
        LibraryScreen(entries, setOf("pull_up", "lat_pull_down"), LibraryActions({}, {}, {})) { android.view.View(context) }
    }

    @Test
    fun calibration() = shot("calibration") {
        val context = androidx.compose.ui.platform.LocalContext.current
        val blank = { android.view.View(context) }
        val ui = CalibrationUi(
            "랫 풀 다운", "개인 보정 적용 중", true, "기준 자세를 잡고 자연스럽게 5회 왕복해요", false,
            CalibrationProfileUi("보정된 값", listOf("반복 길이" to "42cm", "평균 템포" to "2.4초"), "센서 기록은 이 폰에만 남아요"),
            listOf(
                CalibrationRowUi("a", "랫 풀 다운", "등", "보정 완료", true, blank),
                CalibrationRowUi("b", "시티드 로우", "등", "보정 전", false, blank),
            ),
        )
        CalibrationScreen(ui, { false }, { CalibrationButtonUi("보정 시작", true) }, CalibrationActions({}, {}, {}))
    }

    @Test
    fun result() = shot("result") {
        WorkoutResultScreen(
            ResultUi(
                "등·이두", "68분 · 22세트 · 세션 12345678",
                listOf(ResultSectionUi("랫 풀 다운 · 세트 1", listOf("워치 측정값" to "10회", "확정 반복수" to "10회"))),
                "확정 반복수는 폰이 센서 기록을 다시 분석한 결과를 기준으로 해요.",
                listOf("랫 풀 다운 · 최고 중량" to "60 → 62.5kg", "시티드 로우 · 최고 볼륨" to "2,400 → 2,640kg"),
            ),
            {}, {},
        )
    }

    @Test
    fun routineDetail() = shot("routine_detail") {
        val context = androidx.compose.ui.platform.LocalContext.current
        val routine = WorkoutData.defaultRoutines()[1]
        val rows = routine.exercises.mapIndexed { i, item ->
            RoutineRowUi(i, item.exercise.name, "${item.sets.size}세트 · ${item.exercise.muscles}", { android.view.View(context) })
        }
        RoutineDetailScreen(
            routine.name, rows, "시작", KIcon.Play, false,
            RoutineDetailActions({}, {}, {}, {}, { _, _ -> }, {}, {}, {}),
        )
    }

    private fun noActions(): WorkoutActions = java.lang.reflect.Proxy.newProxyInstance(
        WorkoutActions::class.java.classLoader, arrayOf(WorkoutActions::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "NoActions"
            else -> null
        }
    } as WorkoutActions

    private fun workoutModel(resting: Boolean): WorkoutUiModel {
        val now = System.currentTimeMillis()
        fun set(uid: Int, label: String, kg: String, reps: Int, done: Boolean, prev: String?) =
            SetUi(uid, label, kg, reps, done, label == "노멀" && false, "", 90, false, null, prev)
        val sets = listOf(
            set(0, "1", "50", 10, true, "45×10"),
            set(1, "2", "55", 10, true, "50×10"),
            set(2, "3", "55", 10, false, "50×9"),
            set(3, "4", "55", 8, false, null),
        )
        val ex = ExerciseUi(
            "lat_pull_down", "랫 풀 다운", "등, 이두", true, 90, 10, 20, 2, 4, 2, sets,
            volumeDone = 1050, volumeTarget = 2100,
            history = HistoryUi("지난번 45×10 · 50×10 · 50×9", 1840, "55kg로 올리기", "모든 작업 세트를 목표 횟수까지 채웠어요", true),
        )
        return WorkoutUiModel(
            "등·이두", false, false, now - 21 * 60_000L, 0L, 0L, 0, listOf(ex), false,
            if (resting) now - 20_000L else 0L, if (resting) now + 70_000L else 0L, 0L, "다음 랫 풀 다운 3세트",
        )
    }

    @Test
    fun workout() = shot("workout") {
        val context = androidx.compose.ui.platform.LocalContext.current
        ActiveWorkoutScreen(
            workoutModel(false), noActions(),
            LiveLine("Watch8 연결됨 · 랫 풀 다운 · 세트 3", "t").also { it.setTextColor(0xFF2DD4BF.toInt()) },
            LiveLine("7/10회 · 55kg · 심박 112bpm", "t"),
        ) { android.view.View(context) }
    }

    @Test
    fun workoutResting() = shot("workout_rest") {
        val context = androidx.compose.ui.platform.LocalContext.current
        ActiveWorkoutScreen(
            workoutModel(true), noActions(),
            LiveLine("Watch8 연결됨", "t"), LiveLine("휴식 중 · 10/10회 · 심박 98bpm", "t"),
        ) { android.view.View(context) }
    }
}
