package dev.kyungmin.kcoach.phone

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import dev.kyungmin.kcoach.core.EmulatorBridgeClient
import dev.kyungmin.kcoach.core.WearSync
import java.util.concurrent.Executors

class PhoneWearBridge(private val context: Context) {
    private val emulatorBridge = EmulatorBridgeClient { _, _ -> }
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

    fun sendHeartbeat() = sendPath(WearSync.PATH_HEARTBEAT, WearSync.heartbeat("phone"))

    fun sendRaw(path: String, payload: String) = sendPath(path, payload)

    fun close() {
        outboundExecutor.shutdown()
        emulatorBridge.stop()
    }

    private fun sendLifecycle(type: String, sessionId: String) {
        sendPath(WearSync.PATH_COMMAND, WearSync.command(type, sessionId, "", 0, 0))
    }

    private fun sendPath(path: String, payload: String) {
        outboundExecutor.execute {
            runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                nodes.forEach { node ->
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(node.id, path, payload.toByteArray()))
                }
            }
            // Deterministic local-emulator path. The host relay is only used
            // for the two emulator instances and never for a real Watch8.
            emulatorBridge.send(path, payload)
        }
    }
}
