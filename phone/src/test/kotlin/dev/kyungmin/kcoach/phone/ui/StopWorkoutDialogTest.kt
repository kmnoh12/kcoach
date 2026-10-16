package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.unit.dp
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
class StopWorkoutDialogTest {
    @get:Rule val compose = createComposeRule()

    private fun show(events: MutableList<String>) = compose.setContent {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.background(KColors.Surface).padding(24.dp),
        ) {
            StopWorkoutDialog("운동을 종료할까요?", "완료한 세트를 기록으로 저장할 수 있어요.",
                dismiss = { events.add("dismiss") }, onSave = { events.add("save") }, onDiscard = { events.add("discard") })
        }
    }

    @Test fun savingAndDiscardingAreSeparateChoices() {
        val events = mutableListOf<String>()
        show(events)
        compose.onNodeWithText("운동을 종료할까요?").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/stop-workout-dialog.png")
        compose.onNodeWithText("저장하고 종료").performClick()
        assertEquals(listOf("dismiss", "save"), events)
        events.clear()
        compose.onNodeWithText("저장 안 하고 종료").performClick()
        assertEquals(listOf("dismiss", "discard"), events)
    }

    @Test fun continuingTheWorkoutOnlyDismisses() {
        val events = mutableListOf<String>()
        show(events)
        compose.onNodeWithText("계속 운동").performClick()
        assertEquals(listOf("dismiss"), events)
    }
}
