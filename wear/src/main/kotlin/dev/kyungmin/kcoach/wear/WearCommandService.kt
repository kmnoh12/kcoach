package dev.kyungmin.kcoach.wear

import android.content.Intent
import android.os.Build
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import androidx.core.content.ContextCompat
import dev.kyungmin.kcoach.core.EmulatorBridgeClient
import dev.kyungmin.kcoach.core.WearSync
import dev.kyungmin.kcoach.core.WireCodec
import dev.kyungmin.kcoach.core.WirePayloadKeys
import dev.kyungmin.kcoach.core.PersonalCalibrationProfileCodec
import org.json.JSONObject

class WearCommandService : WearableListenerService() {
    private var emulatorBridge: EmulatorBridgeClient? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk_gwear", true)) emulatorBridge = EmulatorBridgeClient(::handlePayload).also { it.start() }
        sendPairHello()
    }

    override fun onDestroy() {
        emulatorBridge?.stop()
        emulatorBridge = null
        super.onDestroy()
    }

    override fun onMessageReceived(event: MessageEvent) {
        handlePayload(event.path, String(event.data))
    }

    private fun handlePayload(path: String, payload: String) {
        if (path == WearSync.PATH_SET_ACK) {
            ContextCompat.startForegroundService(this, Intent(this, WearWorkoutService::class.java)
                .putExtra(WearWorkoutService.EXTRA_COMMAND, "SET_ACK").putExtra("payload", payload))
            return
        }
        if (path == WearSync.PATH_PROBE_START || path == WearSync.PATH_PROBE_STOP) {
            handleProbe(start = path == WearSync.PATH_PROBE_START)
            return
        }
        if (path == WearSync.PATH_HEARTBEAT) {
            val state = getSharedPreferences("workout-runtime", MODE_PRIVATE).getString("state", null)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val ownsSession = state?.optString("session").orEmpty().isNotBlank()
            val hasCompletion = state != null && !state.isNull("pendingCompletion")
            val hasCaptures = getSharedPreferences("capture-outbox", MODE_PRIVATE).getStringSet("pendingCaptureIds", emptySet()).orEmpty().isNotEmpty()
            if (!ownsSession && !hasCompletion && !hasCaptures) return
            dispatchCommand("HEARTBEAT", emptyList())
            return
        }
        if (path == WearSync.PATH_ACK_V3) {
            val envelope = runCatching { WireCodec.decode(payload) }.getOrNull() ?: return
            val captureId = envelope.payload[WirePayloadKeys.ACK_CAPTURE_ID] ?: return
            dispatchCommand("CAPTURE_ACK", listOf(captureId))
            return
        }
        if (path == WearSync.PATH_PERSONAL_PROFILE) {
            val profile = runCatching { PersonalCalibrationProfileCodec.decode(payload) }.getOrNull() ?: return
            getSharedPreferences("personal-profiles", MODE_PRIVATE)
                .edit()
                .putString(profile.exerciseId, payload)
                .commit()
            return
        }
        if (path == WearSync.PATH_PERSONAL_PROFILE_DELETE) {
            val exerciseId = payload.trim()
            if (exerciseId.isBlank()) return
            getSharedPreferences("personal-profiles", MODE_PRIVATE)
                .edit()
                .remove(exerciseId)
                .commit()
            return
        }
        if (path != WearSync.PATH_COMMAND && path != WearSync.PATH_PAIR) return
        val fields = WearSync.parse(payload)
        if (path == WearSync.PATH_PAIR) {
            dispatchCommand("HELLO", fields)
            return
        }
        if (fields.size < 2) return
        dispatchCommand(fields[1], fields)
    }

    private fun dispatchCommand(type: String, fields: List<String>) {
        val intent = Intent(this, WearWorkoutService::class.java).apply {
            putExtra(WearWorkoutService.EXTRA_COMMAND, type)
            if (type == "CAPTURE_ACK") {
                putExtra("captureId", fields.firstOrNull().orEmpty())
            } else if (fields.firstOrNull() == "2") {
                putExtra("session", fields.getOrNull(2).orEmpty())
                putExtra("exerciseId", fields.getOrNull(3).orEmpty())
                putExtra("exerciseName", fields.getOrNull(4).orEmpty())
                putExtra("setIndex", fields.getOrNull(5)?.toIntOrNull() ?: 0)
                putExtra("targetReps", fields.getOrNull(6)?.toIntOrNull() ?: 10)
                putExtra("weightKg", fields.getOrNull(7)?.toDoubleOrNull() ?: Double.NaN)
                putExtra("restSeconds", fields.getOrNull(8)?.toIntOrNull() ?: 60)
                putExtra("restEndAtMs", fields.getOrNull(9)?.toLongOrNull() ?: 0L)
                putExtra("commandRevision", fields.getOrNull(10)?.toLongOrNull() ?: 0L)
                putExtra("syncPhase", fields.getOrNull(11).orEmpty())
                putExtra("pausedRestRemainingMs", fields.getOrNull(12)?.toLongOrNull() ?: 0L)
                putExtra("setId", fields.getOrNull(13).orEmpty())
                putExtra("attemptId", fields.getOrNull(14).orEmpty())
            } else {
                putExtra("session", fields.getOrNull(2).orEmpty())
                putExtra("exerciseId", fields.getOrNull(3).orEmpty())
                putExtra("setIndex", fields.getOrNull(4)?.toIntOrNull() ?: 0)
                putExtra("targetReps", fields.getOrNull(5)?.toIntOrNull() ?: 10)
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }

    /** The phone's probe screen opened or closed: stream (or stop) without anyone touching the watch. */
    private fun handleProbe(start: Boolean) {
        val service = Intent(this, ProbeService::class.java)
        if (!start) {
            stopService(service)
            return
        }
        if (!ProbeStatus.running) runCatching { ContextCompat.startForegroundService(this, service) }
        // Bring the probe screen up when the system lets us (the app is on screen); the ongoing activity covers the rest.
        runCatching {
            startActivity(
                Intent(this, ProbeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    private fun sendPairHello() {
        Thread {
            runCatching {
                emulatorBridge?.send(WearSync.PATH_PAIR, WearSync.hello("watch"))
            }
        }.start()
    }
}
