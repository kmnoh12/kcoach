package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
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

    @Test fun additionalControlsUseTheWholeViewportAndTelemetryKeepsTheSelectedPage() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        update(); measure(root); shot(root, "working-primary")
        val scroll = descendants(root).filterIsInstance<ScrollView>().single()
        scroll.scrollTo(0, 432); shot(root, "working-secondary")
        assertEquals("The full watch, rather than a small lower strip, must scroll", root.height, scroll.height)
        assertEquals(432, scroll.scrollY)
        update(); measure(root)
        assertEquals("New sensor values must not pull the user back to the counter", 432, scroll.scrollY)
        val details = descendants(root).filterIsInstance<TextView>().single { it.text.startsWith("세트 마치기") }
        val visible = android.graphics.Rect()
        assertTrue("Manual action must be visible after switching to controls", details.getGlobalVisibleRect(visible))
        assertTrue(visible.height() > 0)
        activity.finish()
    }

    @Test fun restRingLeavesTheControlsPageAndNextSetActionRemainsVisible() {
        val app = RuntimeEnvironment.getApplication()
        WatchWorkoutOutbox(app).receivePhoneHello(listOf("1", "phone", "0", "WORKOUT_CONTROL_V1"))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        update("RESTING", resting = true); measure(root); shot(root, "rest-primary")
        val ring = descendants(root).single { it.javaClass.simpleName == "RestProgressRing" }
        assertTrue(ring.getGlobalVisibleRect(android.graphics.Rect()))
        val scroll = descendants(root).filterIsInstance<ScrollView>().single()
        scroll.scrollTo(0, 432); shot(root, "rest-secondary")
        assertFalse("The rest ring must leave with the counter, not cover controls", ring.getGlobalVisibleRect(android.graphics.Rect()))
        val next = descendants(root).filterIsInstance<TextView>().single { it.text == "휴식 끝내고 다음 세트" }
        assertTrue(next.getGlobalVisibleRect(android.graphics.Rect()))
        activity.finish()
    }

    @Test fun aFlingSwitchesWholePagesInsteadOfScrollingASmallLowerPanel() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        update(); measure(root)
        val pages = descendants(root).filterIsInstance<WorkoutPagesView>().single()
        pages.fling(500)
        repeat(30) { org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(10)); pages.computeScroll() }
        assertEquals(432, pages.scrollY)
        pages.fling(-500)
        repeat(30) { org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(10)); pages.computeScroll() }
        assertEquals(0, pages.scrollY)
        activity.finish()
    }

    @Test fun aSlowDragAlsoSettlesOnAWholePage() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        update(); measure(root)
        val pages = descendants(root).filterIsInstance<WorkoutPagesView>().single()
        fun drag(fromY: Float, toY: Float) {
            val downAt = SystemClock.uptimeMillis()
            fun event(action: Int, y: Float) {
                val motion = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, 216f, y, 0)
                pages.dispatchTouchEvent(motion)
                motion.recycle()
            }
            event(MotionEvent.ACTION_DOWN, fromY)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500))
            event(MotionEvent.ACTION_MOVE, (fromY + toY) / 2)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500))
            event(MotionEvent.ACTION_MOVE, toY)
            // Hold before releasing so this exercises dragging without a velocity fling.
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1000))
            event(MotionEvent.ACTION_UP, toY)
            repeat(30) { org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(10)); pages.computeScroll() }
        }
        drag(300f, 180f)
        assertEquals(432, pages.scrollY)
        drag(180f, 300f)
        assertEquals(0, pages.scrollY)
        activity.finish()
    }
}
