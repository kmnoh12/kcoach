package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PendingPatternTransfer(val command: PatternProfileCommand, val ack: PatternProfileAck? = null, val superseded: Boolean = false)

/** Durable comparison-profile requests. Activation is deliberately issued by a separate verified-evidence controller. */
class PatternTransferOutbox(context: Context) {
    private val prefs = context.getSharedPreferences("pattern-transfer-outbox", Context.MODE_PRIVATE)

    fun enqueueShadow(runtime: PatternRuntimeProfile): Result<PendingPatternTransfer> = synchronized(lock) {
        runCatching {
            val state = read()
            val key = slotId(runtime.compatibility)
            val entries = entries(state).toMutableList()
            val latest = entries.lastOrNull { slotId(it.command.runtime.compatibility) == key && !it.superseded &&
                it.command.operation == PatternProfileOperation.SHADOW }
            if (latest?.command?.runtime == runtime && latest.ack?.status !in setOf(PatternProfileStatus.REJECTED,
                    PatternProfileStatus.SUPERSEDED, PatternProfileStatus.WRONG_DEVICE)) return@runCatching latest
            val revision = state.getJSONObject("revisions").optLong(key, 0)
            val next = PendingPatternTransfer(PatternProfileCommand(UUID.randomUUID().toString(), PatternProfileOperation.SHADOW, runtime, revision))
            for (index in entries.indices) if (slotId(entries[index].command.runtime.compatibility) == key &&
                entries[index].command.operation == PatternProfileOperation.SHADOW)
                entries[index] = entries[index].copy(superseded = true)
            entries += next
            save(state, entries)
            next
        }
    }

    /** Transport seam used only after the adoption controller has rechecked immutable reports and sources. */
    internal fun enqueueTransition(runtime: PatternRuntimeProfile, operation: PatternProfileOperation,
        evidence: PatternValidationEvidence): Result<PendingPatternTransfer> = synchronized(lock) {
        runCatching {
            require(operation != PatternProfileOperation.SHADOW)
            val state = read(); val all = entries(state).toMutableList()
            val key = slotId(runtime.compatibility)
            val latest = all.lastOrNull { slotId(it.command.runtime.compatibility) == key && !it.superseded &&
                it.command.operation != PatternProfileOperation.SHADOW }
            if (latest?.command?.runtime == runtime && latest.command.operation == operation && latest.command.evidence == evidence &&
                latest.ack?.status in setOf(PatternProfileStatus.APPLIED, PatternProfileStatus.DISABLED)) return@runCatching latest
            if (latest != null && latest.ack?.status in setOf(null, PatternProfileStatus.STAGED)) {
                require(latest.command.runtime == runtime && latest.command.operation == operation && latest.command.evidence == evidence) {
                    "A profile transition is still awaiting observed use"
                }
                return@runCatching latest
            }
            val history = activeHistory(all.filter { slotId(it.command.runtime.compatibility) == key })
            require(evidence.previousActiveHash == history.firstOrNull()?.command?.runtime?.candidateHash) { "Observed active profile changed; validate again" }
            if (operation == PatternProfileOperation.ACTIVATE && history.isNotEmpty())
                require(runtime.version > history.first().command.runtime.version) { "Use rollback to return to an older profile" }
            if (operation == PatternProfileOperation.ROLLBACK)
                require(history.getOrNull(1)?.command?.runtime == runtime) { "Only the exact previous observed profile can be restored" }
            if (operation == PatternProfileOperation.DISABLE)
                require(history.firstOrNull()?.command?.runtime == runtime) { "Only the exact observed active profile can be disabled" }
            val next = PendingPatternTransfer(PatternProfileCommand(UUID.randomUUID().toString(), operation, runtime,
                state.getJSONObject("revisions").optLong(key, 0), evidence))
            for (index in all.indices) if (slotId(all[index].command.runtime.compatibility) == key &&
                all[index].command.operation != PatternProfileOperation.SHADOW) all[index] = all[index].copy(superseded = true)
            all += next; save(state, all); next
        }
    }

    fun pending(): Result<List<PatternProfileCommand>> = synchronized(lock) {
        runCatching { entries(read()).filter { !it.superseded && it.ack?.status in setOf(null, PatternProfileStatus.STAGED) }.map { it.command } }
    }

    fun latest(key: PatternCompatibility): Result<PendingPatternTransfer?> = synchronized(lock) {
        runCatching { entries(read()).lastOrNull { slotId(it.command.runtime.compatibility) == slotId(key) && !it.superseded &&
            it.command.operation == PatternProfileOperation.SHADOW } }
    }

    fun latestTransition(key: PatternCompatibility): Result<PendingPatternTransfer?> = synchronized(lock) {
        runCatching { entries(read()).lastOrNull { slotId(it.command.runtime.compatibility) == slotId(key) && !it.superseded &&
            it.command.operation != PatternProfileOperation.SHADOW } }
    }

    /** Current and previous profiles actually used by the watch, including a late acknowledgement. */
    fun activeHistory(key: PatternCompatibility): Result<List<PendingPatternTransfer>> = synchronized(lock) {
        runCatching { activeHistory(entries(read()).filter { slotId(it.command.runtime.compatibility) == slotId(key) }) }
    }

    fun acknowledge(ack: PatternProfileAck): Result<Boolean> = synchronized(lock) {
        runCatching {
            val state = read(); val entries = entries(state).toMutableList()
            val index = entries.indexOfFirst { it.command.commandId == ack.commandId }
            if (index < 0) return@runCatching false
            val entry = entries[index]; val command = entry.command
            if (ack.operation != command.operation || ack.profileId != command.runtime.id || ack.profileVersion != command.runtime.version ||
                ack.candidateHash != command.runtime.candidateHash || ack.runtimeHash != command.runtimeHash ||
                ack.deviceId != command.runtime.compatibility.deviceId) return@runCatching false
            if (entry.ack?.status in setOf(PatternProfileStatus.APPLIED, PatternProfileStatus.DISABLED)) {
                return@runCatching requireNotNull(entry.ack).copy(duplicate = false) == ack.copy(duplicate = false)
            }
            if (ack.status in setOf(PatternProfileStatus.STAGED, PatternProfileStatus.APPLIED, PatternProfileStatus.DISABLED) && ack.slotRevision <= command.expectedRevision)
                return@runCatching false
            val key = slotId(command.runtime.compatibility)
            val revisions = state.getJSONObject("revisions")
            revisions.put(key, maxOf(revisions.optLong(key, 0), ack.slotRevision))
            entries[index] = entry.copy(ack = ack)
            if (!entry.superseded && ack.status == PatternProfileStatus.CONFLICT && command.operation == PatternProfileOperation.SHADOW) {
                // Safe only for a comparison profile: no workout count or active profile changes.
                entries[index] = entries[index].copy(superseded = true)
                entries += PendingPatternTransfer(command.copy(commandId = UUID.randomUUID().toString(),
                    expectedRevision = revisions.getLong(key)))
            }
            save(state, entries)
            true
        }
    }

    private fun read(): JSONObject = prefs.getString("state", null)?.let(::JSONObject)
        ?.also { require(it.getInt("schemaVersion") == 1) }
        ?: JSONObject().put("schemaVersion", 1).put("revisions", JSONObject()).put("entries", JSONArray())
    private fun entries(state: JSONObject): List<PendingPatternTransfer> {
        val array = state.getJSONArray("entries")
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            PendingPatternTransfer(PatternProfileCommand.decode(row.getString("command")),
                row.optString("ack").takeIf { it.isNotEmpty() }?.let(PatternProfileAck::decode), row.optBoolean("superseded"))
        }
    }
    private fun save(state: JSONObject, entries: List<PendingPatternTransfer>) {
        val retained = entries.toMutableList()
        val protected = entries.groupBy { slotId(it.command.runtime.compatibility) }.values.flatMap { rows ->
            activeHistory(rows) + listOfNotNull(rows.filter { it.ack?.status == PatternProfileStatus.DISABLED }
                .maxByOrNull { it.ack!!.slotRevision })
        }.map { it.command.commandId }.toSet()
        while (retained.size > 256) {
            val index = retained.indexOfFirst { it.command.commandId !in protected &&
                (it.superseded || it.ack?.status in setOf(PatternProfileStatus.APPLIED, PatternProfileStatus.DISABLED)) }
            require(index >= 0) { "Pending profile transfers retained; storage limit reached" }
            retained.removeAt(index)
        }
        state.put("entries", JSONArray(retained.map { row -> JSONObject().put("command", row.command.encode())
            .put("ack", row.ack?.encode().orEmpty()).put("superseded", row.superseded) }))
        check(prefs.edit().putString("state", state.toString()).commit())
    }
    private fun activeHistory(entries: List<PendingPatternTransfer>): List<PendingPatternTransfer> {
        val disabledRevision = entries.filter { it.ack?.status == PatternProfileStatus.DISABLED }
            .maxOfOrNull { it.ack!!.slotRevision } ?: -1L
        return entries.filter {
            it.command.operation != PatternProfileOperation.SHADOW && it.ack?.status == PatternProfileStatus.APPLIED &&
                it.ack.slotRevision > disabledRevision
        }.sortedByDescending { it.ack!!.slotRevision }.distinctBy { it.command.runtimeHash }.take(2)
    }
    private fun slotId(key: PatternCompatibility) = SensorCaptureFormat.sha256(listOf(key.localUserId, key.recipe.name,
        key.side, key.placement, key.deviceId, key.coordinates).joinToString("\n").toByteArray(Charsets.UTF_8))
    companion object { private val lock = Any() }
}
