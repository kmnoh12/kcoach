package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class ManualCountUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun blankIsMissingAndExplicitZeroCanCompleteTheSet() {
        var saved: List<Int>? = null
        compose.setContent { ManualCountSheet("실제 횟수", listOf(12), listOf(1)) { saved = it } }
        compose.onNodeWithText("입력한 횟수로 완료").assertIsNotEnabled()
        compose.onNodeWithTag("actual-reps-0").performTextInput("0")
        compose.onNodeWithText("입력한 횟수로 완료").performClick()
        assertEquals(listOf(0), saved)
    }
    @Test fun bulkCompletionRequiresEveryActualCountOrExplicitTargetConfirmation() {
        var saved: List<Int>? = null
        compose.setContent { ManualCountSheet("모든 세트 완료", listOf(12, 10), listOf(1, 2)) { saved = it } }
        compose.onNodeWithTag("actual-reps-0").performTextInput("9")
        compose.onNodeWithText("입력한 횟수로 완료").assertIsNotEnabled()
        assertNull(saved)
        compose.onNodeWithTag("actual-reps-1").performTextInput("8")
        compose.onNodeWithText("입력한 횟수로 완료").performClick()
        assertEquals(listOf(9, 8), saved)
        compose.onNodeWithText("모두 목표 횟수대로 기록").performClick()
        assertEquals(listOf(12, 10), saved)
    }
}
