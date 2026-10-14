package dev.kyungmin.kcoach.phone.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class AiCoachUiTest {
    @get:Rule val compose = createComposeRule()
    private val account = ChatGptConnectionUi(connected = true, activeProfileId = "fixture-account", planAllowed = true)
    private val model = ChatGptModelSettingsUi(loaded = true, models = listOf(
        ChatGptModelOption("fixture-model", "Fixture model", listOf(ChatGptReasoningOption("high")), "high", true)),
        selectedSlug = "fixture-model", selectedEffort = "high")
    private fun actions(save: (AiCoachProfile) -> Unit = {}, link: (Set<String>) -> Unit = {}, send: (String) -> Unit = {},
        cancel: () -> Unit = {}) = AiCoachActions({}, {}, save, link, send, cancel)

    @Test fun menuHasASmallCoachEntryAndDoesNotStartAnInference() {
        var opened = 0
        compose.setContent { MenuScreen("0.7.4", MenuActions({}, { null }, {}, {}, { opened++ })) }
        compose.onNodeWithText("운동 상담").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test fun savingAnEmptyProfileDoesNotInventBodyMeasurements() {
        var saved: AiCoachProfile? = null
        compose.setContent { AiCoachScreen(AiCoachUi("fixture-account", loaded = true), account, model,
            actions(save = { saved = it })) }
        compose.onNodeWithText("프로필 입력").performClick()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/ai-coach-profile.png")
        compose.onNodeWithText("저장").performClick()
        assertNotNull(saved)
        assertNull(saved!!.heightCm); assertNull(saved!!.weightKg); assertNull(saved!!.bodyFatPercent)
        assertEquals("", saved!!.weightMeasuredOn)
        assertEquals("", saved!!.goal)
    }

    @Test fun identityOnlyCannotSendAQuestionAndDoesNotClaimUsageConsent() {
        compose.setContent { AiCoachScreen(AiCoachUi("fixture-account", loaded = true, profile = AiCoachProfile()),
            account.copy(planAllowed = false), model, actions(send = { fail("Consent gate") })) }
        compose.onNodeWithText("운동 질문").performTextInput("다음 운동 목표는?")
        compose.onNodeWithText("전송").assertIsNotEnabled()
        compose.onNodeWithText("상담을 요청하려면 설정에서 구독 한도 사용을 허용해 주세요").assertExists()
    }

    @Test fun linkedRecordsAreReadOnlyAndOnlyNewSelectionsAreLinked() {
        var linked = emptySet<String>()
        val ui = AiCoachUi("fixture-account", loaded = true, profile = AiCoachProfile(), records = listOf(
            AiCoachRecord("available", "오늘 루틴", 1000, false), AiCoachRecord("existing", "어제 루틴", 0, true)))
        compose.setContent { AiCoachScreen(ui, account, model, actions(link = { linked = it })) }
        compose.onNodeWithText("운동 기록 연결").performClick()
        compose.onAllNodes(isToggleable())[1].assertIsNotEnabled()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onNodeWithText("선택한 기록 연결").performClick()
        assertEquals(setOf("available"), linked)
    }

    @Test fun aFailedRequestRestoresItsQuestionInsteadOfLosingTheDraft() {
        val previous = AiCoachTurn("컬 반복이 줄었는데?", "예전 답변", emptyList(), "fixture-model", "fixture-model",
            "high", "previous-response", 0, "{}")
        val ui = mutableStateOf(AiCoachUi("fixture-account", loaded = true, profile = AiCoachProfile(), turns = listOf(previous)))
        var sent = ""
        compose.setContent { AiCoachScreen(ui.value, account, model, actions(send = { sent = it })) }
        compose.onNodeWithText("운동 질문").performTextInput("컬 반복이 줄었는데?")
        compose.onNodeWithText("전송").performClick()
        assertEquals("컬 반복이 줄었는데?", sent)
        compose.runOnIdle { ui.value = ui.value.copy(busy = true, pendingQuestion = sent, message = "답변 생성 중") }
        compose.waitForIdle()
        compose.onNodeWithText("상담 요청 취소").assertExists()
        compose.runOnIdle { ui.value = ui.value.copy(busy = false, pendingQuestion = "", message = "연결이 끊겼어요") }
        compose.waitForIdle()
        compose.onAllNodesWithText("컬 반복이 줄었는데?").assertCountEquals(2)
        compose.onNodeWithText("전송").assertIsEnabled()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/ai-coach-retry.png")
    }

    @Test fun completedAnswersExposeTheirHistoricalEvidenceAndHideOtherAccountContent() {
        val context = """{"profile":{},"records":[{"evidence_id":"session:fixture","started_at_ms":1000,"title":"팔 루틴","completed_sets":[{"exercise_name":"덤벨 컬","set_index":0,"weight_kg":12.0,"performed_reps":10}]}]}"""
        val turn = AiCoachTurn("오늘 컬 어때?", "기록을 기준으로 다음에는 반복을 유지해 보세요.", listOf("session:fixture"),
            "fixture-model", "fixture-model", "high", "fixture-response", 1000, context)
        val ui = AiCoachUi("fixture-account", loaded = true, profile = AiCoachProfile(), turns = listOf(turn))
        val connection = mutableStateOf(account)
        compose.setContent { AiCoachScreen(ui, connection.value, model, actions()) }
        compose.onNodeWithText(turn.answer).assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/ai-coach-answer.png")
        compose.onNodeWithText("근거 1개").performClick()
        compose.onNodeWithText("닫기").performClick()
        compose.runOnIdle { connection.value = account.copy(activeProfileId = "other-account") }
        compose.onNodeWithText(turn.answer).assertDoesNotExist()
        compose.onNodeWithText("오늘 컬 어때?").assertDoesNotExist()
    }
}
