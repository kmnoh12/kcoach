package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.kyungmin.kcoach.phone.WorkoutWearingSelection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class WorkoutWearingUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun theMenuShowsConfirmedWearingInsteadOfAStaticLeftHandLabel() {
        compose.setContent { MenuScreen("test", MenuActions({}, { null }, {}), WorkoutWearingSelection("RIGHT", "INSIDE")) }
        compose.onNodeWithText("오른손").assertExists()
        compose.onNodeWithText("손바닥 쪽").assertExists()
        compose.onNodeWithText("왼손").assertDoesNotExist()
    }
    @Test fun startingWithoutCalibrationDoesNotSubmitTheRememberedWearingAsConfirmed() {
        var basic = false; var personalized = false
        compose.setContent { WorkoutWearingStartSheet(WorkoutWearingSelection("LEFT", "INSIDE"), {}, { personalized = true }, { basic = true }) }
        compose.onNodeWithText("보정 없이 시작").performClick()
        assertTrue(basic)
        assertFalse(personalized)
    }
    @Test fun startingWithPersonalizationRequiresBothWearingChoices() {
        var choice: WorkoutWearingSelection? = null
        compose.setContent { WorkoutWearingStartSheet(null, {}, { choice = it }, {}) }
        compose.onNodeWithText("이 착용 상태로 시작").assertIsNotEnabled()
        compose.onNodeWithText("오른손").performClick()
        compose.onNodeWithText("이 착용 상태로 시작").assertIsNotEnabled()
        compose.onNodeWithText("손등 쪽").performClick()
        compose.onNodeWithText("이 착용 상태로 시작").performClick()
        assertEquals(WorkoutWearingSelection("RIGHT", "OUTSIDE"), choice)
    }
}
