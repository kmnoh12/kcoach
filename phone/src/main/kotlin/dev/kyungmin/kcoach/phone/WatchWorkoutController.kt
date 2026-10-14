package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*

class WatchWorkoutController(private val context: Context, private val send: (String, String) -> Unit) {
    private val store = SessionStore(context)
    fun receive(payload: String, nowMs: Long): Result<WatchWorkoutApplication> = runCatching {
        val command = WatchWorkoutControl.decode(payload)
        // Confirm wearing before taking the session lock: the candidate/source lock has the opposite order.
        if (command.action == WatchWorkoutAction.START_PREPARED) {
            val offer = store.preparedForWatch(nowMs).getOrThrow()
            if (offer != null && command.sessionId == offer.sessionId && command.planHash == offer.planHash &&
                command.setId == offer.setId && command.attemptId == offer.attemptId) {
                val wearing = command.side?.let { WorkoutWearingSelection(it, requireNotNull(command.placement)) }
                WorkoutWearingStore(context).beginSession(command.sessionId, wearing).getOrThrow()
            }
        }
        val result = store.applyWatchControl(command, nowMs).getOrThrow()
        // A committed transition is replayable if transmission fails before its receipt is delivered.
        if (result.snapshot != null) {
            if (command.action == WatchWorkoutAction.START_PREPARED && result.ack.status == WatchWorkoutControlStatus.APPLIED)
                send(WearSync.PATH_COMMAND, WorkoutWearingStore(context).bind(PhoneSessionController.command(result.snapshot, "START")).getOrThrow())
            else PhoneSessionController(store) { path, value ->
                send(path, if (path == WearSync.PATH_COMMAND) WorkoutWearingStore(context).bind(value).getOrThrow() else value)
            }.resync().getOrThrow()
        }
        send(WearSync.PATH_WORKOUT_CONTROL_ACK, result.ack.encode())
        sendOffer(nowMs)
        result
    }
    fun sendOffer(nowMs: Long): Result<Unit> = runCatching {
        send(WearSync.PATH_WORKOUT_OFFER, WireCodec.encode(WireEnvelope(messageType = WireMessageType.HEARTBEAT,
            sessionId = "workout-controls", source = WireSource.PHONE, sentAtElapsedMs = 0, sentAtWallMs = 0,
            payload = mapOf("kind" to "WORKOUT_CONTROL_SUPPORT_V1"))))
        store.preparedForWatch(nowMs).getOrThrow()?.let { send(WearSync.PATH_WORKOUT_OFFER, it.encode()) }
    }
}
