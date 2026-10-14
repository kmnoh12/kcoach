package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.junit.Assert.assertTrue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class LiquidControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeCanBeSelectedAgain() {
        val selected = mutableIntStateOf(0)
        compose.setContent { Box { FloatingTabBar(selected.intValue, true) { selected.intValue = it } } }
        compose.onRoot().performTouchInput { click(Offset(width * .9f, height / 2f)) }
        compose.runOnIdle { assertEquals(4, selected.intValue) }
        compose.onRoot().performTouchInput { click(Offset(width * .08f, height / 2f)) }
        compose.runOnIdle { assertEquals(0, selected.intValue) }
    }

    @Test fun warmupCanReturnToNormalWithFreshCallback() {
        val selected = mutableIntStateOf(0)
        compose.setContent {
            val current = selected.intValue
            SetSheet("세트", "상세", listOf(SegGroup("종류", listOf("일반", "웜업").mapIndexed { i, label ->
                SegOption(label, current == i) { selected.intValue = i }
            })), emptyList())
        }
        compose.onNodeWithTag("seg-종류").performTouchInput { click(Offset(width * .75f, height / 2f)) }
        compose.runOnIdle { assertEquals(1, selected.intValue) }
        compose.onNodeWithTag("seg-종류").performTouchInput { click(Offset(width * .25f, height / 2f)) }
        compose.runOnIdle { assertEquals(0, selected.intValue) }
    }
    @Test fun directJumpDoesNotExpandIntermediateTabsAndSettlesAtTwoToOne() {
        val selected = mutableIntStateOf(1)
        compose.setContent { FloatingTabBar(selected.intValue, true) { selected.intValue = it } }
        compose.waitForIdle()
        fun width(i: Int) = compose.onNodeWithTag("nav-item-$i").fetchSemanticsNode().boundsInRoot.width
        val statsWidth = width(2)
        val calibrationWidth = width(3)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("nav-gesture").performTouchInput { click(Offset(width * .94f, height / 2f)) }
        repeat(16) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals(statsWidth, width(2), 1f)
            assertEquals(calibrationWidth, width(3), 1f)
        }
        compose.runOnIdle { assertEquals(4, selected.intValue) }
        val height = compose.onNodeWithTag("nav-item-4").fetchSemanticsNode().boundsInRoot.height
        assertEquals(2f, width(4) / height, .03f)
    }

    @Test fun swipeBothDirectionsAndTapEmitHaptics() {
        val selected = mutableIntStateOf(0)
        var pulses = 0
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { pulses++ }
        }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                FloatingTabBar(selected.intValue, true) { selected.intValue = it }
            }
        }
        val bar = compose.onNodeWithTag("nav-gesture")
        bar.performTouchInput { swipe(Offset(width * .1f, height / 2f), Offset(width * .95f, height / 2f), 320) }
        compose.runOnIdle { assertEquals(4, selected.intValue); assertTrue(pulses > 1) }
        bar.performTouchInput { swipe(Offset(width * .95f, height / 2f), Offset(width * .05f, height / 2f), 320) }
        compose.runOnIdle { assertEquals(0, selected.intValue) }
        val before = pulses
        bar.performTouchInput { click(Offset(width * .05f, height / 2f)) }
        compose.runOnIdle { assertEquals(before + 1, pulses) }
    }

    @Test fun expandedTabHitRegionAndLatestCallbackAreUsed() {
        val selected = mutableIntStateOf(0)
        val generation = mutableIntStateOf(0)
        var callbackGeneration = -1
        compose.setContent {
            val captured = generation.intValue
            FloatingTabBar(selected.intValue, true) { selected.intValue = it; callbackGeneration = captured }
        }
        compose.runOnIdle { generation.intValue = 9 }
        // Home occupies 96dp of the bar, including this point; equal-width hit testing picked Records.
        compose.onNodeWithTag("nav-gesture").performTouchInput { click(Offset(width * .28f, height / 2f)) }
        compose.runOnIdle { assertEquals(0, selected.intValue) }
        compose.onNodeWithTag("nav-gesture").performTouchInput { click(Offset(width * .94f, height / 2f)) }
        compose.runOnIdle { assertEquals(4, selected.intValue); assertEquals(9, callbackGeneration) }
    }

    @Test fun verticalGestureDoesNotChangeSetType() {
        val selected = mutableIntStateOf(0)
        compose.setContent {
            SetSheet("세트", "상세", listOf(SegGroup("종류", listOf("일반", "웜업").mapIndexed { i, label ->
                SegOption(label, selected.intValue == i) { selected.intValue = i }
            })), emptyList())
        }
        compose.onNodeWithTag("seg-종류").performTouchInput {
            swipe(Offset(width * .75f, height * .1f), Offset(width * .75f, height * .95f), 200)
        }
        compose.runOnIdle { assertEquals(0, selected.intValue) }
    }

}
