package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w216dp-h216dp-round-xhdpi")
class WatchWorkoutPagesTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun measure(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 432, 432)
    }
    private fun shot(root: View, name: String) {
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        // A parent normally applies the root view's scroll translation when drawing it.
        val canvas = Canvas(bitmap).apply { translate(-root.scrollX.toFloat(), -root.scrollY.toFloat()) }
        root.draw(canvas)
        val file = File("build/outputs/watch-preview/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun update(phase: String = "WORKING", resting: Boolean = false) {
        val app = RuntimeEnvironment.getApplication()
        app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
            .putExtra("session", "preview").putExtra("setId", "set").putExtra("attemptId", "attempt")
            .putExtra("exerciseId", "curl").putExtra("exerciseName", "덤벨 컬")
            .putExtra("paired", true).putExtra("running", !resting).putExtra("resting", resting)
            .putExtra("targetReps", 20).putExtra("reps", 8).putExtra("heartRate", 112)
            .putExtra("restSeconds", 60).putExtra("restEndAtMs", System.currentTimeMillis() + 45_000)
            .putExtra("tempoEnabled", true).putExtra("tempoHint", "1회 3.2초 · 목표보다 느림")
            .putExtra("phase", phase))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun pagerOf(root: View) = descendants(root).filterIsInstance<WatchPager>().single()

    @Test fun theEditorAndHeartRatePagesSitAboveAndBelowTheCounter() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        update(); measure(root); shot(root, "working-primary")
        val pager = pagerOf(root)
        assertTrue(pager.topEnabled); assertTrue(pager.bottomEnabled)
        pager.show(-1); measure(root); shot(root, "working-edit")
        val finish = descendants(root).filterIsInstance<TextView>().single { it.text.endsWith("세트 마치기") || it.text == "횟수 입력" }
        assertTrue("The finish button must be on screen on the editor page", finish.getGlobalVisibleRect(android.graphics.Rect()))
        pager.show(1); measure(root); shot(root, "working-aux")
        update(); measure(root)
        assertEquals("New sensor values must not pull the user back to the counter", 1, pager.page)
        activity.finish()
    }

    @Test fun measuringShowsSetWeightAndModeOnOneLineAndOnlyTheHeartRateBelow() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val app = RuntimeEnvironment.getApplication()
        app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
            .putExtra("session", "preview").putExtra("setId", "set").putExtra("attemptId", "attempt")
            .putExtra("exerciseId", "curl").putExtra("exerciseName", "덤벨 컬").putExtra("setIndex", 0)
            .putExtra("paired", true).putExtra("running", true).putExtra("resting", false)
            .putExtra("targetReps", 12).putExtra("reps", 8).putExtra("heartRate", 112).putExtra("weightKg", 20.0)
            .putExtra("phase", "WORKING"))
        shadowOf(Looper.getMainLooper()).idle(); measure(root); shot(root, "measure-1b")
        val face = descendants(root).filterIsInstance<WatchWorkoutFace>().single()
        assertEquals("1세트 · 20kg · 자동 측정", face.subtitle.text.toString().removePrefix("\u200b"))
        val spanned = face.subtitle.text as android.text.Spanned
        val bright = spanned.getSpans(0, spanned.length, android.text.style.ForegroundColorSpan::class.java)
            .map { spanned.subSequence(spanned.getSpanStart(it), spanned.getSpanEnd(it)).toString() }
        assertEquals(listOf("1세트", "20kg"), bright)
        assertEquals("112", face.heartRate.text.toString()); assertEquals("bpm", face.bpm.text.toString())
        assertTrue(descendants(root).filterIsInstance<TextView>().none { it.text.toString() == "20kg" || it.text.toString() == "20" })
        activity.finish()
    }

    @Test fun restShowsTheYellowRingTheTimeTheTenSecondBarAndThePauseAndSkipButtons() {
        val app = RuntimeEnvironment.getApplication()
        WatchWorkoutOutbox(app).receivePhoneHello(listOf("1", "phone", "0", "WORKOUT_CONTROL_V1"))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        update("RESTING", resting = true); measure(root); shot(root, "rest-primary")
        val ring = descendants(root).single { it.javaClass.simpleName == "RestProgressRing" }
        assertTrue(ring.getGlobalVisibleRect(android.graphics.Rect()))
        val pager = pagerOf(root)
        assertTrue("Resting allows the editor above", pager.topEnabled)
        assertTrue("The heart-rate page is below during a rest too", pager.bottomEnabled)
        val pills = descendants(root).filterIsInstance<RestPill>()
        val pause = pills.single { it.contentDescription == "정지" }
        val skip = pills.single { it.contentDescription == "건너뛰기" }
        assertTrue(pause.getGlobalVisibleRect(android.graphics.Rect())); assertTrue(skip.getGlobalVisibleRect(android.graphics.Rect()))
        assertTrue("Dragging up no longer skips the rest", descendants(root).filterIsInstance<TextView>().none { it.text == "다음 세트 시작" })
        pager.show(-1); measure(root); shot(root, "rest-edit")
        // Dragging up during a rest opens the same heart-rate page as during a set.
        pager.show(1); measure(root); shot(root, "rest-aux")
        assertEquals(1, pager.page)
        assertTrue(descendants(root).filterIsInstance<TextView>().any { it.text.toString() == "112" })
        assertTrue("The heart page stays available during a rest", pager.bottomEnabled)
        activity.finish()
    }

    @Test fun pausingARestMorphsTheScreenAndResumingMorphsItBack() {
        val app = RuntimeEnvironment.getApplication()
        WatchWorkoutOutbox(app).receivePhoneHello(listOf("1", "phone", "0", "WORKOUT_CONTROL_V1"))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val face = descendants(root).filterIsInstance<WatchWorkoutFace>().single()
        fun state(resting: Boolean, restPaused: Boolean) {
            app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
                .putExtra("session", "preview").putExtra("setId", "set").putExtra("attemptId", "attempt")
                .putExtra("exerciseId", "curl").putExtra("exerciseName", "덤벨 컬").putExtra("paired", true)
                .putExtra("running", false).putExtra("resting", resting).putExtra("paused", restPaused)
                .putExtra("restPaused", restPaused).putExtra("pausedRestRemainingMs", if (restPaused) 45_000L else 0L)
                .putExtra("targetReps", 20).putExtra("reps", 8).putExtra("restSeconds", 90)
                .putExtra("restEndAtMs", if (restPaused) 0L else System.currentTimeMillis() + 45_000).putExtra("phase", if (restPaused) "PAUSED" else "RESTING"))
            shadowOf(Looper.getMainLooper()).idle()
        }
        state(resting = true, restPaused = false); measure(root)
        assertEquals(0f, face.restMorph, 0f)
        state(resting = false, restPaused = true)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600)); measure(root); shot(root, "rest-paused")
        assertEquals("The rest screen stays while paused", 1f, face.restMorph, 0.001f)
        assertEquals(1f, descendants(root).filterIsInstance<RestPill>().first { it.contentDescription == "계속" }.resume, 0.001f)
        assertEquals("0:45", face.value.text.toString())
        state(resting = true, restPaused = false)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600)); measure(root)
        assertEquals(0f, face.restMorph, 0.001f)
        assertTrue(descendants(root).filterIsInstance<RestPill>().any { it.contentDescription == "정지" })
        activity.finish()
    }

    @Test fun draggingMovesBetweenWholePagesAndAShortDragStaysPut() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        update(); measure(root)
        val pager = pagerOf(root)
        fun drag(fromY: Float, toY: Float) {
            val downAt = SystemClock.uptimeMillis()
            fun event(action: Int, y: Float) {
                val motion = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, 216f, y, 0)
                pager.dispatchTouchEvent(motion)
                motion.recycle()
            }
            event(MotionEvent.ACTION_DOWN, fromY)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300))
            event(MotionEvent.ACTION_MOVE, (fromY + toY) / 2)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(300))
            event(MotionEvent.ACTION_MOVE, toY)
            // Hold before releasing so this exercises dragging without a velocity fling.
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(800))
            event(MotionEvent.ACTION_UP, toY)
            shadowOf(Looper.getMainLooper()).idle()
        }
        drag(120f, 300f)                 // finger down: the editor above
        assertEquals(-1, pager.page)
        drag(300f, 120f)                 // finger up: back to the counter
        assertEquals(0, pager.page)
        drag(300f, 120f)                 // finger up: the heart-rate page below
        assertEquals(1, pager.page)
        drag(120f, 300f)
        assertEquals(0, pager.page)
        drag(300f, 270f)                 // a short drag does not leave the counter
        assertEquals(0, pager.page)
        activity.finish()
    }

    @Test fun aLongExerciseNameShrinksToOneLineInsteadOfBeingCutOff() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val app = RuntimeEnvironment.getApplication()
        app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
            .putExtra("session", "preview").putExtra("setId", "set").putExtra("attemptId", "attempt")
            .putExtra("exerciseId", "cable_external_rotation").putExtra("exerciseName", "케이블 익스터널 로테이션")
            .putExtra("paired", true).putExtra("running", true).putExtra("targetReps", 12).putExtra("reps", 8).putExtra("heartRate", 112)
            .putExtra("phase", "WORKING"))
        shadowOf(Looper.getMainLooper()).idle(); measure(root); shot(root, "working-long-name")
        val title = descendants(root).filterIsInstance<WatchWorkoutFace>().single().title
        assertEquals("케이블 익스터널 로테이션", title.text.toString())
        val layout = title.layout
        assertEquals("The long name must stay on one line", 1, layout.lineCount)
        assertEquals("No part of the name may be cut with an ellipsis", 0, layout.getEllipsisCount(0))
        assertTrue("The shrunk name still has to be readable", title.textSize >= 22f * (432f / 432f) * 0.9f)
        assertTrue("The title box must be tall enough for a full line", title.height >= title.textSize * 1.3f)
        activity.finish()
    }
}
