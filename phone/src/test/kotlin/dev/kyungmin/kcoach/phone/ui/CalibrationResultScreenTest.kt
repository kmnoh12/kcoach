package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class CalibrationResultScreenTest {
    @get:Rule val compose = createComposeRule()
    private val clip = CapturePlaybackUi("fixture", "케이블 외회전", null, 24_000,
        (0..60).map { it*400 to (.5f + .2f*kotlin.math.sin(it.toFloat())) }, listOf(1000 to 3000))
    @Test fun onlyTheCountIsConfirmedAndResearchToolsStayHidden() {
        var answer = -1; var research = 0
        compose.setContent { CalibrationResultScreen(clip, emptyList(), false, {}, { answer = it }, {}, { research++ }) }
        compose.onNodeWithText("10회 했나요?").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/capture-count-initial.png")
        compose.onNodeWithText("맞아요").performScrollTo().performClick(); assertEquals(10,answer)
        compose.onNodeWithText("확인한 구간 저장").assertDoesNotExist()
        compose.onNodeWithText("연구 모드 · 상세 확인").assertDoesNotExist()
        compose.onNodeWithText("기타").performScrollTo().performClick()
        compose.onNodeWithText("실제 횟수").performTextInput("12")
        compose.onNodeWithText("횟수 저장").performScrollTo().performClick(); assertEquals(12,answer)
        assertEquals(0,research)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/capture-count-confirmation.png")
    }
    @Test fun playbackUsesTheSelectedVideoAndPreservesPauseAcrossBackgrounding() {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            override val lifecycle = androidx.lifecycle.LifecycleRegistry(this).apply {
                currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            }
        }
        val selected = androidx.compose.runtime.mutableStateOf(clip.copy(videoPath = "/fixture/first.mp4"))
        lateinit var root: android.view.View
        fun video(view: android.view.View): android.widget.VideoView? {
            if (view is android.widget.VideoView) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) video(view.getChildAt(i))?.let { return it }
            return null
        }
        compose.setContent {
            root = androidx.compose.ui.platform.LocalView.current.rootView
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                CalibrationResultScreen(selected.value, emptyList(), false, {}, {}, {}, {})
            }
        }
        lateinit var first: org.robolectric.shadows.ShadowVideoView
        compose.runOnIdle {
            first = org.robolectric.Shadows.shadowOf(requireNotNull(video(root)))
            assertEquals("file:///fixture/first.mp4", first.videoURIString)
            first.onPreparedListener.onPrepared(android.media.MediaPlayer())
            assertEquals(org.robolectric.shadows.ShadowVideoView.START, first.currentVideoState)
            owner.lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
            assertEquals(org.robolectric.shadows.ShadowVideoView.PAUSE, first.currentVideoState)
            owner.lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
            assertEquals(org.robolectric.shadows.ShadowVideoView.START, first.currentVideoState)
        }
        compose.onNodeWithText("일시정지").performScrollTo().performClick()
        compose.runOnIdle {
            owner.lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
            owner.lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
            assertEquals(org.robolectric.shadows.ShadowVideoView.PAUSE, first.currentVideoState)
            selected.value = clip.copy(id = "second", videoPath = "/fixture/second.mp4")
        }
        compose.runOnIdle {
            assertEquals(org.robolectric.shadows.ShadowVideoView.STOP, first.currentVideoState)
            val second = org.robolectric.Shadows.shadowOf(requireNotNull(video(root)))
            assertEquals("file:///fixture/second.mp4", second.videoURIString)
            second.onErrorListener.onError(android.media.MediaPlayer(), 1, 0)
        }
        compose.onNodeWithText("영상을 재생하지 못했어요. 촬영 자료를 다시 열어 주세요.").assertExists()
    }

    @Test fun loadingAndReadFailureAreNotShownAsAnEmptyCaptureList() {
        var retry = 0
        val loading = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { CalibrationResultScreen(null, emptyList(), false, {}, {}, {}, {},
            loading = loading.value, error = if (loading.value) null else "촬영 자료를 읽지 못했어요. 다시 시도해 주세요.", retry = { retry++ }) }
        compose.onNodeWithText("촬영 자료를 불러오는 중…").assertExists()
        compose.onNodeWithText("아직 촬영 자료가 없어요.").assertDoesNotExist()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithText("아직 촬영 자료가 없어요.").assertDoesNotExist()
        compose.onNodeWithText("다시 불러오기").performClick()
        assertEquals(1, retry)
    }

    @Test fun mismatchOffersReviewButNeverOpensItWithoutAUserAction() {
        var research = 0
        compose.setContent { CalibrationResultScreen(clip.copy(confirmedCount = 10, watchCount = 9), emptyList(), false, {}, {}, {}, { research++ }) }
        assertEquals(0,research)
        compose.onNodeWithText("반복 구간 표시").performScrollTo().performClick()
        assertEquals(1,research)
    }

    private val rows = listOf(
        CaptureRowUi("a", "케이블 외회전", "10월 15일 오후 11:02 · 10회 확인"),
        CaptureRowUi("b", "케이블 외회전", "10월 15일 오후 10:41 · 횟수 확인 전"),
        CaptureRowUi("c", "바벨 바이셉 컬", "10월 14일 오후 9:12 · 중단됨"))

    @Test fun listShowsDateAndStateAndLongPressStartsSelectionWithABottomDeleteBar() {
        var deleted: Set<String>? = null; var opened: String? = null
        compose.setContent { CalibrationResultScreen(null, rows, false, { opened = it }, {}, {}, {}, deleteClips = { deleted = it }) }
        compose.onNodeWithText("10월 15일 오후 10:41 · 횟수 확인 전").assertExists()
        compose.onNodeWithContentDescription("뒤로").assertExists()
        compose.onNodeWithText("바벨 바이셉 컬").performClick(); assertEquals("c", opened)
        compose.onNodeWithText("10월 15일 오후 11:02 · 10회 확인").performTouchInput { longClick() }
        compose.onNodeWithText("1개 선택됨").assertExists()
        compose.onNodeWithText("삭제 · 1개").assertExists()
        compose.onNodeWithText("10월 15일 오후 10:41 · 횟수 확인 전").performClick()
        compose.onNodeWithText("2개 선택됨").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/capture-list-selecting.png")
        compose.onNodeWithText("전체").performClick()
        compose.onNodeWithText("3개 선택됨").assertExists()
        compose.onNodeWithText("전체").performClick()
        compose.onNodeWithText("촬영을 선택해 주세요").assertExists()
        compose.onNodeWithText("전체").performClick()
        compose.onNodeWithText("삭제 · 3개").performClick()
        compose.onNodeWithText("3개 촬영을 삭제할까요?").assertExists()
        assertNull(deleted)
        compose.onNodeWithText("삭제", useUnmergedTree = true).performClick()
        assertEquals(setOf("a", "b", "c"), deleted)
        compose.onNodeWithText("3개 선택됨").assertDoesNotExist()
    }

    @Test fun cancelLeavesSelectionModeWithoutDeleting() {
        var deleted: Set<String>? = null
        compose.setContent { CalibrationResultScreen(null, rows, false, {}, {}, {}, {}, deleteClips = { deleted = it }) }
        compose.onNodeWithText("바벨 바이셉 컬").performTouchInput { longClick() }
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithText("1개 선택됨").assertDoesNotExist()
        compose.onNodeWithText("저장한 촬영").assertExists()
        assertNull(deleted)
    }

    @Test fun detailHasABackArrowAnExerciseSpecificGraphTitleAndAConfirmedDelete() {
        var back = 0; var deleted = 0
        val shown = clip.copy(graphTitle = "영상 속 팔뚝 방향", confirmedCount = 11, watchCount = 10,
            message = "11회로 저장했어요. 워치는 10회로 세서 서로 달라요. 어디서 갈렸는지 반복 구간을 표시해 주세요.")
        compose.setContent { CalibrationResultScreen(shown, rows, false, {}, {}, { back++ }, {}, deleteClip = { deleted++ }) }
        compose.onNodeWithText("영상 속 팔뚝 방향").performScrollTo().assertExists()
        compose.onNodeWithText("영상 속 손목 높이").assertDoesNotExist()
        compose.onNodeWithText("11회로 저장했어요. 워치는 10회로 세서 서로 달라요. 어디서 갈렸는지 반복 구간을 표시해 주세요.").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/capture-detail-mismatch.png")
        compose.onNodeWithContentDescription("뒤로").performClick(); assertEquals(1, back)
        compose.onNodeWithContentDescription("삭제").performClick()
        compose.onNodeWithText("이 촬영을 삭제할까요?").assertExists()
        compose.onNodeWithText("취소").performClick(); assertEquals(0, deleted)
        compose.onNodeWithContentDescription("삭제").performClick()
        compose.onNodeWithText("삭제", useUnmergedTree = true).performClick(); assertEquals(1, deleted)
    }
}
