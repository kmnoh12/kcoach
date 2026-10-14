package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.kyungmin.kcoach.core.*
import dev.kyungmin.kcoach.phone.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class WorkoutSummaryUiTest {
    @get:Rule val compose = createComposeRule()
    private fun fixture(): WorkoutSummaryRead {
        val set = PlannedSetSnapshot(0, "dumbbell_curl", 0, 8, 10.0, true, exerciseName = "덤벨 컬", performedReps = 9)
        val snapshot = WorkoutSnapshot(sessionId = "fixture", routineId = "routine", exerciseIndex = 0, setIndex = 0,
            plannedSets = listOf(set), performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0,
            pausedRestRemainingMs = 0, paused = false, startedAtMs = 1000, pausedSinceMs = 0, totalPausedMs = 0,
            lastSequence = 0, lastCompletedSetKey = null, captureStates = emptyMap())
        return WorkoutSummaryRead(FinishedWorkoutStore.Evidence(snapshot, "팔 운동", 30, 0), null,
            WorkoutHeartSummary(emptyList(), null, null, true), null,
            JSONObject().put("active_kcal", JSONObject.NULL).put("reason", "프로필 입력 후 확인"),
            JSONObject().put("message", "이 기록에서 확인된 센서 원본이 없어요"))
    }
    @Test fun summaryDoesNotInventHeartOrCaloriesAndExercisesExpandAt390Dp() {
        compose.setContent { Column(Modifier.fillMaxSize().background(KColors.Bg).verticalScroll(rememberScrollState()).padding(20.dp)) { WorkoutSummaryContent(fixture()) } }
        compose.onNodeWithText("30분 · 1세트").assertIsDisplayed()
        compose.onNodeWithText("추정 활동 칼로리 · 프로필 입력 후 확인").assertIsDisplayed()
        compose.onNodeWithText("평균 심박 미측정 · 최고 미측정 bpm").assertIsDisplayed()
        compose.onNodeWithText("덤벨 컬").performScrollTo().performClick()
        compose.onNodeWithText("1세트 · 10.0 kg × 9회").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("이 기록에서 확인된 센서 원본이 없어요").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/workout_summary_missing.png")
    }
}
