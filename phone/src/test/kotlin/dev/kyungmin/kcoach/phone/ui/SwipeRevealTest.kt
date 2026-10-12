package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class SwipeRevealTest {
    @get:Rule
    val compose = createComposeRule()

    private var deletes = 0

    private fun setUp() {
        deletes = 0
        compose.setContent {
            Column(Modifier.padding(16.dp)) {
                SwipeRevealDelete(enabled = true, onDelete = { deletes++ }) {
                    Text("세트 행", modifier = Modifier.fillMaxWidth().height(64.dp).background(KColors.Surface))
                }
            }
        }
    }

    @Test
    fun firstSwipeOnlyRevealsTheButton() {
        setUp()
        compose.onNodeWithText("세트 행").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(0, deletes)
    }

    @Test
    fun tappingTheRevealedButtonDeletes() {
        setUp()
        compose.onNodeWithText("세트 행").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("삭제").performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun secondSwipeDeletes() {
        setUp()
        compose.onNodeWithText("세트 행").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("세트 행").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, deletes)
    }

    @Test
    fun swipingBackClosesWithoutDeleting() {
        setUp()
        compose.onNodeWithText("세트 행").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("세트 행").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(0, deletes)
    }

    @Test
    fun buttonDoesNothingWhileClosed() {
        setUp()
        compose.onNodeWithText("삭제").performClick()
        assertEquals(0, deletes)
    }
}
