package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.WearSync

/** The latest desired command stays durable until the watch commits and acknowledges it. */
internal enum class CommandAckResult { IGNORED, APPLIED, RETRY, RESYNC, BLOCKED, ALREADY_CLOSED }

class PhoneCommandOutbox(context: Context) {
    private val prefs = context.getSharedPreferences("wear-command-outbox", Context.MODE_PRIVATE)

    fun enqueue(payload: String): String = synchronized(lock) {
        val f = WearSync.parse(payload)
        if (f.getOrNull(1) == "QUERY") return@synchronized payload
        val rich = if (f.firstOrNull() == "1") {
            require(f.size >= 6)
            WearSync.parse(WearSync.richCommand(f[1], f[2], f[3], "", f[4].toInt(), f[5].toInt(), null, 0)).toMutableList()
        } else {
            require(f.size >= 10 && f[0] == "2")
            f.toMutableList()
        }
        val revision = Math.addExact(prefs.getLong("revision", 0), 1L)
        if (rich.size == 10) rich.add(revision.toString()) else rich[10] = revision.toString()
        val stamped = rich.joinToString("|")
        check(prefs.edit().putLong("revision", revision).putString("pending", stamped).remove("ackStatus").remove("deliveryBlocked").commit()) { "Failed to persist watch command" }
        stamped
    }

    fun pending(): String? = synchronized(lock) { prefs.getString("pending", null) }

    fun directionStoredOnWatch(profile: dev.kyungmin.kcoach.core.StartDirectionRuntimeProfile): Boolean = synchronized(lock) {
        prefs.getString("directionStoredHash", null) == dev.kyungmin.kcoach.core.StartDirectionRuntimeCodec.hash(
            dev.kyungmin.kcoach.core.StartDirectionRuntimeCodec.encode(profile))
    }

    fun pendingForDelivery(): String? = synchronized(lock) {
        if (prefs.getBoolean("deliveryBlocked", false)) null else prefs.getString("pending", null)
    }

    fun acknowledge(payload: String): Boolean = receiveAck(payload) in setOf(CommandAckResult.APPLIED, CommandAckResult.ALREADY_CLOSED)

    internal fun receiveAck(payload: String): CommandAckResult = synchronized(lock) {
        val ack = WearSync.parse(payload)
        val pending = prefs.getString("pending", null)?.let(WearSync::parse) ?: return@synchronized CommandAckResult.IGNORED
        if (ack.size !in 4..7 || ack[0] != "1" || ack[1] != pending[2] || ack[2] != pending.getOrNull(10)) return@synchronized CommandAckResult.IGNORED
        val direction = pending.getOrNull(20).orEmpty()
        if (ack[3] in setOf("APPLIED", "DUPLICATE") && direction.isNotEmpty() &&
            ack.getOrNull(5) != dev.kyungmin.kcoach.core.StartDirectionRuntimeCodec.hash(direction)) return@synchronized CommandAckResult.IGNORED
        val tempo = pending.getOrNull(21).orEmpty()
        if (ack[3] in setOf("APPLIED", "DUPLICATE") && tempo.isNotEmpty() &&
            ack.getOrNull(6) != dev.kyungmin.kcoach.core.WorkoutTempoSettingsCodec.hash(tempo)) return@synchronized CommandAckResult.IGNORED
        val peerRevision = if (ack.size >= 5) ack[4].toLongOrNull()?.takeIf { it in 0 until Long.MAX_VALUE }
            ?: return@synchronized CommandAckResult.IGNORED else null
        if (peerRevision != null && ack[3] in setOf("APPLIED", "DUPLICATE") && peerRevision != ack[2].toLong()) return@synchronized CommandAckResult.IGNORED
        val result = when (ack[3]) {
            "APPLIED", "DUPLICATE" -> CommandAckResult.APPLIED
            "WAITING_COMPLETION", "CAPTURE_RECOVERY_REQUIRED" -> CommandAckResult.RETRY
            "WRONG_SESSION" -> CommandAckResult.RESYNC
            "STALE" -> if (peerRevision != null && peerRevision >= ack[2].toLong()) CommandAckResult.RESYNC else CommandAckResult.BLOCKED
            "CLOSED_SESSION" -> if (pending[1] in setOf("STOP", "END", "COMPLETE")) CommandAckResult.ALREADY_CLOSED else CommandAckResult.BLOCKED
            "INVALID", "PATTERN_NOT_READY", "PATTERN_BINDING_CHANGED", "START_CONTEXT_CHANGED", "START_PROFILE_CHANGED", "TEMPO_SETTINGS_CHANGED" -> CommandAckResult.BLOCKED
            else -> return@synchronized CommandAckResult.IGNORED
        }
        val edit = prefs.edit().putString("ackStatus", ack[3])
        if (result == CommandAckResult.APPLIED && direction.isNotEmpty()) {
            edit.putString("directionStoredHash", ack[5]).putString("directionStoredSession", pending[2])
                .putLong("directionStoredAtMs", System.currentTimeMillis())
        }
        when (result) {
            CommandAckResult.APPLIED, CommandAckResult.ALREADY_CLOSED -> edit.remove("pending").remove("deliveryBlocked")
            CommandAckResult.RESYNC, CommandAckResult.BLOCKED -> edit.putBoolean("deliveryBlocked", true)
            CommandAckResult.RETRY -> edit.putBoolean("deliveryBlocked", false)
            else -> Unit
        }
        if (result == CommandAckResult.RESYNC && peerRevision != null) {
            edit.putLong("revision", maxOf(prefs.getLong("revision", 0L), peerRevision))
        }
        check(edit.commit()) { "Failed to persist command acknowledgement disposition" }
        result
    }

    companion object { private val lock = Any() }
}
