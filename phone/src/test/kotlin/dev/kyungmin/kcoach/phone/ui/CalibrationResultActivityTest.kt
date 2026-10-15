package dev.kyungmin.kcoach.phone.ui

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.kyungmin.kcoach.phone.CalibrationCaptureStore
import dev.kyungmin.kcoach.phone.CalibrationResultActivity
import dev.kyungmin.kcoach.phone.CalibrationReviewStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real load/save/recreation wiring; VideoView decoding still needs a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalibrationResultActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun intent(id: String) = Intent(app, CalibrationResultActivity::class.java).putExtra("captureId", id)
    private fun awaitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun missingCaptureShowsRetryAndCanReturnToTheList() {
        ActivityScenario.launch<CalibrationResultActivity>(intent("paired-missing")).use {
            awaitText("촬영 자료를 읽지 못했어요. 다시 시도해 주세요.")
            compose.onNodeWithText("아직 촬영 자료가 없어요.").assertDoesNotExist()
            compose.onNodeWithText("다시 불러오기").performClick()
            awaitText("촬영 자료를 읽지 못했어요. 다시 시도해 주세요.")
            compose.onNodeWithText("뒤로").performClick()
            awaitText("아직 촬영 자료가 없어요.")
        }
    }

    @Test fun countPersistsWithoutLabelsAndListSelectionSurvivesRecreation() {
        val captures = CalibrationCaptureStore(app)
        val capture = captures.begin("dumbbell_curl", "테스트 컬", true, "phone").getOrThrow()
        captures.finish(capture.id, false, "fixture without video").getOrThrow()
        ActivityScenario.launch<CalibrationResultActivity>(intent(capture.id)).use { scenario ->
            awaitText("10회 했나요?")
            compose.onNodeWithText("맞아요").performScrollTo().performClick()
            awaitText("확인한 횟수")
            val saved = CalibrationReviewStore(app).load(capture.id).getOrThrow()
            assertEquals(10, saved.countReference!!.reps)
            assertTrue(saved.references.isEmpty())
            assertTrue(CalibrationReviewStore(app).cameraCycles(capture.id).getOrThrow().isEmpty())
            scenario.recreate()
            awaitText("확인한 횟수")
            compose.onNodeWithText("뒤로").performScrollTo().performClick()
            awaitText("저장한 촬영")
            scenario.recreate()
            awaitText("저장한 촬영")
            compose.onNodeWithText("10회 했나요?").assertDoesNotExist()
        }
    }
}
