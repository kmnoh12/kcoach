package dev.kyungmin.kcoach.wear

import android.content.Context
import android.content.Intent
import dev.kyungmin.kcoach.core.SetRecordCommand

/** Retains the just-finished identity even though REST already names the next planned set. */
internal class WatchRecordState(context: Context) {
    private val prefs = context.getSharedPreferences("watch-record-state", Context.MODE_PRIVATE)
    private var session = prefs.getString("session", "").orEmpty()
    private var set = prefs.getString("set", "").orEmpty()
    private var attempt = prefs.getString("attempt", "").orEmpty()
    private var record = prefs.getString("record", null)?.let { runCatching { SetRecordCommand.decode(it) }.getOrNull() }

    fun observe(sessionId: String, setId: String, attemptId: String, working: Boolean) {
        if (session != sessionId || working && (set != setId || attempt != attemptId)) {
            session = sessionId; set = setId; attempt = attemptId; record = null
            save()
        }
    }

    fun receive(payload: String): SetRecordCommand? {
        val incoming = runCatching { SetRecordCommand.decode(payload) }.getOrNull() ?: return null
        if (session.isBlank() || set.isBlank() || incoming.sessionId != session || incoming.setId != set ||
            incoming.attemptId != attempt || record != null) return null
        record = incoming
        save()
        return incoming
    }

    fun currentRecord(): SetRecordCommand? = record

    fun addExtras(intent: Intent) {
        record?.let {
            intent.putExtra("recordSetId", it.setId).putExtra("recordKind", it.kind.name)
            it.weightKg?.let { value -> intent.putExtra("recordWeightKg", value) }
            it.previousWeightKg?.let { value -> intent.putExtra("recordPreviousWeightKg", value) }
            it.reps?.let { value -> intent.putExtra("recordReps", value) }
            it.previousReps?.let { value -> intent.putExtra("recordPreviousReps", value) }
        }
    }

    private fun save() {
        check(prefs.edit().putString("session", session).putString("set", set).putString("attempt", attempt)
            .putString("record", record?.encode()).commit())
    }
}
