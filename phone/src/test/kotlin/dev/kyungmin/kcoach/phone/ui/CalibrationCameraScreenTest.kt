package dev.kyungmin.kcoach.phone.ui

import android.graphics.Color
import android.view.View
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kyungmin.kcoach.core.CameraCaptureGuide
import dev.kyungmin.kcoach.core.CameraPoseGuide
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class CalibrationCameraScreenTest {
    @get:Rule val compose = createComposeRule()
    private val guide = CameraCaptureGuide.forExercise("dumbbell_curl", true)
    private val waiting = CalibrationCameraUi(status = "회색 졸라맨에 맞춰 자세를 잡아 주세요", headline = guide.headline,
        startHint = guide.startHint, wearingSummary = "왼손 · 손등 쪽")

    private fun show(ui: CalibrationCameraUi, openSettings: () -> Unit = {}) = compose.setContent {
        val context = LocalContext.current
        val preview = remember { View(context).apply { setBackgroundColor(Color.rgb(40, 44, 44)) } }
        val overlay = remember { View(context) }
        CalibrationCameraScreen(ui, "덤벨 컬", CameraPoseGuide.familyFor("dumbbell_curl"), preview, overlay,
            back = {}, openSaved = {}, openWearingSettings = openSettings, stop = {}, retrySave = {}, requestPermission = {}, copyError = {})
    }

    @Test fun phoneAsksNothingAndHasNoStartButton() {
        var settings = false
        show(waiting) { settings = true }
        compose.onNodeWithText("왼쪽 측면 · 상체와 왼팔이 보이게").assertExists()
        compose.onNodeWithText("워치 왼손 · 손등 쪽").assertExists()
        assertTrue(compose.onAllNodesWithTextCount("촬영 시작") == 0)
        compose.onNodeWithText("설정에서 변경  ›").performClick()
        assertTrue(settings)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/camera_phone_waiting.png")
    }

    @Test fun readyStateCountsDownWithoutAButton() {
        show(waiting.copy(status = "자세 확인 · 곧 촬영을 시작해요", ready = true))
        compose.onNodeWithText("자세 확인 · 곧 촬영을 시작해요").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/camera_phone_ready.png")
    }

    @Test fun recordingShowsOnlyTheStopButton() {
        show(waiting.copy(status = "촬영 중 · 영상 추정 3회", recording = true, stopEnabled = true, recordingSinceMs = 0L))
        compose.onNodeWithText("촬영 종료 · 저장").assertExists()
        assertTrue(compose.onAllNodesWithTextCount("설정에서 변경  ›") == 0)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/camera_phone_recording.png")
    }

    @Test fun missingWearingPointsToSettings() {
        show(waiting.copy(status = "워치 착용 정보를 먼저 저장해 주세요", wearingSummary = null))
        compose.onNodeWithText("워치 착용 정보가 없어요").assertExists()
        compose.onNodeWithText("설정에서 저장  ›").assertExists()
    }

    @Test fun invalidCandidateWearingOffersBackNotSettings() {
        var left = false
        compose.setContent {
            val context = LocalContext.current
            CalibrationCameraScreen(waiting.copy(wearingSummary = null, wearingFromSettings = false, candidateWearingInvalid = true),
                "덤벨 컬", null, remember { View(context) }, remember { View(context) },
                back = { left = true }, openSaved = {}, openWearingSettings = {}, stop = {}, retrySave = {}, requestPermission = {}, copyError = {})
        }
        compose.onNodeWithText("후보의 워치 착용 정보가 올바르지 않아요").assertExists()
        assertTrue(compose.onAllNodesWithTextCount("설정에서") == 0)
        compose.onNodeWithText("뒤로 가서 후보 시험 다시 선택").performClick()
        assertTrue(left)
    }

    @Config(qualifiers = "w412dp-h660dp-xxhdpi") // about 10:16, like a wide foldable cover screen
    @Test fun wideCoverScreenOverlaysACompactPanel() {
        show(waiting)
        compose.onNodeWithText("워치 왼손 · 손등 쪽").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/camera_cover_waiting.png")
    }

    @Config(qualifiers = "w600dp-h800dp-xxhdpi") // 3:4, like an unfolded inner screen
    @Test fun fourByThreeScreenKeepsTheWholeFrame() {
        show(waiting.copy(status = "자세 확인 · 곧 촬영을 시작해요", ready = true))
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/camera_unfolded_ready.png")
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().size
}
