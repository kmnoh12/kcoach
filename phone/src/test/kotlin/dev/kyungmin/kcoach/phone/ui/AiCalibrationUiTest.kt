package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kyungmin.kcoach.phone.AiCalibrationUi
import org.json.JSONArray
import org.json.JSONObject
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
class AiCalibrationUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun savedCandidateIsPresentedAsAwaitingIndependentValidationAndOffersNoDirectApplyButton() {
        val result = JSONObject().put("actual_model", "Fixture model").put("answer", JSONObject().put("summary", "일치 기준의 새 후보를 만들었어요")
            .put("findings", JSONArray()).put("review_suggestions", JSONArray()).put("limitations", JSONArray(listOf("학습 원본 비교만 완료했어요")))
            .put("candidate_proposals", JSONArray().put(JSONObject().put("reason", "기존과 같은 원본으로 후보를 비교했어요"))))
            .put("candidates", JSONArray().put(JSONObject().put("id", "candidate").put("version", 2)
                .put("training_replay", JSONObject().put("count_mae", .5))))
            .put("timings_ms", JSONObject().put("total", 1200))
        var back = 0
        compose.setContent {
            Column(Modifier.fillMaxSize().background(KColors.Bg).verticalScroll(rememberScrollState())) {
                AiCalibrationContent(AiCalibrationUi("account", "base", loaded = true, resultJson = result.toString(),
                    message = "보정 후보를 저장했어요 · 독립 검증 대기"), "Fixture model", false, {}, {}, {}, { back++ })
            }
        }
        compose.onNodeWithText("새 후보 · 버전 2 · 독립 검증 대기").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("적용 완료").assertDoesNotExist()
        compose.onNodeWithText("후보·검증·적용 상태로 돌아가기").performScrollTo().performClick(); assertEquals(1, back)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/ai_calibration_pending.png")
    }
    @Test fun anActiveRequestCanBeCancelledWithoutPresentingAnUnfinishedProposal() {
        var cancel = 0
        compose.setContent {
            Column(Modifier.fillMaxSize().background(KColors.Bg)) {
                AiCalibrationContent(AiCalibrationUi("account", "base", loaded = true, busy = true,
                    message = "GPT가 보정 후보를 검토하고 있어요"), "Fixture model", false, {}, { cancel++ }, {}, {})
            }
        }
        compose.onNodeWithText("GPT 보정 도움").assertIsDisplayed()
        compose.onNodeWithText("보정 도움 요청 취소").performClick(); assertEquals(1, cancel)
        compose.waitForIdle()
        compose.onNodeWithText("GPT 보정 도움").assertIsDisplayed()
        compose.onNodeWithText("확인한 원본으로 보정 도움 요청").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/ai_calibration_running.png")
    }
}
