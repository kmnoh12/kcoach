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
import dev.kyungmin.kcoach.phone.*
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
class WorkoutReviewUiTest {
    @get:Rule val compose = createComposeRule()
    private fun ui(): WorkoutReviewUi {
        val metric = ReviewMetric("metric:exercise:press", "press", null,
            JSONObject().put("normal_sets", 3).put("normal_reps", 25).put("normal_volume_kg_reps", 1000))
        val context = WorkoutReviewContext("profile", "session", "{}", "hash", setOf("session:session"),
            mapOf("press" to "프레스"), mapOf("set-0" to "press"), mapOf(metric.id to metric))
        val evidence = JSONArray(listOf("session:session")); val refs = JSONArray(listOf(metric.id))
        val answer = JSONObject().put("summary", "같은 중량에서 마지막 세트의 반복이 늘었어요")
            .put("overview", "일반 세트의 총 반복은 24회에서 25회로 늘었어요. 세트 수는 같아요.")
            .put("findings", JSONArray().put(JSONObject().put("text", "훈련량 변화는 근육 성장률을 뜻하지 않아요").put("evidence_ids", evidence)))
            .put("exercise_analyses", JSONArray().put(JSONObject().put("exercise_id", "press").put("status", "partial")
                .put("headline", "같은 중량 · 반복 합계 증가").put("metric_refs", refs)
                .put("observations", JSONArray().put(JSONObject().put("kind", "observed").put("text", "수동 입력한 반복을 기준으로 비교했어요")
                    .put("metric_refs", refs).put("evidence_ids", evidence)))
                .put("highlighted_set_ids", JSONArray()).put("limitations", JSONArray(listOf("센서 분석은 아직 없어요")))))
            .put("next_targets", JSONArray().put(JSONObject().put("exercise_id", "press").put("target", "다음 운동도 같은 중량으로 반복을 유지해 보세요")
                .put("reason", "노력 기록을 확인한 뒤 증량을 정해요").put("evidence_ids", evidence)))
            .put("coverage", JSONObject().put("evidence_ids", evidence).put("limitations", JSONArray(listOf("이번 세션은 카메라 관측이 없어요"))))
            .put("limitations", JSONArray()).put("memory_proposals", JSONArray())
        return WorkoutReviewUi("account", "session", loaded = true, context = context, answerJson = answer.toString(), model = "Fixture model", message = "리뷰를 저장했어요")
    }
    @Test fun completedReviewExpandsAppComputedMetricsAndKeepsActionsSeparateFromRecordChanges() {
        var requests = 0
        compose.setContent {
            Column(Modifier.fillMaxSize().background(KColors.Bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                WorkoutReviewContent(ui(), true, "Fixture model", { requests++ }, {}, {}, {})
            }
        }
        compose.onNodeWithText("같은 중량 · 반복 합계 증가").performScrollTo().performClick()
        compose.onNodeWithText("일반 세트 반복 합계 · 25").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("일반 세트 볼륨 kg·회 · 1000").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("운동 리뷰 요청").performScrollTo().performClick()
        assertEquals(1, requests)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/workout_review_complete.png")
    }
    @Test fun inFlightReviewOffersCancellationAndNoPrematureAnswer() {
        var cancels = 0
        compose.setContent {
            Column(Modifier.fillMaxSize().background(KColors.Bg).padding(20.dp)) {
                WorkoutReviewContent(WorkoutReviewUi("account", "session", loaded = true, busy = true,
                    message = "AI 코치가 운동 리뷰를 정리하고 있어요"), false, "Fixture model", {}, { cancels++ }, {}, {})
            }
        }
        compose.onNodeWithText("리뷰 요청 취소").performClick(); assertEquals(1, cancels)
        compose.onNodeWithText("운동 리뷰 요청").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/workout_review_running.png")
    }
}
