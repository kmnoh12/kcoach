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
}
