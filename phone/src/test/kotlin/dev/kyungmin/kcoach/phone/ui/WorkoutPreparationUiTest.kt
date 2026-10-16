package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.LocalContext
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kyungmin.kcoach.phone.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class WorkoutPreparationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun startPanelShowsWearingAndNoPostureQuestions() {
        var opened = false
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                WorkoutPreparationPanel(WorkoutWearingSelection("RIGHT", "INSIDE"), "워치 연결됨", false) { opened = true }
            }
        }
        compose.onNodeWithText("오른손 · 손바닥 쪽").assertExists()
        compose.onNodeWithText("이 방식").assertDoesNotExist()
        compose.onNodeWithText("프로필·착용 설정").performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test fun postureSettingsDefaultToUnknownAndSaveChoices() {
        var answers by mutableStateOf<Map<String, String>>(emptyMap())
        compose.setContent {
            WorkoutPostureScreen(answers, {}) { id, value -> answers = answers + (id to value) }
        }
        compose.onNodeWithTag("posture:external_rotation:UNKNOWN").assertIsSelected()
        compose.onNodeWithTag("posture:external_rotation:OTHER").performScrollTo().performClick().assertIsSelected()
        assertEquals("OTHER", answers["external_rotation"])
    }

    @Test fun preparationLivesInTheRoutineAndStartNeedsNoSecondSheetOrAnswer() {
        var started = false
        compose.setContent {
            RoutineDetailScreen("등 루틴", emptyList(), "시작", KIcon.Play, false,
                RoutineDetailActions({}, {}, {}, {}, { _, _ -> }, {}, { started = true }, {}),
                preparationContent = {
                    WorkoutPreparationPanel(null, "워치 연결 안 됨", false) {}
                })
        }
        compose.onNodeWithText("운동 준비").assertExists()
        compose.onNodeWithText("착용 정보 미설정 · 개인 보정 없이 시작해요").assertExists()
        compose.onNodeWithText("이 방식").assertDoesNotExist()
        compose.onNodeWithText("시작").performClick()
        assertTrue(started)
        compose.onNodeWithText("이 착용 상태로 시작").assertDoesNotExist()
    }

    @Test fun aShoulderPreparationKeepsStartVisible() {
        val routine = WorkoutData.defaultRoutines().first { it.exercises.any { row -> row.exercise.id == "external_rotation" } }
        compose.setContent {
            val context = LocalContext.current
            RoutineDetailScreen(routine.name, routine.exercises.mapIndexed { i, row ->
                RoutineRowUi(i, row.exercise.name, "${row.sets.size}세트", { android.view.View(context) })
            }, "시작", KIcon.Play, false,
                RoutineDetailActions({}, {}, {}, {}, { _, _ -> }, {}, {}, {}),
                preparationContent = {
                    WorkoutPreparationPanel(WorkoutWearingSelection("LEFT", "OUTSIDE"), "워치 연결됨", false) {}
                })
        }
        compose.onNodeWithText("시작").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/routine-preparation-shoulder.png")
    }
}
