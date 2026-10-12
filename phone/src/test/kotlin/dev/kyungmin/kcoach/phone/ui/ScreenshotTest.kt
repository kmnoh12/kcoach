package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kyungmin.kcoach.phone.WorkoutData
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
}
