package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class SheetDragTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = OverlayState()

    private fun open() {
        compose.setContent { Box(androidx.compose.ui.Modifier.fillMaxSize()) { OverlayHost(state) } }
        compose.runOnUiThread {
            state.show(Overlay(true) {
                SetSheet(
                    "1세트", "케이블 익스터널 로테이션 · 25회",
                    listOf(SheetValueRow("세트 종류", "일반") {}),
                    listOf(SheetAction("세트 삭제", true) {}),
                )
            })
        }
        compose.waitForIdle()
    }

    @Test
    fun draggingTheTitleAreaDownClosesTheSheet() {
        open()
        compose.onNodeWithText("1세트").performTouchInput { swipeDown(startY = centerY, endY = centerY + 500f, durationMillis = 200) }
        compose.waitForIdle()
        assertNull(state.current)
    }

    @Test
    fun draggingARowDownClosesTheSheet() {
        open()
        compose.onNodeWithText("세트 종류").performTouchInput { swipeDown(startY = centerY, endY = centerY + 500f, durationMillis = 200) }
        compose.waitForIdle()
        assertNull(state.current)
    }

    @Test
    fun swipingUpDoesNotClose() {
        open()
        compose.onNodeWithText("1세트").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertNotNull(state.current)
    }

    @Test
    fun scrollableListHandsTheDragToTheSheetWhenAtTheTop() {
        compose.setContent { Box(androidx.compose.ui.Modifier.fillMaxSize()) { OverlayHost(state) } }
        compose.runOnUiThread {
            state.show(Overlay(true) { ChoiceSheet("세트 종류", List(12) { "항목$it" to "설명" }) {} })
        }
        compose.waitForIdle()
        compose.onNodeWithText("항목1").performTouchInput { swipeDown(startY = centerY, endY = centerY + 500f, durationMillis = 200) }
        compose.waitForIdle()
        assertNull(state.current)
    }
}
