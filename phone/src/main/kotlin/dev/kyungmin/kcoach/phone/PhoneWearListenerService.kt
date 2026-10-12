package dev.kyungmin.kcoach.phone

import android.content.Intent
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.kyungmin.kcoach.core.EmulatorBridgeClient
import dev.kyungmin.kcoach.core.CaptureState
import dev.kyungmin.kcoach.core.PhoneRepAnalyzer
import dev.kyungmin.kcoach.core.WearSync
import dev.kyungmin.kcoach.core.WireCodec
import dev.kyungmin.kcoach.core.WireMessages
import dev.kyungmin.kcoach.core.WirePayloadKeys
import dev.kyungmin.kcoach.core.WireSource
import dev.kyungmin.kcoach.core.KCoachEventMarker
import dev.kyungmin.kcoach.core.KCoachEventTrace
import dev.kyungmin.kcoach.core.PersonalCalibrationBuilder
import dev.kyungmin.kcoach.core.PersonalCalibrationProfileCodec
import dev.kyungmin.kcoach.core.CalibrationBuildResult
import dev.kyungmin.kcoach.core.ExerciseTrackingProfiles
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import android.util.Log
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Receives both small messages and large set capture Assets from Wear OS. */
class PhoneWearListenerService : WearableListenerService() {
    private val prefs by lazy { getSharedPreferences("wear-sync", MODE_PRIVATE) }
    private var emulatorBridge: EmulatorBridgeClient? = null
    private lateinit var captureInbox: CaptureInbox
    private val analysisExecutor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(64),
        { runnable -> Thread(runnable, "kcoach-capture-analysis").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val inboxExecutor = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { runnable -> Thread(runnable, "kcoach-capture-inbox").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    override fun onCreate() {
        super.onCreate()
        captureInbox = CaptureInbox(this, prefs)
        emulatorBridge = EmulatorBridgeClient(::handlePayload).also { it.start() }
        inboxExecutor.execute {
            captureInbox.pendingAnalysis().forEach { received ->
                Log.i(TAG, "retrying persisted capture id=${received.validated.captureId}")
                scheduleAnalysis(received)
            }
        }
    }

    override fun onDestroy() {
        emulatorBridge?.stop()
        emulatorBridge = null
        inboxExecutor.shutdown()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    override fun onMessageReceived(event: MessageEvent) {
        handlePayload(event.path, String(event.data))
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (!item.uri.path.orEmpty().startsWith(WearSync.PATH_CAPTURE_ASSET_V3)) continue
            receiveCaptureAsset(item)
        }
    }

    private fun receiveCaptureAsset(item: com.google.android.gms.wearable.DataItem) {
        val map = DataMapItem.fromDataItem(item).dataMap
        val encodedManifest = map.getString("wireManifest") ?: return
        val manifest = runCatching { WireCodec.decode(encodedManifest) }.getOrNull() ?: return
        if (manifest.messageType.name != "CAPTURE_MANIFEST" || manifest.source != WireSource.WATCH) return
        val captureId = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty()
        val asset = map.getAsset("captureAsset") ?: return
        val sourceNodeId = item.uri.host.orEmpty()
        if (captureId.isBlank() || sourceNodeId.isBlank()) return
        if (prefs.getBoolean("capture:$captureId:acked", false)) {
            sendCaptureAck(sourceNodeId, manifest.sessionId, manifest.messageId, captureId)
                .addOnSuccessListener { Wearable.getDataClient(this).deleteDataItems(item.uri) }
            return
        }

        val fdSettled = AtomicBoolean(false)
        val fdHandler = Handler(Looper.getMainLooper())
        val fdTimeout = Runnable {
            if (fdSettled.compareAndSet(false, true)) {
                reportCaptureError(manifest, TimeoutException("asset fd timeout"), "asset_fd")
            }
        }
        fdHandler.postDelayed(fdTimeout, CAPTURE_ASSET_TIMEOUT_MS)
        Wearable.getDataClient(this).getFdForAsset(asset)
            .addOnFailureListener { error ->
                if (fdSettled.compareAndSet(false, true)) {
                    fdHandler.removeCallbacks(fdTimeout)
                    reportCaptureError(manifest, error, "asset_fd")
                }
            }
            .addOnSuccessListener { response ->
            if (!fdSettled.compareAndSet(false, true)) {
                runCatching { response.release() }
                return@addOnSuccessListener
            }
            fdHandler.removeCallbacks(fdTimeout)
            try {
                inboxExecutor.execute {
                    runCatching {
                        val bytes = response.inputStream.use { it.readBytes() }
                        response.release()
                        logEvent(KCoachEventMarker.CAPTURE_ASSET_RECEIVED, manifest)
                        enqueueCapture(manifest, bytes) {
                            sendCaptureAck(sourceNodeId, manifest.sessionId, manifest.messageId, captureId)
                                .addOnSuccessListener {
                                    finishAck(manifest)
                                    Wearable.getDataClient(this).deleteDataItems(item.uri)
                                }
                        }
                    }.onFailure {
                        runCatching { response.release() }
                        reportCaptureError(manifest, it, "asset_read")
                    }
                }
            } catch (error: RejectedExecutionException) {
                runCatching { response.release() }
                reportCaptureError(manifest, error, "inbox_queue")
            }
        }
    }

    private fun receiveEmulatorCapture(payload: String) {
        runCatching {
            val fields = payload.split("|", limit = 3)
            require(fields.size == 3) { "invalid emulator capture payload" }
            val captureId = fields[0]
            val manifestText = String(Base64.getDecoder().decode(fields[1]), Charsets.UTF_8)
            val bytes = Base64.getDecoder().decode(fields[2])
            Log.i(TAG, "emulator capture received id=$captureId bytes=${bytes.size}")
            val manifest = WireCodec.decode(manifestText)
            require(manifest.payload[WirePayloadKeys.CAPTURE_ID] == captureId) { "capture id mismatch" }
            logEvent(KCoachEventMarker.CAPTURE_ASSET_RECEIVED, manifest)
            enqueueCapture(manifest, bytes) {
                val ack = WireCodec.encode(
                    WireMessages.ack(WireSource.PHONE, manifest.sessionId, manifest.messageId, captureId),
                )
                check(emulatorBridge?.send(WearSync.PATH_ACK_V3, ack) == true) {
                    "emulator ACK send failed"
                }
                finishAck(manifest)
            }
        }.onFailure {
            Log.e(TAG, "emulator capture rejected", it)
            sendBroadcast(Intent(ACTION_CAPTURE_ERROR).setPackage(packageName)
                .putExtra("error", it.message ?: "emulator capture verification failed"))
        }
    }

    private fun enqueueCapture(
        manifest: dev.kyungmin.kcoach.core.WireEnvelope,
        bytes: ByteArray,
        acknowledge: () -> Unit,
    ) {
        val captureId = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty()
        if (prefs.getBoolean("capture:$captureId:acked", false)) {
            runCatching(acknowledge).onFailure { reportCaptureError(manifest, it, "ack") }
            return
        }
        if (
            captureInbox.analysisAttempted(captureId) ||
            captureInbox.state(captureId) == CaptureState.ANALYZING
        ) {
            runCatching(acknowledge).onFailure { reportCaptureError(manifest, it, "ack") }
            return
        }
        val received = captureInbox.receive(manifest, bytes).getOrElse {
            reportCaptureError(manifest, it, "validation")
            return
        }
        logEvent(KCoachEventMarker.CAPTURE_VALIDATED, manifest)
        broadcastCaptureState(manifest, CaptureState.RECEIVED)
        if (manifest.sessionId.startsWith("calibration-")) {
            prefs.edit()
                .putString("calibration:${manifest.payload[WirePayloadKeys.EXERCISE_ID].orEmpty()}:status", "RAW_SAVED")
                .putLong("calibration:${manifest.payload[WirePayloadKeys.EXERCISE_ID].orEmpty()}:rawSavedAt", System.currentTimeMillis())
                .commit()
            sendBroadcast(
                Intent(ACTION_CALIBRATION_RAW_SAVED).setPackage(packageName)
                    .putExtra("captureId", captureId)
                    .putExtra("sessionId", manifest.sessionId)
                    .putExtra("exerciseId", manifest.payload[WirePayloadKeys.EXERCISE_ID].orEmpty())
                    .putExtra("sampleCount", received.validated.document.sampleCount),
            )
        }
        // Raw bytes and metadata are already atomically committed on the phone.
        // Release the watch outbox without waiting for profile analysis.
        runCatching(acknowledge).onFailure { reportCaptureError(manifest, it, "ack") }
        scheduleAnalysis(received)
    }

    private fun scheduleAnalysis(received: ReceivedCapture) {
        val manifest = received.manifest
        val captureId = received.validated.captureId
        captureInbox.markState(captureId, CaptureState.ANALYZING)
        broadcastCaptureState(manifest, CaptureState.ANALYZING)
        try {
            analysisExecutor.execute {
                val succeeded = runCatching {
                    analyzeAndPersistCapture(received)
                }.onFailure {
                    captureInbox.markState(captureId, CaptureState.ANALYSIS_FAILED, it.message)
                    reportCaptureError(manifest, it, "analysis")
                }.isSuccess
                captureInbox.markAnalysisAttempted(captureId, succeeded)
                if (succeeded && prefs.getBoolean("capture:$captureId:acked", false)) {
                    captureInbox.markState(captureId, CaptureState.ACKED)
                    broadcastCaptureState(manifest, CaptureState.ACKED)
                }
            }
        } catch (error: RejectedExecutionException) {
            captureInbox.markState(captureId, CaptureState.ANALYSIS_FAILED, "analysis queue full")
            reportCaptureError(manifest, error, "queue")
        }
    }

    private fun analyzeAndPersistCapture(received: ReceivedCapture) {
        val manifest = received.manifest
        val document = received.validated.document
        val captureId = received.validated.captureId
        val watchReps = manifest.payload[WirePayloadKeys.WATCH_REPS]?.toIntOrNull()
        val exerciseId = manifest.payload[WirePayloadKeys.EXERCISE_ID].orEmpty()
        val exerciseName = manifest.payload[WirePayloadKeys.EXERCISE_NAME].orEmpty()
        logEvent(KCoachEventMarker.ANALYSIS_STARTED, manifest)
        if (manifest.sessionId.startsWith("calibration-")) {
            persistCalibrationResult(manifest, document, exerciseId, exerciseName)
        }
        val personal = prefs.getString("personalProfile:$exerciseId", null)
            ?.let { runCatching { PersonalCalibrationProfileCodec.decode(it) }.getOrNull() }
        val trackingProfile = if (personal == null) {
            ExerciseTrackingProfiles.resolve(exerciseId, exerciseName)
        } else {
            PersonalCalibrationBuilder.personalizedTrackingProfile(
                personal,
                ExerciseTrackingProfiles.resolve(exerciseId, exerciseName),
            )
        }
        val analysis = PhoneRepAnalyzer.analyze(document, trackingProfile, watchReps).result
        logEvent(KCoachEventMarker.ANALYSIS_FINISHED, manifest)
        check(
            prefs.edit()
            .putString("analysis:$captureId", encodeAnalysis(analysis))
                .commit(),
        ) { "failed to persist capture analysis" }
        logEvent(KCoachEventMarker.RESULT_SAVED, manifest)
        sendBroadcast(Intent(ACTION_CAPTURE).setPackage(packageName)
            .putExtra("captureId", captureId)
            .putExtra("sessionId", manifest.sessionId)
            .putExtra("exerciseId", manifest.payload[WirePayloadKeys.EXERCISE_ID].orEmpty())
            .putExtra("exerciseName", manifest.payload[WirePayloadKeys.EXERCISE_NAME].orEmpty())
            .putExtra("setIndex", manifest.payload[WirePayloadKeys.SET_INDEX]?.toIntOrNull() ?: 0)
            .putExtra("sampleCount", document.sampleCount)
            .putExtra("watchReps", analysis.watchDetectedReps ?: -1)
            .putExtra("phoneReps", analysis.phoneReanalyzedReps ?: 0)
            .putExtra("confirmedReps", analysis.confirmedReps ?: 0)
            .putExtra("analysisConfidence", analysis.analysisConfidence)
            .putExtra("trackingMode", analysis.trackingMode.name)
            .putExtra("trackingProfileId", analysis.trackingProfileId)
            .putExtra("trackingStatus", analysis.trackingStatus)
            .putExtra("capabilityTier", analysis.capabilityTier.name)
            .putExtra("repConfidence", analysis.repConfidence ?: Double.NaN)
            .putExtra("wristMotionSpeed", analysis.wristMotionSpeed ?: Double.NaN)
            .putExtra("wristMotionRange", analysis.wristMotionRange ?: Double.NaN)
            .putExtra("tempoConsistency", analysis.tempoConsistency ?: -1)
            .putExtra("lateSetDegradation", analysis.lateSetDegradation ?: Double.NaN)
            .putExtra("signalQuality", analysis.signalQuality ?: Double.NaN)
            .putExtra("averageAngularSpeed", analysis.averageAngularSpeed ?: Double.NaN)
            .putExtra("relativeRom", analysis.relativeRom ?: Double.NaN))
    }

    private fun persistCalibrationResult(
        manifest: dev.kyungmin.kcoach.core.WireEnvelope,
        document: dev.kyungmin.kcoach.core.SensorCaptureDocument,
        exerciseId: String,
        exerciseName: String,
    ) {
        val result = PersonalCalibrationBuilder.build(document, exerciseId, exerciseName)
        val intent = Intent(ACTION_CALIBRATION_RESULT).setPackage(packageName)
            .putExtra("sessionId", manifest.sessionId)
            .putExtra("exerciseId", exerciseId)
        when (result) {
            is CalibrationBuildResult.Success -> {
                val encoded = PersonalCalibrationProfileCodec.encode(result.profile)
                check(
                    prefs.edit()
                        .putString("personalProfile:$exerciseId", encoded)
                        .putString("calibration:$exerciseId:status", "CALIBRATION_RESULT")
                        .putLong("calibration:$exerciseId:completedAt", System.currentTimeMillis())
                        .remove("calibration:$exerciseId:error")
                        .commit(),
                ) { "failed to persist personal calibration profile" }
                sendPersonalProfile(encoded)
                intent.putExtra("succeeded", true)
                    .putExtra("profile", encoded)
                    .putExtra("validReps", result.profile.validRepCount)
            }
            is CalibrationBuildResult.Failed -> {
                check(
                    prefs.edit()
                        .putString("calibration:$exerciseId:status", "CALIBRATION_FAILED")
                        .putString("calibration:$exerciseId:error", result.reason)
                        .putLong("calibration:$exerciseId:completedAt", System.currentTimeMillis())
                        .commit(),
                ) { "failed to persist calibration failure" }
                intent.putExtra("succeeded", false)
                    .putExtra("reason", result.reason)
                    .putExtra("validReps", result.detectedReps)
            }
        }
        sendBroadcast(intent)
    }

    /**
     * Profile delivery belongs to the listener service so it is not coupled to
     * MainActivity being visible when background capture analysis completes.
     */
    private fun sendPersonalProfile(encoded: String) {
        emulatorBridge?.send(WearSync.PATH_PERSONAL_PROFILE, encoded)
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(this).sendMessage(
                    node.id,
                    WearSync.PATH_PERSONAL_PROFILE,
                    encoded.toByteArray(),
                ).addOnFailureListener {
                    Log.w(TAG, "personal profile delivery failed node=${node.id}", it)
                }
            }
        }.addOnFailureListener {
            Log.w(TAG, "personal profile node lookup failed", it)
        }
    }

    private fun finishAck(manifest: dev.kyungmin.kcoach.core.WireEnvelope) {
        val captureId = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty()
        prefs.edit().putBoolean("capture:$captureId:acked", true).commit()
        if (captureInbox.analysisSucceeded(captureId)) {
            captureInbox.markState(captureId, CaptureState.ACKED)
            broadcastCaptureState(manifest, CaptureState.ACKED)
        }
        logEvent(KCoachEventMarker.CAPTURE_ACK_SENT, manifest)
    }

    private fun broadcastCaptureState(
        manifest: dev.kyungmin.kcoach.core.WireEnvelope,
        state: CaptureState,
    ) {
        sendBroadcast(
            Intent(ACTION_CAPTURE_STATE).setPackage(packageName)
                .putExtra("captureId", manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty())
                .putExtra("sessionId", manifest.sessionId)
                .putExtra("captureState", state.name),
        )
    }

    private fun reportCaptureError(
        manifest: dev.kyungmin.kcoach.core.WireEnvelope,
        error: Throwable,
        stage: String,
    ) {
        Log.e(TAG, "$stage capture failure", error)
        logEvent(KCoachEventMarker.EVENT_FAILED, manifest, "$stage:${error.message}")
        sendBroadcast(
            Intent(ACTION_CAPTURE_ERROR).setPackage(packageName)
                .putExtra("captureId", manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty())
                .putExtra("sessionId", manifest.sessionId)
                .putExtra("stage", stage)
                .putExtra("error", error.message ?: "$stage capture failure"),
        )
    }

    private fun encodeAnalysis(analysis: dev.kyungmin.kcoach.core.SetAnalysisResult): String = listOf(
        analysis.watchDetectedReps ?: "",
        analysis.phoneReanalyzedReps ?: "",
        analysis.confirmedReps ?: "",
        analysis.averageRepDurationMs ?: "",
        analysis.tempoVariance ?: "",
        analysis.amplitudeVariance ?: "",
        analysis.stabilityScore ?: "",
        analysis.consistencyScore ?: "",
        analysis.lateSetDegradation ?: "",
        analysis.averageHeartRate ?: "",
        analysis.peakHeartRate ?: "",
        analysis.analysisConfidence,
        analysis.algorithmVersion,
        analysis.trackingMode.name,
        analysis.trackingProfileId,
        analysis.trackingStatus,
        analysis.capabilityTier.name,
        analysis.repConfidence ?: "",
        analysis.wristMotionSpeed ?: "",
        analysis.wristMotionRange ?: "",
        analysis.tempoConsistency ?: "",
        analysis.signalQuality ?: "",
        analysis.averageAngularSpeed ?: "",
        analysis.relativeRom ?: "",
    ).joinToString("|")

    private fun sendCaptureAck(nodeId: String, sessionId: String, messageId: String, captureId: String) =
        Wearable.getMessageClient(this).sendMessage(
            nodeId,
            WearSync.PATH_ACK_V3,
            WireCodec.encode(WireMessages.ack(WireSource.PHONE, sessionId, messageId, captureId)).toByteArray(),
        )

    private fun handlePayload(path: String, payload: String) {
        when (path) {
            WearSync.PATH_CAPTURE_ASSET_EMULATOR -> receiveEmulatorCapture(payload)
            WearSync.PATH_PAIR -> {
                prefs.edit().putLong("pairedAt", System.currentTimeMillis()).apply()
                sendBroadcast(Intent(ACTION_PAIR).setPackage(packageName).putExtra("payload", payload))
                syncPersonalProfiles()
            }
            WearSync.PATH_WATCH_UI_READY -> {
                prefs.edit().putLong("watchUiReadyAt", System.currentTimeMillis()).apply()
                sendBroadcast(Intent(ACTION_WATCH_UI_READY).setPackage(packageName).putExtra("payload", payload))
                syncPersonalProfiles()
            }
            WearSync.PATH_STATE, WearSync.PATH_LIVE -> {
                prefs.edit().putString("state", payload).putLong("stateAt", System.currentTimeMillis()).apply()
                sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("payload", payload))
                if (path == WearSync.PATH_LIVE) {
                    sendBroadcast(Intent(ACTION_LIVE).setPackage(packageName).putExtra("payload", payload))
                }
            }
            WearSync.PATH_SAMPLE -> {
                prefs.edit().putString("lastSample", payload).apply()
                sendBroadcast(Intent(ACTION_SAMPLE).setPackage(packageName).putExtra("payload", payload))
            }
            WearSync.PATH_COMPLETE -> {
                prefs.edit().putString("lastCompletion", payload)
                    .putLong("completionAt", System.currentTimeMillis()).apply()
                sendBroadcast(Intent(ACTION_COMPLETE).setPackage(packageName).putExtra("payload", payload))
            }
            WearSync.PATH_CAPTURE_MANIFEST_V3 -> {
                runCatching { WireCodec.decode(payload) }
                    .onSuccess { logEvent(KCoachEventMarker.CAPTURE_MANIFEST_RECEIVED, it) }
                prefs.edit().putString("lastCaptureManifest", payload)
                    .putLong("captureManifestAt", System.currentTimeMillis()).apply()
                sendBroadcast(Intent(ACTION_CAPTURE_MANIFEST).setPackage(packageName)
                    .putExtra("payload", payload))
            }
        }
    }

    private fun syncPersonalProfiles() {
        prefs.all
            .filterKeys { it.startsWith("personalProfile:") }
            .values
            .filterIsInstance<String>()
            .forEach(::sendPersonalProfile)
    }

    private fun logEvent(
        marker: KCoachEventMarker,
        manifest: dev.kyungmin.kcoach.core.WireEnvelope,
        detail: String = "",
    ) {
        Log.i(
            EVENT_TAG,
            KCoachEventTrace.format(
                marker = marker,
                sessionId = manifest.sessionId,
                exerciseId = manifest.payload[WirePayloadKeys.EXERCISE_ID].orEmpty(),
                setIndex = manifest.payload[WirePayloadKeys.SET_INDEX]?.toIntOrNull(),
                captureId = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty(),
                detail = detail,
            ),
        )
    }

    companion object {
        private const val TAG = "KCoachCapture"
        private const val CAPTURE_ASSET_TIMEOUT_MS = 15_000L
        private const val EVENT_TAG = "KCoachEvent"
        const val ACTION_PAIR = "dev.kyungmin.kcoach.WEAR_PAIR"
        const val ACTION_WATCH_UI_READY = "dev.kyungmin.kcoach.WATCH_UI_READY"
        const val ACTION_STATE = "dev.kyungmin.kcoach.WEAR_STATE"
        const val ACTION_LIVE = "dev.kyungmin.kcoach.WEAR_LIVE"
        const val ACTION_SAMPLE = "dev.kyungmin.kcoach.WEAR_SAMPLE"
        const val ACTION_COMPLETE = "dev.kyungmin.kcoach.WEAR_COMPLETE"
        const val ACTION_CAPTURE_MANIFEST = "dev.kyungmin.kcoach.WEAR_CAPTURE_MANIFEST"
        const val ACTION_CAPTURE_STATE = "dev.kyungmin.kcoach.WEAR_CAPTURE_STATE"
        const val ACTION_CALIBRATION_RAW_SAVED = "dev.kyungmin.kcoach.CALIBRATION_RAW_SAVED"
        const val ACTION_CALIBRATION_RESULT = "dev.kyungmin.kcoach.CALIBRATION_RESULT"
        const val ACTION_CAPTURE = "dev.kyungmin.kcoach.WEAR_CAPTURE"
        const val ACTION_CAPTURE_ERROR = "dev.kyungmin.kcoach.WEAR_CAPTURE_ERROR"
    }
}
