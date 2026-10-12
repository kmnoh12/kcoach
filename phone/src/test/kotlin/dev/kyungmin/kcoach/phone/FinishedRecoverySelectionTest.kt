package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinishedRecoverySelectionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun plan(id: String) = WorkoutSnapshot(sessionId = id, routineId = "r", exerciseIndex = 0, setIndex = 0,
        plannedSets = listOf(PlannedSetSnapshot(0, "external_rotation", 0, 10, 5.0, true, performedReps = 6, repSource = SetRepSource.WATCH)),
        performedReps = mapOf("external_rotation" to 6), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
        paused = false, startedAtMs = 1, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0, lastCompletedSetKey = null, captureStates = emptyMap())
    private fun manifest(session: String, id: String) = WireEnvelope(messageType = WireMessageType.CAPTURE_MANIFEST,
        sessionId = session, source = WireSource.WATCH, sentAtElapsedMs = 0, sentAtWallMs = 0, payload = mapOf(
            WirePayloadKeys.CAPTURE_ID to id, WirePayloadKeys.EXERCISE_ID to "external_rotation", WirePayloadKeys.SET_INDEX to "0"))
    private fun encoded(count: Int) = MutableList(24) { "" }.apply { this[0] = "6"; this[1] = count.toString(); this[2] = count.toString(); this[11] = "0.9" }.joinToString("|")
    @Test fun onlyTheSessionWithANewDurableAnalysisIsRecoveredAfterAnInterruptedReconcile() {
        val finished = FinishedWorkoutStore(app)
        finished.finish(plan("changed"), "changed", 10); finished.finish(plan("clean"), "clean", 10)
        assertEquals(0, finished.replay())
        val m = manifest("changed", "capture-change")
        app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE).edit().putString(CaptureInbox.manifestKey("capture-change"), WireCodec.encode(m)).commit()
        SetAnalysisStore(app).persistAnalysis(m, encoded(4)) // Simulate a crash before reconciliation.
        assertEquals(1, finished.replay())
        val logs = WorkoutLogStore(app).load().associateBy { it.sessionId }
        assertEquals(20, logs["changed"]!!.volumeKg); assertEquals(30, logs["clean"]!!.volumeKg)
        assertEquals(0, finished.replay())
    }
    @Test fun startupActivityDoesNotReconcileFinishedSessionsOnItsMainThread() {
        val p = plan("legacy")
        val prefs = app.getSharedPreferences("finished-workouts", Context.MODE_PRIVATE)
        val raw = JSONObject().put("snapshot", WorkoutSnapshotCodec.encode(p)).put("projected", true)
            .put("title", "legacy").put("minutes", 10).put("epochDay", 1).toString()
        prefs.edit().putString("session:legacy", raw).commit()
        val m = manifest("legacy", "legacy-capture")
        app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE).edit().putString("analysis:legacy-capture", encoded(4))
            .putString(CaptureInbox.manifestKey("legacy-capture"), WireCodec.encode(m)).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        try { assertEquals("Recovery belongs to the listener worker, not onCreate", raw, prefs.getString("session:legacy", null)) }
        finally { activity.destroy() }
        assertEquals(1, FinishedWorkoutStore(app).replay())
        assertEquals(20, WorkoutLogStore(app).load().single().volumeKg)
    }
}
