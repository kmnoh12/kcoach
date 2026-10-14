package dev.kyungmin.kcoach.wear

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject

data class PendingWatchWorkoutControl(val command: WatchWorkoutControl, val ack: WatchWorkoutControlAck? = null)

class WatchWorkoutOutbox(context: Context) {
    private val prefs = context.getSharedPreferences("watch-workout-controls", Context.MODE_PRIVATE)
    fun receiveOffer(encoded: String): Boolean = synchronized(lock) {
        require(encoded.length <= 12_000)
        val envelope = WireCodec.decode(encoded)
        if (envelope.source == WireSource.PHONE && envelope.messageType == WireMessageType.HEARTBEAT &&
            envelope.sessionId == "workout-controls" && envelope.payload["kind"] == "WORKOUT_CONTROL_SUPPORT_V1")
            return@synchronized prefs.edit().putBoolean("controlSupported", true).commit()
        WatchWorkoutOffer.decode(encoded)
        prefs.edit().putString("offer", encoded).putBoolean("controlSupported", true).commit()
    }
    fun receivePhoneHello(fields: List<String>): Boolean = synchronized(lock) {
        require(fields.getOrNull(0) == "1" && fields.getOrNull(1) == "phone")
        prefs.edit().putBoolean("controlSupported", fields.getOrNull(3) == "WORKOUT_CONTROL_V1").commit()
    }
    fun supportsControls(): Boolean = synchronized(lock) { prefs.getBoolean("controlSupported", false) }
    fun offer(): WatchWorkoutOffer? = synchronized(lock) { prefs.getString("offer", null)?.let(WatchWorkoutOffer::decode) }
    fun enqueue(command: WatchWorkoutControl): Boolean = synchronized(lock) {
        val rows = read().toMutableList()
        rows.firstOrNull { it.command.commandId == command.commandId }?.let { return@synchronized it.command == command }
        if (rows.any { it.ack == null }) return@synchronized false
        if (rows.size >= 256) rows.removeAt(0) // Every retained row here has a terminal acknowledgement.
        rows += PendingWatchWorkoutControl(command)
        write(rows)
    }
    fun pending(): List<WatchWorkoutControl> = synchronized(lock) { read().filter { it.ack == null }.map { it.command } }
    fun latest(): PendingWatchWorkoutControl? = synchronized(lock) { read().lastOrNull() }
    fun acknowledge(ack: WatchWorkoutControlAck): Boolean = synchronized(lock) {
        val rows = read().toMutableList()
        val index = rows.indexOfFirst { it.command.commandId == ack.commandId && it.command.sessionId == ack.sessionId &&
            it.command.setId == ack.setId && it.command.attemptId == ack.attemptId }
        if (index < 0) return@synchronized false
        if (rows[index].ack != null) return@synchronized rows[index].ack == ack ||
            rows[index].ack!!.status in setOf(WatchWorkoutControlStatus.APPLIED, WatchWorkoutControlStatus.DUPLICATE) &&
                ack.status in setOf(WatchWorkoutControlStatus.APPLIED, WatchWorkoutControlStatus.DUPLICATE)
        val command = rows[index].command
        rows[index] = rows[index].copy(ack = ack)
        if (command.action == WatchWorkoutAction.START_PREPARED &&
            ack.status in setOf(WatchWorkoutControlStatus.APPLIED, WatchWorkoutControlStatus.DUPLICATE,
                WatchWorkoutControlStatus.NO_PLAN, WatchWorkoutControlStatus.STALE) && offer()?.sessionId == command.sessionId)
            return@synchronized write(rows, clearOffer = true)
        write(rows)
    }
    private fun read(): List<PendingWatchWorkoutControl> {
        val array = JSONArray(prefs.getString("entries", "[]")!!)
        return (0 until array.length()).map { index -> array.getJSONObject(index).let {
            PendingWatchWorkoutControl(WatchWorkoutControl.decode(it.getString("command")),
                it.optString("ack").takeIf { value -> value.isNotEmpty() }?.let(WatchWorkoutControlAck::decode))
        } }
    }
    private fun write(rows: List<PendingWatchWorkoutControl>, clearOffer: Boolean = false): Boolean {
        val edit = prefs.edit().putString("entries", JSONArray(rows.map { JSONObject().put("command", it.command.encode())
            .put("ack", it.ack?.encode().orEmpty()) }).toString())
        if (clearOffer) edit.remove("offer")
        return edit.commit()
    }
    companion object { private val lock = Any() }
}
