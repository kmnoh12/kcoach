package dev.kyungmin.kcoach.phone

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import dev.kyungmin.kcoach.core.EmulatorBridgeClient
import dev.kyungmin.kcoach.core.WearSync
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PhoneWearBridge(private val context: Context) {
    private val emulatorBridge = EmulatorBridgeClient { _, _ -> }
    private val commandOutbox = PhoneCommandOutbox(context)
    private val queueLock = Any()
    private val pendingMessages = linkedMapOf<String, Pair<String, String>>()
    private var draining = false
    private var closed = false
    private val outboundExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kcoach-phone-wear-outbound").apply { isDaemon = true }
    }

    fun sendHello() {
        sendPath(WearSync.PATH_PAIR, WearSync.hello("phone"))
    }

    fun sendStart(
        sessionId: String,
        exerciseId: String,
        exerciseName: String,
        setIndex: Int,
        targetReps: Int,
        weightKg: Double?,
        restSeconds: Int,
    ) {
        sendPath(
            WearSync.PATH_COMMAND,
            WearSync.richCommand("START", sessionId, exerciseId, exerciseName, setIndex, targetReps, weightKg, restSeconds),
        )
    }

    fun sendSet(
        sessionId: String,
        exerciseId: String,
        exerciseName: String,
        setIndex: Int,
        targetReps: Int,
        weightKg: Double?,
        restSeconds: Int,
    ) {
        sendPath(
            WearSync.PATH_COMMAND,
            WearSync.richCommand("SET", sessionId, exerciseId, exerciseName, setIndex, targetReps, weightKg, restSeconds),
        )
    }

    fun sendRest(sessionId: String, restSeconds: Int, restEndAtMs: Long) {
        sendPath(
            WearSync.PATH_COMMAND,
            WearSync.richCommand("REST", sessionId, "", "휴식", 0, 0, null, restSeconds, restEndAtMs),
        )
    }

    fun sendPause(sessionId: String) = sendLifecycle("PAUSE", sessionId)

    fun sendResume(sessionId: String) = sendLifecycle("RESUME", sessionId)

    fun sendStop(sessionId: String) = sendLifecycle("STOP", sessionId)

    fun sendComplete(sessionId: String) = sendLifecycle("COMPLETE", sessionId)

    fun sendHeartbeat() {
        sendPath(WearSync.PATH_HEARTBEAT, WearSync.heartbeat("phone"))
        retryPendingCommand()
    }

    fun retryPendingCommand() { commandOutbox.pendingForDelivery()?.let { dispatch(WearSync.PATH_COMMAND, it) } }

    fun hasPendingDelivery(): Boolean = synchronized(queueLock) { draining || pendingMessages.isNotEmpty() }

    fun sendRaw(path: String, payload: String) = sendPath(path, payload)

    fun close() {
        synchronized(queueLock) { closed = true; pendingMessages.clear() }
        outboundExecutor.shutdown()
        emulatorBridge.stop()
    }

    private fun sendLifecycle(type: String, sessionId: String) {
        sendPath(WearSync.PATH_COMMAND, WearSync.command(type, sessionId, "", 0, 0))
    }

    private fun sendPath(path: String, payload: String) {
        val durable = if (path == WearSync.PATH_COMMAND) commandOutbox.enqueue(payload) else payload
        dispatch(path, durable)
    }

    private fun dispatch(path: String, payload: String) {
        synchronized(queueLock) {
            if (closed) return
            val key = if (path == WearSync.PATH_COMMAND && WearSync.parse(payload).getOrNull(1) == "QUERY") "$path:query" else path
            pendingMessages[key] = path to payload
            if (draining) return
            draining = true
            outboundExecutor.execute(::drain)
        }
    }

    /** Coalesce repeated heartbeats/desired state while the link is down; never grow an unbounded task backlog. */
    private fun drain() {
        while (true) {
            val message = synchronized(queueLock) {
                val entry = pendingMessages.entries.minByOrNull {
                    when (it.value.first) { WearSync.PATH_SET_ACK -> 0; WearSync.PATH_COMMAND -> 1; else -> 2 }
                }
                if (entry == null || closed) { draining = false; return }
                pendingMessages.remove(entry.key)
                entry.value
            }
            val path = message.first
            val payload = if (path == WearSync.PATH_COMMAND && WearSync.parse(message.second).getOrNull(1) != "QUERY") {
                commandOutbox.pendingForDelivery() ?: continue
            } else message.second
            runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes, 2, TimeUnit.SECONDS)
                nodes.forEach { node ->
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(node.id, path, payload.toByteArray()), 2, TimeUnit.SECONDS)
                }
            }.onFailure { Log.w("KCoachEvent", "watch command delivery deferred path=$path", it) }
            // Deterministic local-emulator path. The host relay is only used
            // for the two emulator instances and never for a real Watch8.
            if (Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk", true)) emulatorBridge.send(path, payload)
        }
    }
}
