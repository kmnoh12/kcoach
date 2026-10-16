package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kyungmin.kcoach.core.ProgressionSettings
import dev.kyungmin.kcoach.core.ProgressionSpeed
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class SettingsProgressionTest {
    @get:Rule val compose = createComposeRule()
    private val saved = mutableListOf<ProgressionSettings>()

    private fun show(initial: ProgressionSettings = ProgressionSettings()) =
        compose.setContent { SettingsScreen(SettingsActions(back = {}), initial) { saved.add(it) } }

    private fun slider() = compose.onNodeWithContentDescription("진행 속도")

    @Test fun startsOnWithTheNormalSpeedAndTellsWhatItDoes() {
        show()
        compose.onNodeWithText("점진적 과부하 사용").assertExists()
        compose.onAllNodesWithText("보통").assertCountEquals(2) // the chosen speed and its place on the slider
        compose.onNodeWithText("목표 횟수를 다 채운 날이 2번 이어지면 무게를 올려요.").assertExists()
        compose.onNodeWithText("예시").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings-progression-on.png")
    }

    @Test fun theSwitchSavesAndTheSliderStopsReactingWhileOff() {
        show()
        compose.onNodeWithContentDescription("점진적 과부하 사용").performClick()
        assertEquals(listOf(ProgressionSettings(enabled = false)), saved)
        slider().performTouchInput { click(Offset(width - 4f, centerY)) }
        assertEquals("a slider tap while off must not save", 1, saved.size)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings-progression-off.png")
        compose.onNodeWithContentDescription("점진적 과부하 사용").performClick()
        assertEquals(ProgressionSettings(enabled = true), saved.last())
    }

    @Test fun tappingEitherEndSnapsToSlowOrFastAndChangesTheExample() {
        show()
        slider().performTouchInput { click(Offset(width - 4f, centerY)) }
        assertEquals(ProgressionSettings(true, ProgressionSpeed.FAST), saved.last())
        compose.onNodeWithText("목표 횟수를 다 채운 날, 바로 무게를 올려요.").assertExists()
        compose.onAllNodesWithText("85kg 6회").assertCountEquals(2)
        slider().performTouchInput { click(Offset(4f, centerY)) }
        assertEquals(ProgressionSettings(true, ProgressionSpeed.SLOW), saved.last())
        compose.onNodeWithText("목표 횟수를 다 채운 날이 3번 이어지면 무게를 올려요.").assertExists()
        compose.onAllNodesWithText("80kg 10회").assertCountEquals(2)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings-progression-slow.png")
    }

    @Test fun savedChoicesAreShownWhenTheScreenOpens() {
        show(ProgressionSettings(enabled = true, speed = ProgressionSpeed.FAST))
        compose.onNodeWithText("목표 횟수를 다 채운 날, 바로 무게를 올려요.").assertExists()
    }
}
