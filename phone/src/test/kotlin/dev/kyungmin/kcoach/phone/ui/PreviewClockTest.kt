package dev.kyungmin.kcoach.phone.ui

import android.view.View
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class PreviewClockTest {
    @get:Rule val compose = createComposeRule()

    private fun show(preview: Boolean, paused: Boolean = false) {
        val now = System.currentTimeMillis()
        val model = WorkoutUiModel("어깨전문", preview, paused, now - 33_000, 0, if (paused) now else 0,
            0, listOf(ExerciseUi("external_rotation", "케이블 익스터널 로테이션", "어깨", true,
                45, 50, 0, 0, 2, 0, emptyList(), 0, 250, null)), false, 0, 0, 0, null)
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
            ActiveWorkoutScreen(model, actions, LiveLine("Watch8 연결됨", "t"), LiveLine("운동 대기 중", "t")) { View(context) }
        }
    }

    @Test fun browsingExerciseDoesNotRunWorkoutClock() {
        show(preview = true)
        compose.onNodeWithText("00:00").assertExists()
        compose.mainClock.advanceTimeBy(25_000)
        compose.onNodeWithText("00:00").assertExists()
    }

    @Test fun anActualPausedWorkoutKeepsItsElapsedTime() {
        show(preview = false, paused = true)
        compose.onNodeWithText("00:33").assertExists()
    }
}
