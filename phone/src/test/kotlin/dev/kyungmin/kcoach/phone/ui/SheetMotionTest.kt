package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class SheetMotionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sheetEntersVerticallyAtFullWidth() {
        val state = OverlayState()
        var host = Rect.Zero
        var sheet = Rect.Zero
        compose.setContent {
            Box(Modifier.fillMaxSize().onGloballyPositioned { host = it.boundsInRoot() }) {
                OverlayHost(state)
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.show(Overlay(true) {
            Box(Modifier.fillMaxWidth().height(180.dp).onGloballyPositioned { sheet = it.boundsInRoot() })
        }) }
        repeat(22) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (sheet.height > 1f) {
                assertEquals("left at frame $it", host.left, sheet.left, 1f)
                assertEquals("right at frame $it", host.right, sheet.right, 1f)
            }
        }
        assertTrue(sheet.height > 1f)
    }
}
