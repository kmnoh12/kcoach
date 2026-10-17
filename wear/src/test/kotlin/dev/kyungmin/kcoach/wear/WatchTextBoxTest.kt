package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * No line of text may sit in a box shorter than the text itself, at any screen density, on any screen: the workout
 * pages, the set-done and record screens, calibration and the workout result, with long names and extreme values.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchTextBoxTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun check(side: Int) {
        val app = RuntimeEnvironment.getApplication()
        WatchWorkoutOutbox(app).receivePhoneHello(listOf("1", "phone", "0", "WORKOUT_CONTROL_V1"))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val pager = descendants(root).filterIsInstance<WatchPager>().single()
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, side, side)
        }
        fun verify(label: String) {
            layout()
            val boxes = descendants(root).filterIsInstance<TextView>().filter { it.text.isNotEmpty() && it.isShown }
            assertTrue("$label: nothing to check", boxes.isNotEmpty())
            boxes.forEach {
                assertTrue("$label: '${it.text}' (${it.textSize}px) sits in a ${it.height}px box at $side px", it.height >= it.textSize * 1.3f)
                // The box must also hold the glyphs: from the top of the box to the baseline and the descent below it.
                assertTrue("$label: '${it.text}' is cut at the bottom at $side px", it.baseline + it.paint.descent() <= it.height + 0.5f)
                assertTrue("$label: '${it.text}' is cut at the top at $side px", it.baseline + it.paint.ascent() >= -0.5f)
            }
            for (page in listOf(-1, 1)) if ((page == -1 && pager.topEnabled) || (page == 1 && pager.bottomEnabled)) {
                pager.show(page); layout()
                descendants(root).filterIsInstance<TextView>().filter { it.text.isNotEmpty() && it.isShown }.forEach {
                    assertTrue("$label page $page: '${it.text}' (${it.textSize}px) sits in a ${it.height}px box at $side px", it.height >= it.textSize * 1.3f)
                }
                pager.show(0)
            }
        }
        fun idle(ms: Long = 0) = if (ms > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) else shadowOf(Looper.getMainLooper()).idle()
        fun state(name: String = "덤벨 컬", running: Boolean = false, resting: Boolean = false, setId: String = "set", setIndex: Int = 0,
            reps: Int = 8, target: Int = 12, weight: Double = 20.0, heart: Int = 112, restLeftMs: Long = 45_000, session: String = "preview",
            phase: String = if (resting) "RESTING" else "WORKING", extras: Intent.() -> Unit = {}) {
            app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
                .putExtra("session", session).putExtra("setId", setId).putExtra("attemptId", "attempt-$setId")
                .putExtra("exerciseId", "curl").putExtra("exerciseName", name).putExtra("paired", true)
                .putExtra("running", running).putExtra("resting", resting).putExtra("targetReps", target).putExtra("reps", reps)
                .putExtra("setIndex", setIndex).putExtra("heartRate", heart).putExtra("weightKg", weight).putExtra("restSeconds", 60)
                .putExtra("restEndAtMs", System.currentTimeMillis() + restLeftMs).putExtra("phase", phase).apply(extras))
            idle()
        }
        val long = "케이블 익스터널 로테이션"
        state(running = true); verify("measuring")
        state(name = long, running = true, reps = 99, target = 12, weight = 102.5, heart = 0); verify("long name, over target, no heart rate")
        state(name = long, running = true, reps = 105, target = 100, weight = 62.5); verify("three-digit count")
        state(resting = true, setIndex = 1, restLeftMs = 5_000); verify("rest 0:05")
        state(name = long, resting = true, setIndex = 11, target = 15, weight = 102.5); verify("long rest card")
        // Set done (3), then the two record screens (7, 8) with extreme values.
        state(name = long, running = true, setId = "a", setIndex = 11, reps = 3, weight = 102.5)
        state(name = long, running = false, setId = "a", setIndex = 11, reps = 105, weight = 102.5, phase = "READY") {
            putExtra("recordSetId", "a"); putExtra("recordKind", "BEST_VOLUME")
        }
        verify("set done"); idle(4_000)
        state(name = long, running = true, setId = "b", setIndex = 12, reps = 3, weight = 102.5)
        state(name = long, running = false, setId = "b", setIndex = 12, reps = 3, weight = 102.5, phase = "READY") {
            putExtra("recordSetId", "b"); putExtra("recordKind", "HEAVIEST_WEIGHT"); putExtra("recordWeightKg", 102.5); putExtra("recordPreviousWeightKg", 97.5)
        }
        verify("heaviest record"); idle(4_000)
        state(name = long, running = true, setId = "c", setIndex = 13, reps = 3, weight = 62.5)
        state(name = long, running = false, setId = "c", setIndex = 13, reps = 12, weight = 62.5, phase = "READY") {
            putExtra("recordSetId", "c"); putExtra("recordKind", "SAME_WEIGHT_REPS"); putExtra("recordWeightKg", 62.5)
            putExtra("recordReps", 12); putExtra("recordPreviousReps", 10)
        }
        verify("reps record"); idle(4_000)
        // Calibration (9, 10, 11).
        state(session = "calibration-x", setId = "", running = true, reps = 0, target = 5, phase = "BASELINE") {
            putExtra("preparationHoldMs", 500L); putExtra("preparationHoldTargetMs", 2_000L); putExtra("phoneCameraRecording", true)
        }
        verify("calibration prepare")
        state(session = "calibration-x", setId = "", running = true, reps = 3, target = 5, phase = "CONTROLLED_REPS") { putExtra("phoneCameraRecording", true) }
        verify("calibration count")
        state(session = "calibration-x", setId = "", running = false, reps = 5, target = 5, phase = "READY") { putExtra("calibrationConfirmedReps", 4) }
        verify("calibration done")
        activity.finish()
        // The workout result (12).
        app.getSharedPreferences("watch-home", android.content.Context.MODE_PRIVATE).edit()
            .putString("completedSession", "done").putLong("completedAt", 1L).putBoolean("showCompletion", true)
            .putString("completedSummary", WatchHomeStore.Summary(7_384_000L, 124, 102_400.5, 121, 12).encode()).commit()
        val result = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val resultRoot = result.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        resultRoot.measure(View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY))
        resultRoot.layout(0, 0, side, side)
        val boxes = descendants(resultRoot).filterIsInstance<TextView>().filter { it.text.isNotEmpty() && it.isShown }
        assertTrue(boxes.any { it.text.toString() == "2:03:04" })
        boxes.forEach { assertTrue("result: '${it.text}' (${it.textSize}px) sits in a ${it.height}px box at $side px", it.height >= it.textSize * 1.3f) }
        result.finish()
    }

    @Config(sdk = [34], qualifiers = "w216dp-h216dp-round-xhdpi") @Test fun atXhdpi() = check(432)
    @Config(sdk = [34], qualifiers = "w216dp-h216dp-round-xxhdpi") @Test fun atXxhdpi() = check(648)
}
