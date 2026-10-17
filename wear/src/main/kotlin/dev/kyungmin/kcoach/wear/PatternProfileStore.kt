package dev.kyungmin.kcoach.wear

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** A selection is frozen before sensors run; APPLIED needs an observed use, not a successful file write. */
data class FrozenPatternProfile(val runtime: PatternRuntimeProfile, val operation: PatternProfileOperation,
    val commandId: String, val slotRevision: Long, val sessionId: String, val setId: String, val attemptId: String,
    val watchClockId: String)

class PatternProfileStore(private val context: Context,
    private val deviceId: String = WearClockEpoch.current(context).deviceId) {
    private val prefs = context.getSharedPreferences("reviewed-pattern-runtime", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "pattern-runtime")

    fun stage(command: PatternProfileCommand): Result<PatternProfileAck> = synchronized(lock) {
        runCatching {
            require(command.operation !in setOf(PatternProfileOperation.ACTIVATE, PatternProfileOperation.ROLLBACK) ||
                command.evidence?.safetyVersion == 1) { "Calibration safety revalidation required" }
            val state = read()
            val slots = state.getJSONObject("slots")
            val id = slotId(command.runtime.compatibility)
            val slot = slots.optJSONObject(id) ?: JSONObject().put("revision", 0L)
            val revision = slot.getLong("revision")
            fun ack(status: PatternProfileStatus) = PatternProfileAck(command.commandId, command.operation, command.runtime.id,
                command.runtime.version, command.runtime.candidateHash, command.runtimeHash, revision, deviceId, status)
            if (command.runtime.compatibility.deviceId != deviceId) return@runCatching ack(PatternProfileStatus.WRONG_DEVICE)
            val receipts = state.getJSONObject("receipts")
            val prior = receipts.optJSONObject(command.commandId)
            if (prior != null) {
                require(prior.getString("fingerprint") == fingerprint(command)) { "Command ID reused with different contents" }
                return@runCatching PatternProfileAck.decode(prior.getString("ack")).copy(duplicate = true)
            }
            if (command.expectedRevision != revision) return@runCatching ack(PatternProfileStatus.CONFLICT)
            val active = slot.optString("currentActive").takeIf { it.isNotEmpty() }?.let(PatternProfileCommand::decode)
            if (command.operation != PatternProfileOperation.SHADOW) {
                require(command.evidence?.previousActiveHash == active?.runtime?.candidateHash) { "Active profile changed" }
                if (command.operation == PatternProfileOperation.DISABLE)
                    require(active?.runtime == command.runtime) { "Disable must identify the exact active snapshot" }
                if (command.operation == PatternProfileOperation.ACTIVATE && active != null)
                    require(command.runtime.version > active.runtime.version) { "Use rollback to return to an older version" }
                if (command.operation == PatternProfileOperation.ROLLBACK) {
                    val previous = slot.optString("previousActive").takeIf { it.isNotEmpty() }?.let(PatternProfileCommand::decode)
                    require(previous?.runtime == command.runtime && slot.optString("previousActiveHash") == command.runtime.candidateHash) {
                        "Rollback must restore the exact previous active snapshot"
                    }
                }
            }
            saveRuntime(command.runtime)
            val channel = if (command.operation == PatternProfileOperation.SHADOW) "Shadow" else "Active"
            val oldPending = slot.optString("pending$channel").takeIf { it.isNotEmpty() }?.let(PatternProfileCommand::decode)
            if (oldPending != null) receipts.optJSONObject(oldPending.commandId)?.let { receipt ->
                val oldAck = PatternProfileAck.decode(receipt.getString("ack"))
                receipt.put("ack", oldAck.copy(status = PatternProfileStatus.SUPERSEDED).encode())
            }
            val nextRevision = Math.addExact(revision, 1L)
            val response = ack(PatternProfileStatus.STAGED).copy(slotRevision = nextRevision)
            slot.put("revision", nextRevision).put("pending$channel", command.encode())
            slots.put(id, slot)
            receipts.put(command.commandId, JSONObject().put("fingerprint", fingerprint(command)).put("command", command.encode()).put("ack", response.encode()))
            trimReceipts(state)
            write(state)
            response
        }
    }

    fun beginSet(key: PatternCompatibility, sessionId: String, setId: String, attemptId: String, watchClockId: String,
        active: Boolean = false): Result<FrozenPatternProfile?> = synchronized(lock) {
        runCatching {
            require(key.deviceId == deviceId && listOf(sessionId, setId, attemptId, watchClockId).all { it.isNotBlank() })
            val state = read()
            val slots = state.getJSONObject("slots")
            val id = slotId(key)
            val slot = slots.optJSONObject(id) ?: if (active) JSONObject().put("revision", 0L) else return@runCatching null
            if (active) {
                val effective = slot.optString("pendingActive").ifEmpty { slot.optString("currentActive") }
                    .takeIf { it.isNotEmpty() }?.let(PatternProfileCommand::decode)
                if (effective != null && effective.operation != PatternProfileOperation.DISABLE && effective.evidence?.safetyVersion != 1) {
                    // Preserve receipts/revision for an explicit DISABLE or verified replacement, but use generic.
                    slot.remove("frozenActive")
                    slot.put("frozenEmptyActive", JSONObject().put("sessionId", sessionId).put("setId", setId)
                        .put("attemptId", attemptId).put("watchClockId", watchClockId))
                    slots.put(id, slot); write(state); return@runCatching null
                }
            }
            val channel = if (active) "Active" else "Shadow"
            if (active) slot.optJSONObject("frozenEmptyActive")?.let { empty ->
                if (empty.getString("sessionId") == sessionId && empty.getString("setId") == setId &&
                    empty.getString("attemptId") == attemptId) {
                    require(empty.getString("watchClockId") == watchClockId) { "The clock rebooted; a new attempt is required" }
                    return@runCatching null
                }
            }
            slot.optJSONObject("frozen$channel")?.let { json ->
                val frozen = decodeFrozen(json)
                if (frozen.sessionId == sessionId && frozen.setId == setId && frozen.attemptId == attemptId) {
                    require(frozen.watchClockId == watchClockId) { "The clock rebooted; a new attempt is required" }
                    return@runCatching frozen
                }
            }
            val pending = slot.optString("pending$channel").takeIf { it.isNotEmpty() }
            val encoded = pending ?: slot.optString("current$channel").takeIf { it.isNotEmpty() }
            if (encoded == null) {
                if (active) {
                    val empty = JSONObject().put("sessionId", sessionId).put("setId", setId)
                        .put("attemptId", attemptId).put("watchClockId", watchClockId)
                    slot.optString("disabledActive").takeIf { it.isNotEmpty() }?.let {
                        empty.put("disableCommandId", PatternProfileCommand.decode(it).commandId)
                    }
                    slot.put("frozenEmptyActive", empty).remove("frozenActive")
                    slots.put(id, slot)
                    write(state)
                }
                return@runCatching null
            }
            val command = PatternProfileCommand.decode(encoded)
            require(command.runtime.compatibility == key && loadRuntime(command.runtimeHash) == command.runtime)
            if (command.operation == PatternProfileOperation.DISABLE) {
                require(active && pending != null)
                slot.put("previousActive", slot.optString("currentActive"))
                    .put("previousActiveHash", command.runtime.candidateHash)
                slot.remove("currentActive"); slot.remove("pendingActive"); slot.remove("frozenActive")
                slot.put("disabledActive", encoded)
                slot.put("frozenEmptyActive", JSONObject().put("sessionId", sessionId).put("setId", setId)
                    .put("attemptId", attemptId).put("watchClockId", watchClockId).put("disableCommandId", command.commandId))
                slots.put(id, slot); write(state)
                return@runCatching null
            }
            val receipt = state.getJSONObject("receipts").getJSONObject(command.commandId)
            val revision = PatternProfileAck.decode(receipt.getString("ack")).slotRevision
            val frozen = FrozenPatternProfile(command.runtime, command.operation, command.commandId, revision,
                sessionId, setId, attemptId, watchClockId)
            if (pending != null && active) {
                val previousEncoded = slot.optString("currentActive")
                val previous = previousEncoded.takeIf { it.isNotEmpty() }?.let(PatternProfileCommand::decode)
                slot.put("previousActiveHash", previous?.runtime?.candidateHash.orEmpty())
                slot.put("previousActive", previousEncoded)
            }
            slot.put("current$channel", encoded).remove("pending$channel")
            if (active) { slot.remove("frozenEmptyActive"); slot.remove("disabledActive") }
            slot.put("frozen$channel", encodeFrozen(frozen))
            write(state) // selection and frozen identity move in one durable transaction
            frozen
        }
    }

    fun confirmUsed(frozen: FrozenPatternProfile, sampleNs: Long, processedAtNs: Long): Result<PatternProfileAck> = synchronized(lock) {
        runCatching {
            val state = read()
            val slot = state.getJSONObject("slots").getJSONObject(slotId(frozen.runtime.compatibility))
            val channel = if (frozen.operation == PatternProfileOperation.SHADOW) "Shadow" else "Active"
            require(decodeFrozen(slot.getJSONObject("frozen$channel")) == frozen)
            val receipts = state.getJSONObject("receipts")
            val receipt = receipts.getJSONObject(frozen.commandId)
            val previous = PatternProfileAck.decode(receipt.getString("ack"))
            if (previous.status == PatternProfileStatus.APPLIED) return@runCatching previous.copy(duplicate = true)
            require(previous.status == PatternProfileStatus.STAGED || previous.status == PatternProfileStatus.SUPERSEDED)
            val response = previous.copy(status = PatternProfileStatus.APPLIED, application = PatternProfileApplication(
                frozen.sessionId, frozen.setId, frozen.attemptId, frozen.watchClockId, sampleNs, processedAtNs))
            receipt.put("ack", response.encode()); write(state)
            response
        }
    }

    fun frozen(key: PatternCompatibility, active: Boolean = false): Result<FrozenPatternProfile?> = synchronized(lock) {
        runCatching { read().getJSONObject("slots").optJSONObject(slotId(key))
            ?.optJSONObject(if (active) "frozenActive" else "frozenShadow")?.let(::decodeFrozen)?.takeIf { frozen ->
                !active || PatternProfileCommand.decode(read().getJSONObject("receipts").getJSONObject(frozen.commandId)
                    .getString("command")).evidence?.safetyVersion == 1
            } }
    }

    /** Receipt identifies the removed model; application proves the basic detector actually processed a sample. */
    fun confirmDisabled(key: PatternCompatibility, sessionId: String, setId: String, attemptId: String,
        watchClockId: String, sampleNs: Long, processedAtNs: Long): Result<PatternProfileAck?> = synchronized(lock) {
        runCatching {
            val state = read()
            val slot = state.getJSONObject("slots").optJSONObject(slotId(key)) ?: return@runCatching null
            val empty = slot.optJSONObject("frozenEmptyActive") ?: return@runCatching null
            val commandId = empty.optString("disableCommandId").takeIf { it.isNotEmpty() } ?: return@runCatching null
            require(key.deviceId == deviceId && empty.getString("sessionId") == sessionId &&
                empty.getString("setId") == setId && empty.getString("attemptId") == attemptId &&
                empty.getString("watchClockId") == watchClockId && !slot.has("frozenActive"))
            val receipt = state.getJSONObject("receipts").getJSONObject(commandId)
            val previous = PatternProfileAck.decode(receipt.getString("ack"))
            if (previous.status == PatternProfileStatus.DISABLED) return@runCatching previous.copy(duplicate = true)
            require(previous.operation == PatternProfileOperation.DISABLE &&
                previous.status in setOf(PatternProfileStatus.STAGED, PatternProfileStatus.SUPERSEDED))
            val response = previous.copy(status = PatternProfileStatus.DISABLED, application = PatternProfileApplication(
                sessionId, setId, attemptId, watchClockId, sampleNs, processedAtNs))
            receipt.put("ack", response.encode()); write(state); response
        }
    }

    fun beginRequestedShadow(commandId: String, sessionId: String, setId: String, attemptId: String,
        watchClockId: String): Result<FrozenPatternProfile> = synchronized(lock) {
        runCatching {
            val state = read()
            val receipt = state.getJSONObject("receipts").getJSONObject(commandId)
            val command = PatternProfileCommand.decode(receipt.getString("command"))
            require(command.operation == PatternProfileOperation.SHADOW)
            val slot = state.getJSONObject("slots").getJSONObject(slotId(command.runtime.compatibility))
            val current = slot.optJSONObject("frozenShadow")?.let(::decodeFrozen)
            val sameAttempt = current?.let { it.sessionId == sessionId && it.setId == setId && it.attemptId == attemptId } == true
            if (sameAttempt) require(current!!.commandId == commandId)
            else {
                val desired = slot.optString("pendingShadow").ifEmpty { slot.optString("currentShadow") }
                require(PatternProfileCommand.decode(desired).commandId == commandId) { "The requested shadow candidate was superseded" }
            }
            beginSet(command.runtime.compatibility, sessionId, setId, attemptId, watchClockId).getOrThrow()!!
        }
    }

    fun requestedShadow(commandId: String): Result<PatternProfileCommand> = synchronized(lock) {
        runCatching {
            PatternProfileCommand.decode(read().getJSONObject("receipts").getJSONObject(commandId).getString("command"))
                .also { require(it.operation == PatternProfileOperation.SHADOW && it.runtime.compatibility.deviceId == deviceId) }
        }
    }

    private fun saveRuntime(runtime: PatternRuntimeProfile) {
        val bytes = PatternRuntimeCodec.encode(runtime)
        val hash = SensorCaptureFormat.sha256(bytes)
        check(root.mkdirs() || root.isDirectory)
        val file = File(root, "$hash.prt")
        if (file.exists()) { require(file.readBytes().contentEquals(bytes)); return }
        val temp = File(root, "$hash.incoming")
        FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
        check(temp.renameTo(file))
    }
    private fun loadRuntime(hash: String): PatternRuntimeProfile {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        val file = File(root, "$hash.prt"); require(file.length() in 1..4096)
        val bytes = file.readBytes(); require(SensorCaptureFormat.sha256(bytes) == hash)
        return PatternRuntimeCodec.decode(bytes)
    }
    private fun read(): JSONObject = prefs.getString("state", null)?.let(::JSONObject)
        ?: JSONObject().put("schemaVersion", 1).put("slots", JSONObject()).put("receipts", JSONObject())
    private fun write(state: JSONObject) { require(state.getInt("schemaVersion") == 1); check(prefs.edit().putString("state", state.toString()).commit()) }
    private fun fingerprint(command: PatternProfileCommand) = SensorCaptureFormat.sha256(command.encode().toByteArray(Charsets.UTF_8))
    private fun slotId(key: PatternCompatibility) = SensorCaptureFormat.sha256(listOf(key.localUserId, key.recipe.name,
        key.side, key.placement, key.deviceId, key.coordinates).joinToString("\n").toByteArray(Charsets.UTF_8))
    private fun encodeFrozen(f: FrozenPatternProfile) = JSONObject().put("runtimeHash", SensorCaptureFormat.sha256(PatternRuntimeCodec.encode(f.runtime)))
        .put("operation", f.operation.name).put("commandId", f.commandId).put("slotRevision", f.slotRevision)
        .put("sessionId", f.sessionId).put("setId", f.setId).put("attemptId", f.attemptId).put("watchClockId", f.watchClockId)
    private fun decodeFrozen(json: JSONObject) = FrozenPatternProfile(loadRuntime(json.getString("runtimeHash")),
        PatternProfileOperation.valueOf(json.getString("operation")), json.getString("commandId"), json.getLong("slotRevision"),
        json.getString("sessionId"), json.getString("setId"), json.getString("attemptId"), json.getString("watchClockId"))
    private fun trimReceipts(state: JSONObject) {
        val receipts = state.getJSONObject("receipts")
        val protected = mutableSetOf<String>()
        val slots = state.getJSONObject("slots")
        slots.keys().forEach { id ->
            val slot = slots.getJSONObject(id)
            listOf("pendingShadow", "pendingActive", "currentShadow", "currentActive", "previousActive", "disabledActive").forEach { field ->
                slot.optString(field).takeIf { it.isNotEmpty() }?.let { protected += PatternProfileCommand.decode(it).commandId }
            }
            listOf("frozenShadow", "frozenActive").forEach { field -> slot.optJSONObject(field)?.let { protected += it.getString("commandId") } }
            slot.optJSONObject("frozenEmptyActive")?.optString("disableCommandId")?.takeIf { it.isNotEmpty() }?.let { protected += it }
        }
        val removable = receipts.keys().asSequence().toList().filter { id -> id !in protected &&
            PatternProfileAck.decode(receipts.getJSONObject(id).getString("ack")).status != PatternProfileStatus.STAGED }
        removable.forEach { if (receipts.length() > 256) receipts.remove(it) }
        require(receipts.length() <= 256) { "Unresolved receipts retained; profile staging paused" }
    }
    companion object { private val lock = Any() }
}
