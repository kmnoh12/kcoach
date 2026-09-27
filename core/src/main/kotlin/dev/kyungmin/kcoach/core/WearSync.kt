package dev.kyungmin.kcoach.core

/** Small, versioned wire format shared by the phone and Wear OS app. */
object WearSync {
    const val PROTOCOL_VERSION = CURRENT_WIRE_PROTOCOL_VERSION
    const val PATH_CONTROL_V3 = "/kcoach/v3/control"
    const val PATH_LIVE_V3 = "/kcoach/v3/live"
    const val PATH_CAPTURE_MANIFEST_V3 = "/kcoach/v3/capture/manifest"
    const val PATH_CAPTURE_ASSET_V3 = "/kcoach/v3/capture/asset"
    const val PATH_CAPTURE_ASSET_EMULATOR = "/kcoach/emulator/capture/asset"
    const val PATH_PERSONAL_PROFILE = "/kcoach/personal-profile"
    const val PATH_PERSONAL_PROFILE_DELETE = "/kcoach/personal-profile/delete"
    const val PATH_ACK_V3 = "/kcoach/v3/ack"
    const val PATH_COMMAND = "/kcoach/command"
    const val PATH_STATE = "/kcoach/state"
    const val PATH_LIVE = "/kcoach/live"
    const val PATH_SAMPLE = "/kcoach/sample"
    const val PATH_PAIR = "/kcoach/pair"
    const val PATH_HEARTBEAT = "/kcoach/heartbeat"
    const val PATH_WATCH_UI_READY = "/kcoach/watch-ui-ready"
    const val PATH_COMPLETE = "/kcoach/complete"
    const val KEY_PAYLOAD = "payload"
    const val KEY_SESSION = "session"

    // The emulator-only TCP relay runs on the host. Keep it separate from the
    // old adb-forwarded port so a stale forwarding rule cannot hijack traffic.
    const val EMULATOR_BRIDGE_HOST = "10.0.2.2"
    const val EMULATOR_BRIDGE_PORT = 8767

    fun command(type: String, sessionId: String, exerciseId: String, setIndex: Int, targetReps: Int): String =
        listOf("1", type, sessionId, exerciseId, setIndex, targetReps).joinToString("|")

    fun richCommand(
        type: String,
        sessionId: String,
        exerciseId: String,
        exerciseName: String,
        setIndex: Int,
        targetReps: Int,
        weightKg: Double?,
        restSeconds: Int,
        restEndAtMs: Long = 0L,
    ): String = listOf(
        "2", type, sessionId, exerciseId, exerciseName.replace('|', '/'), setIndex,
        targetReps, weightKg ?: "", restSeconds, restEndAtMs,
    ).joinToString("|")

    fun hello(device: String): String = listOf("1", device, System.currentTimeMillis()).joinToString("|")

    fun heartbeat(device: String): String = listOf("1", device, System.currentTimeMillis()).joinToString("|")

    fun watchUiReady(sessionId: String = ""): String = listOf("1", sessionId, System.currentTimeMillis()).joinToString("|")

    fun completion(
        sessionId: String,
        exerciseId: String,
        setIndex: Int,
        reps: Int,
        heartRate: Int,
        phase: String,
        sampleCount: Int,
    ): String = listOf(
        "1", sessionId, exerciseId, setIndex, reps, heartRate, phase, sampleCount, System.currentTimeMillis(),
    ).joinToString("|")

    fun state(sessionId: String, exerciseId: String, setIndex: Int, reps: Int, heartRate: Int, recording: Boolean): String =
        listOf("1", sessionId, exerciseId, setIndex, reps, heartRate, recording).joinToString("|")

    fun liveState(
        sessionId: String,
        exerciseId: String,
        exerciseName: String,
        setIndex: Int,
        reps: Int,
        targetReps: Int,
        weightKg: Double?,
        heartRate: Int,
        phase: String,
        recording: Boolean,
        restEndAtMs: Long = 0L,
        sampleCount: Int = 0,
        trackingProfileId: String = "",
        personalized: Boolean = false,
    ): String = listOf(
        "2", sessionId, exerciseId, exerciseName.replace('|', '/'), setIndex, reps,
        targetReps, weightKg ?: "", heartRate, phase, recording, System.currentTimeMillis(),
        restEndAtMs, sampleCount, trackingProfileId.replace('|', '/'), personalized,
    ).joinToString("|")

    fun sample(sessionId: String, timestamp: Long, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float): String =
        listOf("1", sessionId, timestamp, ax, ay, az, gx, gy, gz).joinToString("|")

    fun parse(payload: String): List<String> = payload.split('|')
}
