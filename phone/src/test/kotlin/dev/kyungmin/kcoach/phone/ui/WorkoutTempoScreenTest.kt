package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class WorkoutTempoScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun emptyEnabledTargetsAreRejectedAndValidSecondsAreSavedForTheNextSet() {
        var saved: WorkoutTempoSettings? = null
        compose.setContent { WorkoutTempoScreen("dumbbell_curl", WorkoutTempoSettings("dumbbell_curl"), {}, {}) { saved = it; true } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/tempo-settings-top.png")
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("다음 새 세트부터 저장").performScrollTo().performClick()
        assertNull(saved)
        compose.onNodeWithText("시간 범위·연속 횟수·알림 간격을 확인해 주세요. 알림을 켜려면 시간 범위가 필요해요.").assertExists()
        compose.onAllNodesWithText("최소 초")[0].performScrollTo().performTextInput("2")
        compose.onAllNodesWithText("최대 초")[0].performTextInput("4")
        compose.onNodeWithText("다음 새 세트부터 저장").performScrollTo().performClick()
        assertTrue(requireNotNull(saved).enabled)
        assertEquals(WorkoutTempoRange(2000,4000), saved!!.targets[WorkoutTempoMetric.REPETITION])
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/tempo-settings.png")
    }
}
