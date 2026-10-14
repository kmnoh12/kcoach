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
    @Test fun candidateSetupStoresExplicitVariantAndMountWhileRemainingOptional() {
        var choice: WorkoutWearingSelection? = null
        compose.setContent {
            WorkoutWearingStartSheet(WorkoutWearingSelection("LEFT", "OUTSIDE"), {}, { choice = it }, {}, listOf("external_rotation"))
        }
        compose.onNodeWithText("팔꿈치 쪽").performScrollTo().performClick()
        compose.onNodeWithText("손바닥 위 · 전완 수평").performScrollTo().performClick()
        compose.onNodeWithText("이 착용 상태로 시작").performScrollTo().performClick()
        assertEquals(WorkoutWearingSelection("LEFT", "OUTSIDE", "ELBOW", mapOf("external_rotation" to "PALM_UP_HORIZONTAL")), choice)
    }
    @Test fun deferredExercisesDoNotShowOrRequireCandidateSetup() {
        var choice: WorkoutWearingSelection? = null
        compose.setContent {
            WorkoutWearingStartSheet(WorkoutWearingSelection("LEFT", "OUTSIDE"), {}, { choice = it }, {}, listOf("lat_pull_down", "barbell_row"))
        }
        compose.onNodeWithText("본체 위쪽이 향하는 곳").assertDoesNotExist()
        compose.onNodeWithText("케이블 익스터널 로테이션 시작").assertDoesNotExist()
        compose.onNodeWithText("이 착용 상태로 시작").performClick()
        assertEquals(WorkoutWearingSelection("LEFT", "OUTSIDE"), choice)
    }
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
