package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*

/** Keep only the last announcement, and retry only while its completed set is still in rest. */
internal class PhoneRecordOutbox(context: Context) {
    private val prefs = context.getSharedPreferences("wear-record-outbox", Context.MODE_PRIVATE)
    private val store = SessionStore(context)

    fun enqueue(payload: String): String? = synchronized(lock) {
        val record = SetRecordCommand.decode(payload)
        if (prefs.getString("lastCommandId", null) == record.commandId) return@synchronized null
        check(prefs.edit().putString("pending", payload).putString("lastCommandId", record.commandId).commit())
        pending()
    }

    fun pending(): String? = synchronized(lock) {
        val payload = prefs.getString("pending", null) ?: return@synchronized null
        val record = SetRecordCommand.decode(payload)
        val snapshot = store.loadActive().getOrThrow()
        val valid = snapshot != null && snapshot.sessionId == record.sessionId && snapshot.phase == WorkoutPhase.RESTING &&
            snapshot.plannedSets.any { it.setId == record.setId && it.attemptId == record.attemptId && it.completed &&
                SetIdentity.completionKey(snapshot.sessionId, it) == snapshot.lastCompletedSetKey }
        if (valid) payload else { check(prefs.edit().remove("pending").commit()); null }
    }
    companion object { private val lock = Any() }
}
