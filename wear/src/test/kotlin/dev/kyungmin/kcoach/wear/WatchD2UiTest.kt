package dev.kyungmin.kcoach.wear

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import dev.kyungmin.kcoach.core.ManualSetCommand
import dev.kyungmin.kcoach.core.WireCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w216dp-h216dp-round-xhdpi")
class WatchD2UiTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun broadcast(reps: Int = 8, running: Boolean = true) {
        val app = RuntimeEnvironment.getApplication()
        app.sendBroadcast(Intent(WearWorkoutService.ACTION_STATE).setPackage(app.packageName)
            .putExtra("paired", true).putExtra("running", running).putExtra("resting", false).putExtra("paused", false)
            .putExtra("session", if (running) "active" else "").putExtra("setId", if (running) "set" else "")
            .putExtra("attemptId", "attempt").putExtra("exerciseId", "curl").putExtra("exerciseName", "덤벨 컬")
            .putExtra("reps", reps).putExtra("targetReps", 20).putExtra("heartRate", 112).putExtra("phase", if (running) "WORKING" else "IDLE"))
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun submitted(): List<Intent> {
        val results = mutableListOf<Intent>(); val app = shadowOf(RuntimeEnvironment.getApplication())
        while (true) { val next = app.nextStartedService ?: break; if (next.getStringExtra("command") == "MANUAL_SUBMIT") results += next }
        return results
    }
    @Test fun onlyAboveTheTargetTurnsOrangeAndHeartRateHasNoBpmLabel() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val face = views(controller.get().window.decorView).filterIsInstance<WatchWorkoutFace>().single()
        broadcast(20); assertEquals(WatchWorkoutFace.BONE, face.value.currentTextColor)
        broadcast(21); assertEquals(WatchWorkoutFace.ORANGE, face.value.currentTextColor)
        assertEquals("112", face.heartRate.text.toString()); assertEquals(android.view.View.VISIBLE, face.heart.visibility)
        assertFalse(views(face).filterIsInstance<TextView>().any { it.text.contains("bpm") })
        controller.pause().stop().destroy()
    }
    @Test fun countCorrectionIsOnlyADraftUntilTheUserFinishesTheSet() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get(); broadcast(); submitted()
        views(activity.window.decorView).filterIsInstance<TextView>().single { it.text == "횟수 수정" }.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("9")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(submitted().isEmpty())
        views(activity.window.decorView).filterIsInstance<TextView>().single { it.text.startsWith("세트 마치기") }.performClick()
        val sent = submitted().single()
        assertEquals(9, ManualSetCommand.fromEnvelope(WireCodec.decode(sent.getStringExtra("payload")!!)).performedReps)
        controller.pause().stop().destroy()
    }
    @Test fun completedResultUsesItsOwnIdAndFailureAllowsANewRequestWithoutClaimingPhoneDisplay() {
        val app = RuntimeEnvironment.getApplication(); WatchHomeStore(app).complete("finished-exact")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get(); val requests = mutableListOf<Intent>()
        activity.resultOpener = { request, done -> requests += request; done(false) }
        val face = views(activity.window.decorView).filterIsInstance<WatchWorkoutFace>().single()
        assertEquals(View.VISIBLE, face.action.visibility)
        face.action.performClick(); assertTrue(face.action.isEnabled)
        assertTrue(face.value.text.contains("다시 눌러"))
        activity.resultOpener = { request, done -> requests += request; done(true) }
        face.action.performClick()
        assertEquals("폰에서 확인해 주세요", face.value.text.toString())
        assertEquals(listOf("finished-exact", "finished-exact"), requests.map { it.data!!.getQueryParameter("session_id") })
        assertNotEquals(requests[0].data!!.getQueryParameter("request_id"), requests[1].data!!.getQueryParameter("request_id"))
        broadcast()
        assertEquals(View.GONE, face.action.visibility)
        face.action.performClick(); assertEquals(2, requests.size)
        controller.pause().stop().destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertFalse(WatchHomeStore(app).showsCompletion())
        restored.pause().stop().destroy()
    }
}
