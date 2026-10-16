package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kyungmin.kcoach.phone.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class WorkoutProfileUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun signedOutUserCanSaveUnknownBodyFactsAndExplicitWearing() {
        var saved: DeviceWorkoutProfile? = null
        compose.setContent { WorkoutProfileTheme { WorkoutProfileEditor(WorkoutProfile(), null, {}, { saved = it }) } }
        compose.onNodeWithText("여성").performScrollTo().performClick()
        compose.onNodeWithText("골격근량 (kg, 선택)").performScrollTo().assertExists()
        compose.onNodeWithText("오른손").performScrollTo().performClick()
        compose.onNodeWithText("손바닥 쪽").performScrollTo().performClick()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/device-workout-profile-wearing.png")
        compose.onNodeWithText("저장").performClick()
        assertNotNull(saved)
        assertEquals("FEMALE", saved!!.profile.gender)
        assertNull(saved!!.profile.heightCm); assertNull(saved!!.profile.ageYears)
        assertNull(saved!!.profile.weightKg); assertNull(saved!!.profile.skeletalMuscleKg)
        assertEquals(WorkoutWearingSelection("RIGHT", "INSIDE"), saved!!.wearing)
    }

    @Test fun incompleteWearingCannotBeSavedAndClearReallyMeansUnknown() {
        var saved: DeviceWorkoutProfile? = null
        compose.setContent { WorkoutProfileTheme { WorkoutProfileEditor(WorkoutProfile(), null, {}, { saved = it }) } }
        compose.onNodeWithText("왼손").performScrollTo().performClick()
        compose.onNodeWithText("저장").performClick()
        assertNull(saved)
        compose.onNodeWithText("착용 상태 미설정").performScrollTo().performClick()
        compose.onNodeWithText("저장").performClick()
        assertNotNull(saved); assertNull(saved!!.wearing)
    }
}
