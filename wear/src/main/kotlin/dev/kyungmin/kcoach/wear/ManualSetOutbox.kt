package dev.kyungmin.kcoach.wear

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject

data class PendingManualSet(val command: ManualSetCommand, val ack: ManualSetAck? = null)

/** Retain the user's count offline, including rejected/conflicting counts for review. */
class ManualSetOutbox(context: Context) {
    private val prefs = context.getSharedPreferences("manual-set-outbox", Context.MODE_PRIVATE)

    fun enqueue(command: ManualSetCommand): Boolean = synchronized(lock) {
        val entries = read().toMutableList()
        entries.firstOrNull { it.command.commandId == command.commandId }?.let { return@synchronized it.command == command }
        val previous = entries.lastOrNull { sameAttempt(it.command, command.sessionId, command.setId, command.attemptId) }
        if (previous != null && (previous.ack == null || previous.ack.status in setOf(ManualSetStatus.STALE, ManualSetStatus.NO_SESSION, ManualSetStatus.REJECTED)))
            return@synchronized false
        if (previous?.ack != null && previous.ack.currentRevision != command.expectedRevision) return@synchronized false
        // Only acknowledged successful history is expendable; never evict offline inputs.
        if (entries.size >= 256) {
            val index = entries.indexOfFirst { it.ack?.status in setOf(ManualSetStatus.APPLIED, ManualSetStatus.DUPLICATE) }
            if (index < 0) return@synchronized false
            entries.removeAt(index)
        }
        entries.add(PendingManualSet(command))
        write(entries)
    }

    fun pending(): List<ManualSetCommand> = synchronized(lock) { read().filter { it.ack == null }.map { it.command } }

    fun unresolved(): List<PendingManualSet> = synchronized(lock) {
        read().filter { it.ack?.status !in setOf(ManualSetStatus.APPLIED, ManualSetStatus.DUPLICATE) }
    }

    fun latest(sessionId: String, setId: String, attemptId: String): PendingManualSet? = synchronized(lock) {
        read().lastOrNull { sameAttempt(it.command, sessionId, setId, attemptId) }
    }

    fun acknowledge(ack: ManualSetAck): Boolean = synchronized(lock) {
        val entries = read().toMutableList()
        val index = entries.indexOfFirst {
            it.command.commandId == ack.commandId && sameAttempt(it.command, ack.sessionId, ack.setId, ack.attemptId)
        }
        if (index < 0) return@synchronized false
        val previous = entries[index].ack
        if (previous != null && (ack.currentRevision < previous.currentRevision ||
                previous.status in setOf(ManualSetStatus.APPLIED, ManualSetStatus.DUPLICATE))) return@synchronized true
        entries[index] = entries[index].copy(ack = ack)
        write(entries)
    }

    private fun sameAttempt(command: ManualSetCommand, sessionId: String, setId: String, attemptId: String) =
        command.sessionId == sessionId && command.setId == setId && command.attemptId == attemptId

    private fun read(): List<PendingManualSet> {
        val values = JSONArray(prefs.getString("entries", "[]")!!)
        return (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            PendingManualSet(ManualSetCommand.fromEnvelope(WireCodec.decode(item.getString("command"))),
                item.optString("ack").takeIf { it.isNotBlank() }?.let(ManualSetAck::decode))
        }
    }

    private fun write(entries: List<PendingManualSet>): Boolean = prefs.edit().putString("entries", JSONArray(entries.map {
        JSONObject().put("command", WireCodec.encode(it.command.toEnvelope(0, 0)))
            .put("ack", it.ack?.encode(0, 0).orEmpty())
    }).toString()).commit()

    companion object { private val lock = Any() }
}
