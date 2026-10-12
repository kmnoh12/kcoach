package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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
class SegmentedBarTest {
    @get:Rule
    val compose = createComposeRule()

    private val picked = mutableListOf<Int>()

    private fun show(selected: Int = 0) {
        picked.clear()
        compose.setContent {
            Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                SetSheet(
                    "1세트", "테스트",
                    listOf(SegGroup("종류", List(5) { i -> SegOption("항목$i", i == selected) { picked += i } })),
                    emptyList(),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun center(index: Int, width: Float) = width * (index + 0.5f) / 5f

    @Test
    fun tapSelectsTheTappedSegmentOnce() {
        show()
        compose.onNodeWithTag("seg-종류").performTouchInput { down(androidx.compose.ui.geometry.Offset(center(3, width.toFloat()), centerY)); up() }
        compose.waitForIdle()
        assertEquals(listOf(3), picked)
    }

    @Test
    fun slidingAcrossSegmentsSelectsOnlyTheOneUnderTheFingerOnRelease() {
        show()
        compose.onNodeWithTag("seg-종류").performTouchInput {
            val w = width.toFloat()
            down(androidx.compose.ui.geometry.Offset(center(0, w), centerY))
            moveTo(androidx.compose.ui.geometry.Offset(center(1, w), centerY))
            moveTo(androidx.compose.ui.geometry.Offset(center(2, w), centerY))
            moveTo(androidx.compose.ui.geometry.Offset(center(4, w), centerY))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(4), picked)
    }

    @Test
    fun releasingOnTheCurrentSegmentChangesNothing() {
        show(selected = 2)
        compose.onNodeWithTag("seg-종류").performTouchInput {
            val w = width.toFloat()
            down(androidx.compose.ui.geometry.Offset(center(2, w), centerY))
            moveTo(androidx.compose.ui.geometry.Offset(center(4, w), centerY))
            moveTo(androidx.compose.ui.geometry.Offset(center(2, w), centerY))
            up()
        }
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), picked)
    }
}
