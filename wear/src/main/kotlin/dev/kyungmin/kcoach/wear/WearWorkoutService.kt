package dev.kyungmin.kcoach.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import dev.kyungmin.kcoach.core.EmulatorBridgeClient
import dev.kyungmin.kcoach.core.SensorCaptureFormat
import dev.kyungmin.kcoach.core.SensorCaptureRecord
import dev.kyungmin.kcoach.core.SensorSample
import dev.kyungmin.kcoach.core.ExerciseTrackingProfiles
import dev.kyungmin.kcoach.core.ForearmRotationRepDetector
import dev.kyungmin.kcoach.core.ProfiledMotionRepDetector
import dev.kyungmin.kcoach.core.StartPoseGateConfig
import dev.kyungmin.kcoach.core.StartPoseStabilityGate
import dev.kyungmin.kcoach.core.TrackingSignal
import dev.kyungmin.kcoach.core.TrackingMode
import dev.kyungmin.kcoach.core.WearSync
import dev.kyungmin.kcoach.core.WireCodec
import dev.kyungmin.kcoach.core.WireMessages
import dev.kyungmin.kcoach.core.KCoachEventMarker
import dev.kyungmin.kcoach.core.KCoachEventTrace
import dev.kyungmin.kcoach.core.PersonalCalibrationBuilder
import dev.kyungmin.kcoach.core.PersonalCalibrationProfileCodec
import kotlin.math.sqrt
import java.io.File
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import android.util.Log

/**
 * Owns the watch workout runtime. MainActivity only renders the state
 * broadcast by this service; sensors and timers must survive a round-display
 * transition or screen-off event.
 */
class WearWorkoutService : Service(), SensorEventListener {
    private enum class CalibrationStage {
        BASELINE,
        CONTROLLED_REPS,
    }

    private val handler = Handler(Looper.getMainLooper())
    private val emulatorBridge = EmulatorBridgeClient { _, _ -> }
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var heartRateSensor: Sensor? = null

    private var paired = false
    private var running = false
    private var resting = false
    private var paused = false
    private var pausedRestRemainingMs = 0L
    private var lastPhoneHeartbeatAtMs = 0L
    private var sessionId = ""
    private var exerciseId = ""
    private var exerciseName = "운동 대기 중"
    private var setIndex = 0
    private var targetReps = 10
    private var trackingProfile = ExerciseTrackingProfiles.resolve("", "")
    private var forearmRotationDetector: ForearmRotationRepDetector? = null
    private var profiledMotionDetector: ProfiledMotionRepDetector? = null
    private var weightKg: Double? = null
    private var restSeconds = 60
    private var restEndAtMs = 0L
    private var reps = 0
    private var heartRate = 0
    private var lastPeakAtMs = 0L
    private var lastStateAtMs = 0L
    private var lastUiBroadcastAtMs = 0L
    private var sensorEventCount = 0
    private var uiBroadcastCount = 0
    private var liveStateCount = 0
    private var maxObservedOutboundQueue = 0
    private var lastSampleAtMs = 0L
    private var lastCaptureTimestampNs = 0L
    private var lastSensorAccuracy = -1
    private var lastAccel = FloatArray(3)
    private var lastGyro = FloatArray(3)
    private val captureRecords = ArrayList<SensorCaptureRecord>(CAPTURE_CHUNK_SAMPLES)
    private val captureChunkFiles = mutableListOf<File>()
    private var captureSampleCount = 0
    private var captureChunkIndex = 0
    private var captureLimitLogged = false
    private var restTicker: Runnable? = null
    private var phoneWatchdog: Runnable? = null
    private var calibrationStage: CalibrationStage? = null
    private var measurementReady = false
    private var measurementArmed = false
    private var startPoseGate: StartPoseStabilityGate? = null
    private val capturePrefs by lazy { getSharedPreferences(CAPTURE_PREFS, MODE_PRIVATE) }
    private val captureSendInFlight = mutableSetOf<String>()
    private val outboundSequence = AtomicLong()
    private val outboundQueue = PriorityBlockingQueue<Runnable>()
    private val outboundExecutor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        outboundQueue,
        { runnable -> Thread(runnable, "kcoach-watch-outbound").apply { isDaemon = true } },
    )
    private val captureTransferExecutor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(4),
        { runnable -> Thread(runnable, "kcoach-capture-upload").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var serviceDestroyed = false
    private val pendingCaptureRetry = Runnable { retryPendingCaptures() }

    override fun onCreate() {
        super.onCreate()
        serviceDestroyed = false
        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        createNotificationChannel()
        // Android 14/15 validates the health FGS runtime permission at startup.
        // Generic Wear emulators intentionally run the fake telemetry lane
        // without BODY_SENSORS/ACTIVITY_RECOGNITION, so use the manifest-declared
        // dataSync type only on that lane. Physical watches stay on health FGS.
        if (isEmulator && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        emulatorBridge.start()
        startPhoneWatchdog()
        schedulePendingCaptureRetry(1_500L)
        broadcastState(force = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_COMMAND)) {
            "QUERY" -> {
                broadcastState(force = true)
                sendLiveState(currentPhase(), running, force = true)
            }
            "HEARTBEAT" -> {
                markPhoneAlive()
                // Phone heartbeat messages can arrive after COMPLETE and would
                // otherwise recreate the foreground workout service forever.
                // Keep the service alive only while a session is still owned.
                if (sessionId.isBlank() && !running && !resting && !paused) {
                    stopRuntimeService(startId)
                }
            }
            "HELLO" -> markPhoneAlive()
            "START", "SET" -> startSession(intent)
            "REST" -> startRest(intent)
            "PAUSE" -> pauseSession()
            "RESUME" -> resumeSession()
            "STOP", "END" -> finishSession("STOPPED", startId)
            "COMPLETE" -> finishSession("COMPLETE", startId)
            "CAPTURE_ACK" -> acknowledgeCapture(intent.getStringExtra("captureId").orEmpty())
            "SET_COMPLETE" -> {
                completeSet(manual = true)
                startRest(intent)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceDestroyed = true
        stopRestTicker()
        phoneWatchdog?.let(handler::removeCallbacks)
        phoneWatchdog = null
        handler.removeCallbacks(pendingCaptureRetry)
        sensorManager.unregisterListener(this)
        emulatorBridge.stop()
        outboundExecutor.shutdown()
        captureTransferExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun markPhoneAlive() {
        lastPhoneHeartbeatAtMs = System.currentTimeMillis()
        if (!paired) paired = true
        broadcastState(force = true)
    }

    private fun startPhoneWatchdog() {
        phoneWatchdog?.let(handler::removeCallbacks)
        val tick = object : Runnable {
            override fun run() {
                val last = lastPhoneHeartbeatAtMs
                if (last > 0L && System.currentTimeMillis() - last > PHONE_HEARTBEAT_TIMEOUT_MS) {
                    resetForPhoneDisconnect()
                }
                handler.postDelayed(this, PHONE_WATCHDOG_INTERVAL_MS)
            }
        }
        phoneWatchdog = tick
        handler.post(tick)
    }

    private fun resetForPhoneDisconnect() {
        if (!paired && !running && !resting && !paused) return
        saveCapture()?.let(::sendCaptureToPhone)
        stopRestTicker()
        cancelMeasurementPreparation()
        sensorManager.unregisterListener(this)
        running = false
        resting = false
        paused = false
        paired = false
        pausedRestRemainingMs = 0L
        lastPhoneHeartbeatAtMs = 0L
        restEndAtMs = 0L
        resetSessionFields()
        broadcastState(force = true)
        stopRuntimeService()
    }

    private fun startSession(intent: Intent) {
        markPhoneAlive()
        val incomingSessionId = intent.getStringExtra("session").orEmpty().ifBlank { sessionId }
        val incomingExerciseId = intent.getStringExtra("exerciseId").orEmpty()
        val incomingSetIndex = intent.getIntExtra("setIndex", 0)
        if (
            resting && incomingSessionId == sessionId &&
            (incomingExerciseId != exerciseId || incomingSetIndex != setIndex)
        ) {
            Log.w(
                TAG,
                "ignored stale set command during rest session=$incomingSessionId " +
                    "exercise=$incomingExerciseId set=$incomingSetIndex " +
                    "restTarget=$exerciseId:$setIndex",
            )
            broadcastState(force = true)
            return
        }
        if (
            running && incomingSessionId == sessionId && incomingExerciseId == exerciseId &&
            incomingSetIndex == setIndex
        ) {
            targetReps = intent.getIntExtra("targetReps", targetReps).coerceAtLeast(1)
            weightKg = intent.getDoubleExtra("weightKg", Double.NaN).takeUnless { it.isNaN() }
            restSeconds = intent.getIntExtra("restSeconds", restSeconds).coerceAtLeast(0)
            Log.i(TAG, "ignored duplicate active-set command session=$sessionId exercise=$exerciseId set=$setIndex")
            sendLiveState(currentWorkingPhase(), true, force = true)
            broadcastState(force = true)
            return
        }
        stopRestTicker()
        cancelMeasurementPreparation()
        resting = false
        paused = false
        pausedRestRemainingMs = 0L
        sessionId = incomingSessionId
        exerciseId = incomingExerciseId
        exerciseName = intent.getStringExtra("exerciseName").orEmpty().ifBlank { exerciseId.replace('_', ' ') }
        val genericProfile = ExerciseTrackingProfiles.resolve(exerciseId, exerciseName)
        val personalProfile = getSharedPreferences("personal-profiles", MODE_PRIVATE)
            .getString(exerciseId, null)
            ?.let { runCatching { PersonalCalibrationProfileCodec.decode(it) }.getOrNull() }
        trackingProfile = if (personalProfile == null) {
            genericProfile
        } else {
            PersonalCalibrationBuilder.personalizedTrackingProfile(personalProfile, genericProfile)
        }
        setIndex = incomingSetIndex
        targetReps = intent.getIntExtra("targetReps", 10).coerceAtLeast(1)
        weightKg = intent.getDoubleExtra("weightKg", Double.NaN).takeUnless { it.isNaN() }
        restSeconds = intent.getIntExtra("restSeconds", 60).coerceAtLeast(0)
        running = true
        reps = 0
        calibrationStage = if (sessionId.startsWith("calibration-")) CalibrationStage.BASELINE else null
        forearmRotationDetector = if (trackingProfile.signal == TrackingSignal.FOREARM_ROTATION) {
            ForearmRotationRepDetector(trackingProfile)
        } else {
            null
        }
        profiledMotionDetector = if (
            trackingProfile.mode != TrackingMode.MANUAL_REQUIRED &&
            trackingProfile.signal != TrackingSignal.FOREARM_ROTATION
        ) {
            ProfiledMotionRepDetector(trackingProfile)
        } else {
            null
        }
        measurementReady = trackingProfile.mode == TrackingMode.MANUAL_REQUIRED
        measurementArmed = measurementReady
        startPoseGate = if (measurementReady) null else
            StartPoseStabilityGate(StartPoseGateConfig.forProfile(trackingProfile))
        // Any unresolved previous chunks stay on disk instead of being erased
        // when a replacement set starts.
        resetCaptureBuffer(deleteChunks = false)
        sensorEventCount = 0
        uiBroadcastCount = 0
        liveStateCount = 0
        maxObservedOutboundQueue = 0
        lastSensorAccuracy = -1
        if (sessionId.isBlank()) sessionId = "watch-${System.currentTimeMillis()}"
        heartRate = heartRate.coerceAtLeast(60)
        registerSensors()
        sendLiveState(currentWorkingPhase(), true, force = true)
        if (calibrationStage == CalibrationStage.BASELINE) {
            Log.i(CALIBRATION_TAG, "stage=BASELINE session=$sessionId exerciseId=$exerciseId")
        } else if (!measurementReady) {
            sendLiveState(currentWorkingPhase(), true, force = true)
        }
        broadcastState(force = true)
    }

    private fun startRest(intent: Intent) {
        markPhoneAlive()
        running = false
        resting = true
        paused = false
        pausedRestRemainingMs = 0L
        unregisterMotionSensors()
        cancelMeasurementPreparation()
        restSeconds = intent.getIntExtra("restSeconds", 60).coerceAtLeast(0)
        restEndAtMs = intent.getLongExtra("restEndAtMs", 0L)
            .takeIf { it > 0L } ?: (System.currentTimeMillis() + restSeconds * 1_000L)
        intent.getStringExtra("exerciseId")?.takeIf { it.isNotBlank() }?.let { exerciseId = it }
        intent.getStringExtra("exerciseName")?.takeIf { it.isNotBlank() && it != "휴식" }
            ?.let { exerciseName = it }
        setIndex = intent.getIntExtra("setIndex", setIndex).coerceAtLeast(0)
        targetReps = intent.getIntExtra("targetReps", targetReps).coerceAtLeast(1)
        weightKg = intent.getDoubleExtra("weightKg", Double.NaN).takeUnless { it.isNaN() }
        heartRate = heartRate.coerceAtLeast(60)
        heartRateSensor?.let { sensor ->
            if (hasBodySensorPermission()) sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sendLiveState("RESTING", false, force = true)
        stopRestTicker()
        val tick = object : Runnable {
            override fun run() {
                if (!resting) return
                val left = ((restEndAtMs - System.currentTimeMillis() + 999) / 1_000).coerceAtLeast(0)
                sendLiveState("RESTING", false, force = true)
                broadcastState()
                if (left <= 0) {
                    resting = false
                    restEndAtMs = 0L
                    sendLiveState("REST_COMPLETE", false, force = true)
                    broadcastState(force = true)
                } else {
                    handler.postDelayed(this, 1_000L)
                }
            }
        }
        restTicker = tick
        handler.post(tick)
        broadcastState(force = true)
    }

    private fun pauseSession() {
        markPhoneAlive()
        if (!running && !resting) return
        if (resting) pausedRestRemainingMs = (restEndAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
        running = false
        resting = false
        paused = true
        stopRestTicker()
        cancelMeasurementPreparation()
        sensorManager.unregisterListener(this)
        sendLiveState("PAUSED", false, force = true)
        broadcastState(force = true)
    }

    private fun resumeSession() {
        markPhoneAlive()
        if (!paused) return
        paused = false
        if (pausedRestRemainingMs > 0L) {
            restEndAtMs = System.currentTimeMillis() + pausedRestRemainingMs
            pausedRestRemainingMs = 0L
            val synthetic = Intent(this, WearWorkoutService::class.java).putExtra(EXTRA_COMMAND, "REST")
                .putExtra("restSeconds", restSeconds)
                .putExtra("restEndAtMs", restEndAtMs)
            startService(synthetic)
        } else {
            running = true
            registerSensors()
            if (measurementReady || trackingProfile.mode == TrackingMode.MANUAL_REQUIRED) {
                sendLiveState(currentWorkingPhase(), true, force = true)
            } else {
                startPoseGate = StartPoseStabilityGate(StartPoseGateConfig.forProfile(trackingProfile))
                measurementArmed = false
                sendLiveState(currentWorkingPhase(), true, force = true)
            }
            broadcastState(force = true)
        }
    }

    private fun finishSession(phase: String, startId: Int = 0) {
        markPhoneAlive()
        if (sessionId.isBlank()) {
            resetForPhoneDisconnect()
            return
        }
        Log.i(TAG, "finishSession begin phase=$phase session=$sessionId set=$setIndex samples=$captureSampleCount cacheDir=${filesDir.absolutePath}")
        if (measurementReady) flushForearmRotation()
        running = false
        resting = false
        paused = false
        stopRestTicker()
        cancelMeasurementPreparation()
        sensorManager.unregisterListener(this)
        val savedCapture = saveCapture()
        savedCapture?.let(::sendCaptureToPhone)
        sendLiveState(phase, false, force = true)
        sendToPhone(
            WearSync.PATH_COMPLETE,
            WearSync.completion(sessionId, exerciseId, setIndex, reps, heartRate, phase, captureSampleCount),
        )
        restEndAtMs = 0L
        resetSessionFields()
        broadcastState(force = true)
        val pendingCaptures = capturePrefs.getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet()).orEmpty()
        if (pendingCaptures.isEmpty()) stopRuntimeService(startId)
        Log.i(TAG, "finishSession end phase=$phase")
    }

    private fun registerSensors() {
        if (hasBodySensorPermission()) {
            accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            heartRateSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
    }

    private fun unregisterMotionSensors() {
        accelerometer?.let { sensorManager.unregisterListener(this, it) }
        gyroscope?.let { sensorManager.unregisterListener(this, it) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        sensorEventCount += 1
        val nowMs = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccel = event.values.copyOf()
            }
            Sensor.TYPE_GYROSCOPE -> {
                lastGyro = event.values.copyOf()
                if (running && trackingProfile.mode != TrackingMode.MANUAL_REQUIRED) {
                    val sensorSample = SensorSample(
                        timestampNs = event.timestamp,
                        accelerationX = lastAccel[0],
                        accelerationY = lastAccel[1],
                        accelerationZ = lastAccel[2],
                        gyroscopeX = lastGyro[0],
                        gyroscopeY = lastGyro[1],
                        gyroscopeZ = lastGyro[2],
                        sensorAccuracy = event.accuracy,
                    )
                    if (!measurementReady) {
                        val gate = startPoseGate?.observe(sensorSample)
                        measurementArmed = gate?.armed == true
                        if (gate?.ready == true) {
                            if (calibrationStage == CalibrationStage.BASELINE) {
                                calibrationStage = CalibrationStage.CONTROLLED_REPS
                                Log.i(CALIBRATION_TAG, "stage=CONTROLLED_REPS session=$sessionId exerciseId=$exerciseId")
                            }
                            beginMeasurementCounting()
                            sendLiveState(currentWorkingPhase(), true, force = true)
                            broadcastState(force = true)
                        }
                    } else {
                        val marker = forearmRotationDetector?.add(sensorSample)
                            ?: profiledMotionDetector?.add(sensorSample)
                        if (marker != null) {
                            recordDetectedRep()
                            if (!running) return
                        }
                    }
                }
            }
            Sensor.TYPE_HEART_RATE -> if (event.values.isNotEmpty()) heartRate = event.values[0].toInt().coerceIn(0, 240)
        }
        lastSensorAccuracy = event.accuracy
        if (running && measurementReady && event.sensor.type == Sensor.TYPE_GYROSCOPE && nowMs - lastSampleAtMs >= 20L) {
            val timestampNs = event.timestamp
            if (timestampNs <= lastCaptureTimestampNs) return
            lastSampleAtMs = nowMs
            lastCaptureTimestampNs = timestampNs
            appendCaptureRecord(SensorCaptureRecord(
                sample = SensorSample(
                    timestampNs = timestampNs,
                    accelerationX = lastAccel[0], accelerationY = lastAccel[1], accelerationZ = lastAccel[2],
                    gyroscopeX = lastGyro[0], gyroscopeY = lastGyro[1], gyroscopeZ = lastGyro[2],
                    sensorAccuracy = lastSensorAccuracy.takeIf { it >= 0 },
                ),
                heartRateBpm = heartRate.takeIf { it in 20..240 },
            ))
        }
        if (running) sendLiveState(currentWorkingPhase(), true)
        broadcastState()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun stopRestTicker() {
        restTicker?.let(handler::removeCallbacks)
        restTicker = null
    }

    private fun completeSet(manual: Boolean = false) {
        if (!running) return
        Log.i(TAG, "completeSet begin manual=$manual session=$sessionId set=$setIndex reps=$reps samples=$captureSampleCount")
        if (calibrationStage == null && measurementReady) flushForearmRotation()
        val rotationDiagnostics = forearmRotationDetector?.diagnostics()
        val wasCalibration = calibrationStage != null || sessionId.startsWith("calibration-")
        running = false
        unregisterMotionSensors()
        cancelMeasurementPreparation()
        val savedCapture = saveCapture()
        savedCapture?.let(::sendCaptureToPhone)
        if (wasCalibration) {
            Log.i(CALIBRATION_TAG, "stage=CALIBRATION_RESULT session=$sessionId exerciseId=$exerciseId reps=$reps")
        }
        sendLiveState(
            if (wasCalibration) "CALIBRATION_RESULT" else "SET_COMPLETE",
            false,
            force = true,
        )
        if (wasCalibration) vibrateCompleted()
        calibrationStage = null
        measurementReady = false
        measurementArmed = false
        startPoseGate = null
        broadcastState(force = true)
        resetCaptureBuffer(deleteChunks = savedCapture != null)
        Log.i(
            TAG,
            "runtime load sensorEvents=$sensorEventCount uiBroadcasts=$uiBroadcastCount " +
                "liveStates=$liveStateCount maxOutboundQueue=$maxObservedOutboundQueue",
        )
        if (rotationDiagnostics != null) {
            Log.i(TAG, "forearm rotation diagnostics=$rotationDiagnostics")
        }
        Log.i(TAG, "completeSet end manual=$manual session=$sessionId set=$setIndex reps=$reps")
    }

    private fun beginMeasurementCounting() {
        forearmRotationDetector?.beginCounting()
        profiledMotionDetector?.beginCounting()
        measurementReady = true
        measurementArmed = true
        vibrateReady()
    }

    private fun cancelMeasurementPreparation() {
        startPoseGate?.reset()
    }

    private fun recordDetectedRep() {
        if (!running || trackingProfile.mode == TrackingMode.MANUAL_REQUIRED) return
        reps += 1
        vibrateShort()
        if (reps >= targetReps) completeSet()
    }

    private fun currentWorkingPhase(): String = when (calibrationStage) {
        CalibrationStage.BASELINE -> "BASELINE"
        CalibrationStage.CONTROLLED_REPS -> "CONTROLLED_REPS"
        null -> when {
            trackingProfile.mode == TrackingMode.MANUAL_REQUIRED -> "MANUAL_REQUIRED"
            !measurementReady && !measurementArmed -> "POSITIONING"
            !measurementReady -> "PREPARING"
            else -> "WORKING"
        }
    }

    private fun sendLiveState(phase: String, recording: Boolean, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStateAtMs < 350L) return
        lastStateAtMs = now
        val critical = phase == "SET_COMPLETE" || phase == "CALIBRATION_RESULT" || phase == "REST_COMPLETE"
        sendToPhone(
            WearSync.PATH_LIVE,
            WearSync.liveState(
                sessionId,
                exerciseId,
                exerciseName,
                setIndex,
                reps,
                targetReps,
                weightKg,
                heartRate,
                phase,
                recording,
                restEndAtMs,
                captureSampleCount,
                trackingProfile.id,
                trackingProfile.calibrationProfileVersion != null,
            ),
            coalescible = !critical,
        )
        sendToPhone(WearSync.PATH_STATE, WearSync.state(sessionId, exerciseId, setIndex, reps, heartRate, recording))
        liveStateCount += 1
    }

    private fun sendToPhone(path: String, payload: String, coalescible: Boolean = true) {
        val priority = when {
            !coalescible -> PRIORITY_CONTROL
            path == WearSync.PATH_LIVE || path == WearSync.PATH_STATE || path == WearSync.PATH_SAMPLE -> PRIORITY_LIVE
            path == WearSync.PATH_CAPTURE_MANIFEST_V3 -> PRIORITY_CAPTURE
            else -> PRIORITY_CONTROL
        }
        val task = OutboundTask(path, priority, outboundSequence.incrementAndGet()) {
            runCatching {
                Tasks.await(Wearable.getNodeClient(this).connectedNodes).forEach { node ->
                    Tasks.await(Wearable.getMessageClient(this).sendMessage(node.id, path, payload.toByteArray()))
                }
            }
            emulatorBridge.send(path, payload)
        }
        synchronized(outboundQueue) {
            if (priority == PRIORITY_LIVE && coalescible) {
                outboundQueue.removeIf { queued -> queued is OutboundTask && queued.path == path }
            }
            if (outboundQueue.size >= MAX_OUTBOUND_QUEUE) {
                if (priority == PRIORITY_LIVE) return
                val disposable = outboundQueue
                    .filterIsInstance<OutboundTask>()
                    .filter { it.priority > priority }
                    .maxByOrNull { it.sequence }
                if (disposable != null) outboundQueue.remove(disposable) else {
                    Log.w(TAG, "outbound queue full path=$path")
                    return
                }
            }
            outboundExecutor.execute(task)
            maxObservedOutboundQueue = maxOf(maxObservedOutboundQueue, outboundQueue.size)
        }
    }

    private class OutboundTask(
        val path: String,
        val priority: Int,
        val sequence: Long,
        private val action: () -> Unit,
    ) : Runnable, Comparable<OutboundTask> {
        override fun run() = action()

        override fun compareTo(other: OutboundTask): Int {
            val priorityOrder = priority.compareTo(other.priority)
            return if (priorityOrder != 0) priorityOrder else sequence.compareTo(other.sequence)
        }
    }

    private data class SavedCapture(
        val captureId: String,
        val fileName: String,
        val bytes: ByteArray,
        val manifest: String,
    )

    private fun saveCapture(): SavedCapture? {
        if (captureSampleCount == 0 || sessionId.isBlank()) return null
        return runCatching {
            check(flushCaptureChunk()) { "failed to flush capture chunk" }
            val allRecords = captureChunkFiles.flatMap { chunk ->
                SensorCaptureFormat.read(chunk.inputStream()).records
            }
            check(allRecords.size == captureSampleCount) { "capture chunk sample count mismatch" }
            val captureId = "capture-$sessionId-${System.currentTimeMillis()}"
            val fileName = "$captureId.kcs"
            val bytes = ByteArrayOutputStream().use { output ->
                SensorCaptureFormat.write(output, allRecords, 50.0)
                output.toByteArray()
            }
            atomicWrite(File(filesDir, fileName), bytes)
            val document = SensorCaptureFormat.read(ByteArrayInputStream(bytes))
            val startNs = document.startMonotonicNs ?: 0L
            val endNs = document.endMonotonicNs ?: startNs
            val durationSeconds = ((endNs - startNs).coerceAtLeast(0L)) / 1_000_000_000.0
            val observedRate = if (document.sampleCount > 1 && durationSeconds > 0.0) {
                (document.sampleCount - 1) / durationSeconds
            } else {
                0.0
            }
            val manifest = WireMessages.captureManifest(
                sessionId = sessionId,
                exerciseSessionId = exerciseId,
                setId = "$sessionId-set-$setIndex",
                captureId = captureId,
                formatVersion = SensorCaptureFormat.FORMAT_VERSION,
                compression = "NONE",
                sampleCount = document.sampleCount,
                sampleRateRequestedHz = document.sampleRateRequestedHz,
                sampleRateObservedHz = observedRate,
                startMonotonicNs = startNs,
                endMonotonicNs = endNs,
                sha256 = SensorCaptureFormat.sha256(bytes),
                assetSizeBytes = bytes.size.toLong(),
                watchDetectedReps = reps,
                exerciseId = exerciseId,
                exerciseName = exerciseName,
                setIndex = setIndex,
            ).let(WireCodec::encode)
            atomicWrite(File(filesDir, "$captureId.manifest"), manifest.toByteArray(Charsets.UTF_8))
            rememberPendingCapture(captureId)
            sendToPhone(WearSync.PATH_CAPTURE_MANIFEST_V3, manifest)
            Log.i(TAG, "capture saved id=$captureId samples=${document.sampleCount} bytes=${bytes.size}")
            captureChunkFiles.forEach(File::delete)
            captureChunkFiles.clear()
            SavedCapture(captureId, fileName, bytes, manifest)
        }.onFailure {
            Log.e(TAG, "capture save failed session=$sessionId set=$setIndex samples=$captureSampleCount", it)
        }.getOrNull()
    }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val temp = File(destination.parentFile, "${destination.name}.incoming")
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (destination.exists()) check(destination.delete()) { "failed to replace ${destination.name}" }
        check(temp.renameTo(destination)) { "failed to commit ${destination.name}" }
    }

    private fun appendCaptureRecord(record: SensorCaptureRecord) {
        if (captureSampleCount >= MAX_CAPTURE_SAMPLES) {
            if (!captureLimitLogged) {
                Log.w(TAG, "capture sample limit reached session=$sessionId set=$setIndex")
                captureLimitLogged = true
            }
            return
        }
        captureRecords += record
        captureSampleCount += 1
        if (captureRecords.size >= CAPTURE_CHUNK_SAMPLES) flushCaptureChunk()
    }

    private fun flushCaptureChunk(): Boolean {
        if (captureRecords.isEmpty()) return true
        return runCatching {
            val safeSession = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val chunk = File(filesDir, ".capture-$safeSession-$setIndex-${captureChunkIndex++}.kcs.part")
            chunk.outputStream().use { output ->
                SensorCaptureFormat.write(output, captureRecords.toList(), 50.0)
            }
            captureChunkFiles += chunk
            captureRecords.clear()
            true
        }.getOrElse {
            Log.e(TAG, "capture chunk flush failed", it)
            false
        }
    }

    private fun resetCaptureBuffer(deleteChunks: Boolean) {
        captureRecords.clear()
        if (deleteChunks) captureChunkFiles.forEach(File::delete)
        captureChunkFiles.clear()
        captureSampleCount = 0
        captureChunkIndex = 0
        captureLimitLogged = false
        lastCaptureTimestampNs = 0L
        lastSampleAtMs = 0L
    }

    private fun sendCaptureToPhone(capture: SavedCapture) {
        if (!captureSendInFlight.add(capture.captureId)) return
        val emulatorPayload = listOf(
            capture.captureId,
            Base64.getEncoder().encodeToString(capture.manifest.toByteArray(Charsets.UTF_8)),
            Base64.getEncoder().encodeToString(capture.bytes),
        ).joinToString("|")
        if (isEmulator) {
            // completeSet() runs on the service main looper. Network I/O here
            // throws NetworkOnMainThreadException before the relay can receive
            // the asset, so keep the emulator lane off the main thread too.
            submitCaptureTransfer(capture.captureId) {
                val relayWrite = emulatorBridge.send(WearSync.PATH_CAPTURE_ASSET_EMULATOR, emulatorPayload)
                handler.post {
                    Log.i(TAG, "capture send id=${capture.captureId} emulator=true relayWrite=$relayWrite bytes=${capture.bytes.size}")
                    // A relay TCP write only proves that the host accepted bytes;
                    // keep the outbox retryable until the phone parses, persists,
                    // and returns CAPTURE_ACK.
                    handler.postDelayed({
                        captureSendInFlight.remove(capture.captureId)
                        val stillPending = capturePrefs
                            .getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet())
                            .orEmpty()
                            .contains(capture.captureId)
                        if (stillPending) {
                            Log.i(TAG, "capture retry id=${capture.captureId}")
                            schedulePendingCaptureRetry()
                        }
                    }, CAPTURE_RETRY_DELAY_MS)
                }
            }
            return
        }
        submitCaptureTransfer(capture.captureId) {
            val delivered = runCatching {
                val request = PutDataMapRequest.create("${WearSync.PATH_CAPTURE_ASSET_V3}/${capture.captureId}")
                request.dataMap.putString("wireManifest", capture.manifest)
                request.dataMap.putAsset("captureAsset", Asset.createFromBytes(capture.bytes))
                request.dataMap.putLong("updatedAt", System.currentTimeMillis())
                Tasks.await(
                    Wearable.getDataClient(this).putDataItem(request.asPutDataRequest().setUrgent()),
                    CAPTURE_TRANSFER_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                )
                true
            }.getOrDefault(false)
            handler.post {
                captureSendInFlight.remove(capture.captureId)
                if (!delivered) schedulePendingCaptureRetry()
            }
        }
    }

    private fun submitCaptureTransfer(captureId: String, action: () -> Unit) {
        try {
            captureTransferExecutor.execute(Runnable { action() })
        } catch (error: RejectedExecutionException) {
            captureSendInFlight.remove(captureId)
            Log.w(TAG, "capture upload queue full id=$captureId", error)
            schedulePendingCaptureRetry()
        }
    }

    private fun schedulePendingCaptureRetry(delayMs: Long = CAPTURE_RETRY_DELAY_MS) {
        if (serviceDestroyed) return
        handler.removeCallbacks(pendingCaptureRetry)
        handler.postDelayed(pendingCaptureRetry, delayMs)
    }

    private fun acknowledgeCapture(captureId: String) {
        if (captureId.isBlank()) return
        Log.i(
            EVENT_TAG,
            KCoachEventTrace.format(
                marker = KCoachEventMarker.CAPTURE_ACK_RECEIVED,
                sessionId = sessionId,
                exerciseId = exerciseId,
                setIndex = setIndex,
                captureId = captureId,
            ),
        )
        deleteFile("$captureId.kcs")
        deleteFile("$captureId.manifest")
        captureSendInFlight.remove(captureId)
        val pending = capturePrefs.getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet()).orEmpty().toMutableSet()
        pending.remove(captureId)
        capturePrefs.edit().putStringSet(KEY_PENDING_CAPTURE_IDS, pending).apply()
        if (!running && !resting && !paused && pending.isEmpty()) stopRuntimeService()
    }

    private fun rememberPendingCapture(captureId: String) {
        val pending = capturePrefs.getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet()).orEmpty().toMutableSet()
        if (pending.add(captureId)) capturePrefs.edit().putStringSet(KEY_PENDING_CAPTURE_IDS, pending).apply()
    }

    private fun retryPendingCaptures() {
        capturePrefs.getStringSet(KEY_PENDING_CAPTURE_IDS, emptySet()).orEmpty().forEach { captureId ->
            val captureFile = File(filesDir, "$captureId.kcs")
            val manifestFile = File(filesDir, "$captureId.manifest")
            if (!captureFile.isFile || !manifestFile.isFile) {
                acknowledgeCapture(captureId)
                return@forEach
            }
            val manifest = runCatching { manifestFile.readText() }.getOrNull() ?: return@forEach
            val bytes = runCatching { captureFile.readBytes() }.getOrNull() ?: return@forEach
            sendCaptureToPhone(SavedCapture(captureId, captureFile.name, bytes, manifest))
        }
    }

    private fun resetSessionFields() {
        sessionId = ""
        exerciseId = ""
        exerciseName = "운동 대기 중"
        setIndex = 0
        reps = 0
        heartRate = 0
        // A failed finalization must not erase the only durable raw chunks.
        resetCaptureBuffer(deleteChunks = false)
        forearmRotationDetector = null
        profiledMotionDetector = null
        trackingProfile = ExerciseTrackingProfiles.resolve("", "")
        calibrationStage = null
        measurementReady = false
        measurementArmed = false
        cancelMeasurementPreparation()
    }

    /** Sensors and foreground notification are removed once the session is over. */
    private fun stopRuntimeService(startId: Int = 0) {
        stopRestTicker()
        phoneWatchdog?.let(handler::removeCallbacks)
        phoneWatchdog = null
        sensorManager.unregisterListener(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        if (startId > 0) stopSelfResult(startId) else stopSelf()
    }

    private fun broadcastState(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUiBroadcastAtMs < UI_BROADCAST_INTERVAL_MS) return
        lastUiBroadcastAtMs = now
        uiBroadcastCount += 1
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra("paired", paired)
            putExtra("running", running)
            putExtra("resting", resting)
            putExtra("paused", paused)
            putExtra("session", sessionId)
            putExtra("exerciseId", exerciseId)
            putExtra("exerciseName", exerciseName)
            putExtra("setIndex", setIndex)
            putExtra("targetReps", targetReps)
            putExtra("weightKg", weightKg ?: Double.NaN)
            putExtra("restSeconds", restSeconds)
            putExtra("restEndAtMs", restEndAtMs)
            putExtra("reps", reps)
            putExtra("heartRate", heartRate)
            putExtra("captureSampleCount", captureSampleCount.coerceAtMost(MAX_CAPTURE_SAMPLES))
            putExtra("phase", currentPhase())
            putExtra("trackingProfileId", trackingProfile.id)
            putExtra("personalized", trackingProfile.calibrationProfileVersion != null)
        })
    }

    private fun currentPhase(): String = when {
        paused -> "PAUSED"
        resting -> "RESTING"
        running -> currentWorkingPhase()
        sessionId.isNotBlank() -> "READY"
        else -> "IDLE"
    }

    private fun hasBodySensorPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED

    private fun flushForearmRotation() {
        val rotationMarker = forearmRotationDetector?.finish()
        val profiledMarker = profiledMotionDetector?.finish()
        if (rotationMarker != null || profiledMarker != null) {
            reps += 1
            Log.i(
                TAG,
                "rep flushed external=${rotationMarker != null} profiled=${profiledMarker != null}",
            )
        }
    }

    private fun vibrateShort() {
        if (!running) return
        getSystemService(android.os.Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrateReady() {
        if (!running) return
        getSystemService(android.os.Vibrator::class.java)?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0L, 90L, 70L, 130L), -1),
        )
    }

    private fun vibrateCompleted() {
        getSystemService(android.os.Vibrator::class.java)?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0L, 150L, 70L, 220L), -1),
        )
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "KCoach 워치 운동", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("KCoach")
        .setContentText("워치 센서 세션을 유지하는 중")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private val isEmulator: Boolean
        get() = Build.FINGERPRINT.contains("generic", ignoreCase = true) || Build.MODEL.contains("sdk_gwear", ignoreCase = true)

    companion object {
        private const val TAG = "KCoachCapture"
        private const val EVENT_TAG = "KCoachEvent"
        private const val CALIBRATION_TAG = "KCoachCalibration"
        const val ACTION_STATE = "dev.kyungmin.kcoach.wear.RUNTIME_STATE"
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL_ID = "kcoach_wear_workout"
        private const val NOTIFICATION_ID = 4102
        private const val PHONE_HEARTBEAT_TIMEOUT_MS = 12_000L
        private const val PHONE_WATCHDOG_INTERVAL_MS = 3_000L
        private const val CAPTURE_PREFS = "capture-outbox"
        private const val KEY_PENDING_CAPTURE_IDS = "pendingCaptureIds"
        private const val CAPTURE_RETRY_DELAY_MS = 5_000L
        private const val CAPTURE_TRANSFER_TIMEOUT_MS = 12_000L
        private const val CAPTURE_CHUNK_SAMPLES = 250
        private const val MAX_CAPTURE_SAMPLES = 60_000
        private const val UI_BROADCAST_INTERVAL_MS = 125L
        private const val MAX_OUTBOUND_QUEUE = 32
        private const val PRIORITY_CONTROL = 0
        private const val PRIORITY_CAPTURE = 1
        private const val PRIORITY_LIVE = 2
        private const val CALIBRATION_BASELINE_MS = 2_000L
        private const val MEASUREMENT_PREPARATION_MS = 2_000L
    }
}
