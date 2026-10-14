package dev.kyungmin.kcoach.wear

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualWatchUiTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun manualExerciseHasNoMadeUpCountAndEmptyInputCannotSubmit() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val app = RuntimeEnvironment.getApplication()
        app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
            .putExtra("paired", true).putExtra("running", true).putExtra("session", "session")
            .putExtra("exerciseId", "leg_press").putExtra("exerciseName", "레그프레스")
            .putExtra("setId", "set").putExtra("attemptId", "attempt").putExtra("targetReps", 12)
            .putExtra("manualRequired", true).putExtra("phase", "WORKING"))
        ShadowLooper.idleMainLooper()
        val views = descendants(activity.findViewById(android.R.id.content))
        assertTrue(views.filterIsInstance<TextView>().any { it.text == "— / 12회" })
        views.filterIsInstance<TextView>().single { it.text == "실제 횟수 입력 · 완료" }.performClick()
        ShadowLooper.idleMainLooper()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        assertNotNull(input.error)
        input.setText("0")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.isShowing)
        var submitted: Intent? = null
        while (true) {
            val intent = shadowOf(app).nextStartedService ?: break
            if (intent.getStringExtra("command") == "MANUAL_SUBMIT") submitted = intent
        }
        val command = ManualSetCommand.fromEnvelope(WireCodec.decode(submitted!!.getStringExtra("payload")!!))
        assertEquals(0, command.performedReps)
        assertEquals("attempt", command.attemptId)
        activity.finish()
    }
}
