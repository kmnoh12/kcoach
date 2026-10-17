package dev.kyungmin.kcoach.wear

import android.content.Context
import android.content.Intent
import dev.kyungmin.kcoach.core.*
import org.json.JSONObject

/** UI-only receipt, separate from workout records and detector configuration. */
class CalibrationWatchStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("calibration-camera-state", Context.MODE_PRIVATE)
    private fun read() = prefs.getString("state", null)?.let(::JSONObject)
    fun begin(session: String, set: String, attempt: String, exercise: String, name: String) {
        if (CapturePurpose.forSession(session) != CapturePurpose.PAIRED_REFERENCE) {
            check(prefs.edit().clear().commit()); return
        }
        val old = read()
        if (old?.getString("session") == session && old.getString("set") == set && old.getString("attempt") == attempt) return
        val row = JSONObject().put("session", session).put("set", set).put("attempt", attempt)
            .put("exercise", exercise).put("name", name).put("reps", 0).put("finished", false)
        check(prefs.edit().putString("state", row.toString()).commit())
    }
    fun update(state: CalibrationCameraState): Boolean {
        val row = read() ?: return false
        if (row.getString("session") != state.sessionId || row.getString("set") != state.setId || row.getString("attempt") != state.attemptId) return false
        val old = row.optString("phone").takeIf { it.isNotEmpty() }?.let(CalibrationCameraState::decode)
        if (old != null && !state.supersedes(old)) return old == state
        if (row.getBoolean("finished") && state.recording) return false
        row.put("phone", state.encode())
        check(prefs.edit().putString("state", row.toString()).commit()); return true
    }
    fun finish(session: String, reps: Int) {
        val row = read() ?: return
        if (row.getString("session") != session) return
        row.put("finished", true).put("reps", reps)
        check(prefs.edit().putString("state", row.toString()).commit())
    }
    fun applyTo(intent: Intent, activeSession: String) {
        val row = read() ?: return
        if (activeSession.isNotBlank() && activeSession != row.getString("session")) return
        val state = row.optString("phone").takeIf { it.isNotEmpty() }?.let(CalibrationCameraState::decode)
        intent.putExtra("phoneCameraRecording", state?.recording == true && !row.getBoolean("finished"))
            .putExtra("calibrationConfirmedReps", state?.confirmedReps ?: -1)
        if (activeSession.isBlank() && row.getBoolean("finished")) {
            intent.putExtra("session", row.getString("session")).putExtra("setId", row.getString("set"))
                .putExtra("attemptId", row.getString("attempt")).putExtra("exerciseId", row.getString("exercise"))
                .putExtra("exerciseName", row.getString("name")).putExtra("reps", row.getInt("reps"))
                .putExtra("phase", "CALIBRATION_RESULT")
        } else if (!intent.getBooleanExtra("measurementReady", false) && intent.getStringExtra("phase")?.startsWith("PREPAR") == true) {
            intent.putExtra("phase", "BASELINE")
        }
    }
}
