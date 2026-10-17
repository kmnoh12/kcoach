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

/** No line of text may sit in a box shorter than the text itself, at any screen density. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchTextBoxTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun check(side: Int) {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val app = RuntimeEnvironment.getApplication()
        fun state(resting: Boolean, name: String) {
            app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
                .putExtra("session", "preview").putExtra("setId", "set").putExtra("attemptId", "attempt")
                .putExtra("exerciseId", "curl").putExtra("exerciseName", name).putExtra("paired", true)
                .putExtra("running", !resting).putExtra("resting", resting).putExtra("targetReps", 12).putExtra("reps", 8)
                .putExtra("heartRate", 112).putExtra("weightKg", 20.0).putExtra("restSeconds", 60)
                .putExtra("restEndAtMs", System.currentTimeMillis() + 45_000).putExtra("phase", if (resting) "RESTING" else "WORKING"))
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, side, side)
        }
        for ((resting, name) in listOf(false to "덤벨 컬", false to "케이블 익스터널 로테이션", true to "덤벨 컬")) {
            state(resting, name)
            val boxes = descendants(root).filterIsInstance<TextView>().filter { it.text.isNotEmpty() && it.visibility == View.VISIBLE }
            boxes.forEach {
                assertTrue("'${it.text}' (${it.textSize}px) sits in a ${it.height}px box at $side px", it.height >= it.textSize * 1.3f)
            }
        }
        activity.finish()
    }

    @Config(sdk = [34], qualifiers = "w216dp-h216dp-round-xhdpi") @Test fun atXhdpi() = check(432)
    @Config(sdk = [34], qualifiers = "w216dp-h216dp-round-xxhdpi") @Test fun atXxhdpi() = check(648)
}
