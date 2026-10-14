package dev.kyungmin.kcoach.updates

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One OTA transaction per app; never shares or replaces the workout command outbox. */
class OtaStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("companion-ota", Context.MODE_PRIVATE)
    fun instance(): String = synchronized(lock) {
        prefs.getString("instance", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("instance", it).commit())
        }
    }
    fun local(): OtaDeviceState? = synchronized(lock) { prefs.getString("local", null)?.let(OtaDeviceState::decode) }
    fun peer(): OtaDeviceState? = synchronized(lock) { prefs.getString("peer", null)?.let(OtaDeviceState::decode) }
    fun command(): OtaCommand? = synchronized(lock) { prefs.getString("command", null)?.let(OtaCommand::decode) }
    fun nextSequence(): Long = synchronized(lock) {
        val value = Math.addExact(prefs.getLong("sequence", 0), 1)
        check(prefs.edit().putLong("sequence", value).commit()); value
    }
    fun begin(command: OtaCommand, state: OtaDeviceState) = synchronized(lock) {
        check(prefs.edit().putString("command", command.encode()).putString("local", state.encode())
            .remove("peer").remove("peerNode").remove("sessionId").putBoolean("selfAfterPeer", false).commit())
    }
    fun saveCommand(command: OtaCommand) = synchronized(lock) {
        check(prefs.edit().putString("command", command.encode()).commit())
    }
    fun saveLocal(state: OtaDeviceState): Boolean = synchronized(lock) {
        if (local()?.id != state.id) return false
        check(prefs.edit().putString("local", state.encode()).commit()); true
    }
    fun acceptPeer(state: OtaDeviceState, node: String): Boolean = synchronized(lock) {
        val command = command() ?: return false
        if (state.id != command.id || state.commandSequence > command.sequence) return false
        val previous = peer()
        if (previous != null && (previous.instance != state.instance || state.revision <= previous.revision)) return false
        val pinnedNode = prefs.getString("peerNode", null)
        if (pinnedNode != null && pinnedNode != node) return false
        check(prefs.edit().putString("peer", state.encode()).putString("peerNode", node).commit()); true
    }
    fun receiveCommand(command: OtaCommand): Boolean = synchronized(lock) {
        val seen = JSONObject(prefs.getString("received", "{}")!!)
        val previous = seen.optJSONObject(command.senderInstance)
        if (previous != null) {
            if (command.sequence < previous.getLong("sequence")) return false
            if (command.sequence == previous.getLong("sequence")) {
                require(previous.getString("payload") == command.encode()) { "OTA sequence reused with different command" }
                return false
            }
        }
        require(seen.length() < 32 || seen.has(command.senderInstance))
        seen.put(command.senderInstance, JSONObject().put("sequence", command.sequence).put("payload", command.encode()))
        check(prefs.edit().putString("received", seen.toString()).commit()); true
    }
    fun waitingForPeer(): Boolean = prefs.getBoolean("selfAfterPeer", false)
    fun waitForPeer(value: Boolean) { check(prefs.edit().putBoolean("selfAfterPeer", value).commit()) }
    fun sessionId(): Int = prefs.getInt("sessionId", -1)
    fun setSessionId(value: Int) { check(prefs.edit().putInt("sessionId", value).commit()) }
    fun apk(id: String): File {
        require(id.matches(OTA_ID))
        return File(context.cacheDir, "updates/$id/kcoach-update.apk")
    }
    companion object { private val lock = Any() }
}
