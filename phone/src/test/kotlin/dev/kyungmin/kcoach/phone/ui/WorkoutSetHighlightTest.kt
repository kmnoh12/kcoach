package dev.kyungmin.kcoach.phone.ui

import android.view.View
import android.graphics.BitmapFactory
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil
import kotlin.math.floor

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class WorkoutSetHighlightTest {
    @get:Rule val compose = createComposeRule()

    private fun show(preview: Boolean, resting: Boolean = false) {
        val now = System.currentTimeMillis()
        val sets = listOf(
            SetUi(1, "1", "10", 8, !preview, false, "", 60, false),
            SetUi(2, "2", "10", 8, false, false, "", 60, false),
        )
        val model = WorkoutUiModel("팔 루틴", preview, false, now, 0, 0, 0,
            listOf(ExerciseUi("dumbbell_curl", "덤벨 컬", "이두", true, 60, 16, 0,
                if (preview) 0 else 1, 2, if (preview) 0 else 1, sets, 0, 160, null)), false,
            if (resting) now else 0, if (resting) now + 60_000 else 0, 0, null,
            restingSet = if (resting) 0 to 0 else null)
        val actions = java.lang.reflect.Proxy.newProxyInstance(WorkoutActions::class.java.classLoader,
            arrayOf(WorkoutActions::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NoActions"
                else -> null
            }
        } as WorkoutActions
        compose.setContent {
            val context = LocalContext.current
            ActiveWorkoutScreen(model, actions, LiveLine("워치 연결됨", "title"), LiveLine("", "telemetry")) { View(context) }
        }
    }

    /** Read the rendered row's accent (bone) pixels, including its set number and current-set marker. */
    private fun mintPixelsInSetRow(label: String): Int {
        val cell = compose.onNode(hasTextExactly(label) and hasClickAction())
        cell.performScrollTo().assertIsDisplayed()
        val bounds = cell.fetchSemanticsNode().boundsInRoot
        val screenshot = File("build/outputs/roborazzi/workout-set-row-$label.png")
        compose.onRoot().captureRoboImage(screenshot.path,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val pixels = requireNotNull(BitmapFactory.decodeFile(screenshot.absolutePath))
        screenshot.delete()
        var mint = 0
        for (y in floor(bounds.top).toInt().coerceAtLeast(0) until ceil(bounds.bottom).toInt().coerceAtMost(pixels.height)) {
            for (x in 0 until pixels.width) {
                val color = pixels.getPixel(x, y)
                val red = (color shr 16) and 255
                val green = (color shr 8) and 255
                val blue = color and 255
                // The current-set marker is the bone accent (about 237, 232, 220); plain text is a cooler white.
                if (red >= 225 && green >= 215 && blue <= 228 && red - blue >= 14) mint++
            }
        }
        pixels.recycle()
        return mint
    }

    @Test fun previewHasNoMintCurrentSetBeforeWorkoutStart() {
        show(preview = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/workout-preview-set-highlight.png")
        assertEquals("Browsing a routine has no current workout set", 0, mintPixelsInSetRow("1"))
        assertEquals(0, mintPixelsInSetRow("2"))
        compose.onNodeWithText("운동 시작 전 · 총 2세트").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("현재 1세트 · 완료 0/2세트").assertDoesNotExist()
    }

    @Test fun onlyTheWorkingSetIsMintAndPreviousCompletionUsesANeutralCheck() {
        show(preview = false)
        assertEquals(0, mintPixelsInSetRow("1"))
        assertTrue(mintPixelsInSetRow("2") > 0)
    }

    @Test fun restHighlightsTheJustCompletedSetInsteadOfTheNextSet() {
        show(preview = false, resting = true)
        assertTrue(mintPixelsInSetRow("1") > 0)
        assertEquals(0, mintPixelsInSetRow("2"))
        compose.onNodeWithText("1세트 후 휴식 · 완료 1/2세트").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("현재 2세트 · 완료 1/2세트").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/workout-rest-set-highlight.png")
    }
}
