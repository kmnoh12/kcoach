package dev.kyungmin.kcoach.phone

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kyungmin.kcoach.core.*
import dev.kyungmin.kcoach.phone.ui.CalibrationCameraScreen
import dev.kyungmin.kcoach.phone.ui.CalibrationCameraUi
import java.util.concurrent.Executors

/** A reference clip and its exact analyzed frame timestamps stay on this phone. */
class CalibrationCameraActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { CalibrationCaptureStore(this) }
    private val clocks by lazy { CaptureClockStore(this) }
    private lateinit var preview: PreviewView
    private lateinit var overlay: PoseOverlay
    // Screen state read by Compose; written on the main thread only.
    private var ui by mutableStateOf(CalibrationCameraUi())
    /** Wearing comes from the saved workout defaults (or the pattern candidate being tested), never asked here. */
    @Volatile private var wearing: WorkoutWearingSelection? = null
    private var deviceTop = "UNKNOWN"
    private var startVariant = "UNKNOWN"
    // Auto start: green must hold this long; after a start the pose must break once before the next one.
    private var readySinceMs: Long? = null
    private var autoStartArmed = true
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var pose: PoseLandmarker? = null
    @Volatile private var cameraReady = false
    @Volatile private var stopping = false
    @Volatile private var capture: CalibrationCapture? = null
    @Volatile private var preparedCapture: CalibrationCapture? = null
    private var starting = false
    private var watchStartScheduled = false
    @Volatile private var selectedShadow: PatternProfileCommand? = null
    private var encoder: ReferenceVideoEncoder? = null
    @Volatile private var poseReady = false
    @Volatile private var previewLeft = true
    private var poseReadySinceNs: Long? = null
    private var firstCameraNs: Long? = null
    private var lastModelMs = -1L
    private var frameIndex = 0L
    private var timestampSource = CameraTimestampSource.UNKNOWN
    private var counter: UpperBodyPoseCounter? = null
    private val guideFamily by lazy { CameraPoseGuide.familyFor(exerciseId) }
    private val guideSmoother = CameraPoseGuideSmoother()
    private val finalizer by lazy { CalibrationCaptureFinalizer(store) }
    private var lastClockWindowAtMs = 0L
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val prepared = preparedCapture ?: return
            if (intent.getStringExtra("captureId") == prepared.id || intent.getStringExtra("commandId") == selectedShadow?.commandId)
                startWhenReady(prepared)
        }
    }
    private val exerciseId get() = intent.getStringExtra("exerciseId").orEmpty()
    private val exerciseName get() = intent.getStringExtra("exerciseName").orEmpty()
    /** Denied with "don't ask again": the system dialog no longer appears, so the button opens app settings. */
    private var permissionBlocked = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { ui = ui.copy(permissionNeeded = false); bindCamera() }
        else {
            permissionBlocked = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            ui = ui.copy(permissionNeeded = true, status = if (permissionBlocked) "설정 › 권한에서 카메라를 허용해 주세요." else "촬영하려면 카메라 권한이 필요해요.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // FIT_CENTER inside a 3:4 box: the whole analyzed frame is visible, nothing MediaPipe sees is cropped away.
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        overlay = PoseOverlay()
        loadWearing()
        setContent {
            CalibrationCameraScreen(ui, exerciseName, guideFamily, preview, overlay,
                back = ::finish,
                openSaved = { if (capture == null) startActivity(Intent(this, CalibrationReviewActivity::class.java)) },
                openWearingSettings = { if (capture == null && !starting) startActivity(Intent(this, WorkoutProfileActivity::class.java)) },
                stop = { stopCapture(null) },
                retrySave = { ui = ui.copy(saveRetry = false); worker.execute { saveStoppedCapture() } },
                requestPermission = ::requestCameraPermission,
                copyError = ::copyFailureReport)
        }
        ContextCompat.registerReceiver(this, clockReceiver, IntentFilter(PhoneWearListenerService.ACTION_CAPTURE_CLOCK).apply {
            addAction(PhoneWearListenerService.ACTION_PATTERN_PROFILE)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        store.recoverInterrupted().onFailure { ui = ui.copy(status = "이전 촬영 자료를 확인해야 해요.") }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        // Permission granted from system settings while this screen was away: start the camera now.
        if (ui.permissionNeeded && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permissionBlocked = false; ui = ui.copy(permissionNeeded = false, status = "카메라 준비 중…"); bindCamera()
        }
        // The user may have changed the defaults in settings; an ongoing capture keeps its binding.
        if (capture == null && preparedCapture == null && !starting) loadWearing()
    }

    private fun loadWearing() {
        val wearingStore = WorkoutWearingStore(this)
        val selection = if (intent.hasExtra("watchOnLeft")) {
            // A pattern candidate test must run with the candidate's own side and placement.
            val placement = intent.getStringExtra("watchOrientation")
            if (placement in setOf("OUTSIDE", "INSIDE")) WorkoutWearingSelection(if (intent.getBooleanExtra("watchOnLeft", true)) "LEFT" else "RIGHT", placement!!)
            else null
        } else wearingStore.defaultSelection().getOrNull()
        wearing = selection
        previewLeft = selection?.side != "RIGHT"
        deviceTop = selection?.deviceTop ?: "UNKNOWN"
        startVariant = wearingStore.preparationAnswers().getOrNull()?.get(ExerciseMotionCatalog.normalizeExerciseId(exerciseId)) ?: "UNKNOWN"
        poseReady = false; poseReadySinceNs = null
        val guide = CameraCaptureGuide.forExercise(exerciseId, previewLeft)
        ui = ui.copy(headline = guide.headline, startHint = guide.startHint, wearingSummary = selection?.let {
            (if (it.side == "LEFT") "왼손" else "오른손") + " · " + (if (it.placement == "OUTSIDE") "손등 쪽" else "손바닥 쪽")
        }, wearingFromSettings = !intent.hasExtra("watchOnLeft"),
            // A candidate with an unusable wearing value never falls back to the saved defaults; the user re-picks from the test entry.
            candidateWearingInvalid = selection == null && intent.hasExtra("watchOnLeft"))
    }

    companion object { private const val AUTO_START_MS = 1500L }

    private fun requestCameraPermission() {
        if (permissionBlocked) startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", packageName, null)))
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun copyFailureReport() {
        val report = ui.failureReport ?: return
        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("카메라 초기화 오류", report))
        Toast.makeText(this, "오류 정보를 복사했어요", Toast.LENGTH_SHORT).show()
    }

    /**
     * Main thread, once per analyzed frame while not capturing: green held long enough starts capture by itself.
     * Only a frame whose geometry really leaves the start pose re-arms; the readiness hold restarting
     * (screen resumed, wearing reloaded) is not a pose break and must not start another capture.
     */
    private fun autoStart(ready: Boolean, poseBroken: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (poseBroken) autoStartArmed = true
        if (!ready) { readySinceMs = null; overlay.ringSinceMs = null; return }
        if (!autoStartArmed || !cameraReady || wearing == null || capture != null || preparedCapture != null || starting ||
            finalizer.pending != null) { readySinceMs = null; overlay.ringSinceMs = null; return }
        val since = readySinceMs ?: now.also { readySinceMs = it }
        overlay.ringSinceMs = since
        if (now - since >= AUTO_START_MS) {
            readySinceMs = null; overlay.ringSinceMs = null; autoStartArmed = false
            beginCapture()
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                provider = future.get()
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(android.util.Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build()
                analysis = imageAnalysis
                val camera = provider!!.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, cameraPreview, imageAnalysis)
                val source = Camera2CameraInfo.from(camera.cameraInfo).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
                timestampSource = if (source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
                    CameraTimestampSource.PHONE_ELAPSED_REALTIME else CameraTimestampSource.UNKNOWN
                worker.execute {
                    runCatching {
                        pose = PoseLandmarker.createFromOptions(this, PoseLandmarker.PoseLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                            .setRunningMode(RunningMode.VIDEO).setNumPoses(2).setMinPoseDetectionConfidence(0.6f)
                            .setMinPosePresenceConfidence(0.6f).setMinTrackingConfidence(0.6f).build())
                        imageAnalysis.setAnalyzer(worker, ::analyze)
                        cameraReady = true
                        main.post { ui = ui.copy(status = "회색 졸라맨에 맞춰 자세를 잡아 주세요") }
                    }.onFailure { error ->
                        val report = CameraInitializationFailure.save(this, "POSE_INIT", error)
                        main.post {
                            cameraReady = false
                            ui = ui.copy(failureReport = report, status = "관절 인식을 시작하지 못했어요. 오류 정보를 복사해 주세요.")
                        }
                    }
                }
            }.onFailure { error ->
                ui = ui.copy(failureReport = CameraInitializationFailure.save(this, "CAMERA_BIND", error),
                    status = "전면 카메라를 시작하지 못했어요. 오류 정보를 복사해 주세요.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun beginCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { permission.launch(Manifest.permission.CAMERA); return }
        if (!cameraReady || capture != null || starting) return
        val selected = wearing ?: return
        if (!poseReady) return
        starting = true
        ui = ui.copy(status = "워치 응답을 확인하는 중…")
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            // Left the screen while the watch was checked: never start a capture with no camera frames.
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) { starting = false; return@addOnSuccessListener }
            if (nodes.isEmpty()) { starting = false; ui = ui.copy(status = "워치를 연결한 뒤 자세를 다시 잡아 주세요.", blocked = true); return@addOnSuccessListener }
            val onLeft = selected.side == "LEFT"
            val wearing = selected.placement
            val pickedTop = deviceTop
            val pickedVariant = startVariant
            worker.execute {
                runCatching {
                    selectedShadow = null
                    intent.getStringExtra("candidateId")?.let { id ->
                        val candidates = PatternCandidateStore(this)
                        val candidate = candidates.load(id).getOrThrow()
                        candidates.checkCurrentSources(candidate.profile)
                        val key = candidate.profile.compatibility
                        require(key.recipe == ExerciseMeasurementContracts.resolve(exerciseId).recipe &&
                            key.side == if (onLeft) "LEFT" else "RIGHT")
                        require(key.placement == wearing)
                        selectedShadow = PatternTransferOutbox(this).enqueueShadow(PatternRuntimeProfile.from(candidate.profile)).getOrThrow().command
                        PhoneWorkoutService.send(this, WearSync.PATH_PATTERN_PROFILE, selectedShadow!!.encode())
                    }
                    val epoch = PhoneClockEpoch.current(this)
                    val declared = StartWristContext.expectedVariant(exerciseId)?.takeIf {
                        pickedTop != "UNKNOWN" && pickedVariant != "UNKNOWN"
                    }?.let {
                        StartWristContext(ExerciseMotionCatalog.normalizeExerciseId(exerciseId), PatternCandidateStore(this).localUserId(),
                            if (onLeft) "LEFT" else "RIGHT", wearing, pickedTop, pickedVariant)
                    }
                    val next = store.begin(exerciseId, exerciseName, onLeft, epoch.id, wearing, declared).getOrThrow()
                    frameIndex = 0; firstCameraNs = null; stopping = false; watchStartScheduled = false; encoder = null
                    val motion = CameraCaptureGuide.forExercise(exerciseId, onLeft).metric
                    counter = motion?.let(::UpperBodyPoseCounter)
                    preparedCapture = next
                    lastClockWindowAtMs = SystemClock.elapsedRealtime()
                    probe(next.id, "START")
                    main.post {
                        ui = ui.copy(recording = true, stopEnabled = true, status = "워치의 동시 촬영 지원과 응답을 확인하는 중…")
                        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                            stopCapture("Camera screen left foreground"); return@post
                        }
                        main.postDelayed({ if (preparedCapture?.id == next.id) stopCapture("Watch reference capture support not confirmed") }, 5_000)
                        startWhenReady(next)
                    }
                }.onFailure { main.post { starting = false; ui = ui.copy(status = "다른 운동이 진행 중이거나 저장을 시작하지 못했어요.", blocked = true) } }
            }
        }.addOnFailureListener { starting = false; ui = ui.copy(status = "워치 연결을 확인하지 못했어요. 자세를 다시 잡아 주세요.", blocked = true) }
    }

    private fun startWhenReady(prepared: CalibrationCapture) {
        if (!clocks.pairedCaptureSupported(prepared.id)) return
        if (prepared.startWristContext != null && !clocks.startWristContextSupported(prepared.id)) {
            ui = ui.copy(status = "시작 방향 자료를 저장하려면 워치도 업데이트해 주세요. 응답을 다시 확인하는 중…")
            return
        }
        val shadow = selectedShadow
        if (shadow != null) {
            if (!clocks.patternActiveSupported(prepared.id)) return
            val transfer = PatternTransferOutbox(this).latest(shadow.runtime.compatibility).getOrNull() ?: return
            if (transfer.command.commandId != shadow.commandId || transfer.ack?.status !in setOf(PatternProfileStatus.STAGED, PatternProfileStatus.APPLIED)) return
        }
        startPreparedCapture(prepared)
    }

    @Synchronized private fun startPreparedCapture(prepared: CalibrationCapture) {
        if (stopping || watchStartScheduled || preparedCapture?.id != prepared.id) return
        watchStartScheduled = true
        worker.execute {
            synchronized(this) {
                if (stopping) return@execute
                capture = prepared
                preparedCapture = null
                val startCommand = WearSync.withPatternShadow(WearSync.richCommand("START", prepared.id, exerciseId,
                    exerciseName, 0, 8, null, 0, setId = prepared.setId, attemptId = prepared.attemptId), selectedShadow?.commandId.orEmpty())
                val patternBound = selectedShadow?.runtime?.compatibility?.let { WearSync.withPatternBinding(startCommand, it) } ?: startCommand
                val boundCommand = prepared.startWristContext?.let { declared ->
                    WearSync.withStartWristContext(WearSync.withPatternWearing(patternBound,
                        PatternWearingContext(declared.owner, declared.side, declared.placement)), declared)
                } ?: patternBound
                PhoneWorkoutService.send(this, WearSync.PATH_COMMAND, boundCommand)
            }
            main.post { starting = false; ui = ui.copy(recordingSinceMs = SystemClock.uptimeMillis(), status = "촬영 중 · 평소 속도로 움직여 주세요") }
        }
    }

    private fun analyze(image: ImageProxy) {
        val receivedNs = SystemClock.elapsedRealtimeNanos()
        var bitmap: Bitmap? = null
        try {
            if (stopping) return
            val cameraNs = image.imageInfo.timestamp
            val modelMs = cameraNs / 1_000_000L
            if (modelMs <= lastModelMs) {
                if (capture != null) stopCapture("Camera timestamp order changed")
                return
            }
            lastModelMs = modelMs
            val original = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            bitmap = if (rotation == 0) original else Bitmap.createBitmap(original, 0, 0, original.width, original.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true).also { original.recycle() }
            val convertedNs = SystemClock.elapsedRealtimeNanos()
            val result = bitmap.withDetectorImage { mp -> requireNotNull(pose).detectForVideo(mp, modelMs) }
            val completedNs = SystemClock.elapsedRealtimeNanos()
            val normalized = result.landmarks().map { points -> points.map {
                CameraLandmark(it.x().toDouble(), it.y().toDouble(), it.z().toDouble(), it.visibility().orElse(null)?.toDouble(), it.presence().orElse(null)?.toDouble())
            } }
            val world = result.worldLandmarks().map { points -> points.map {
                CameraLandmark(it.x().toDouble(), it.y().toDouble(), it.z().toDouble(), it.visibility().orElse(null)?.toDouble(), it.presence().orElse(null)?.toDouble())
            } }
            val guide = CameraCaptureGuide.forExercise(exerciseId, previewLeft)
            val pose = normalized.singleOrNull()
            val worldPose = world.singleOrNull()
            val poseCandidate = pose != null && worldPose != null && guide.startReady(pose, worldPose, previewLeft)
            val diagnosis = if (pose != null && worldPose != null) guide.diagnose(pose, worldPose, previewLeft) else null
            logDiagnosis(diagnosis, modelMs)
            if (poseCandidate) { if (poseReadySinceNs == null) poseReadySinceNs = cameraNs } else poseReadySinceNs = null
            poseReady = poseReadySinceNs?.let { cameraNs - it >= guide.readyHoldMs * 1_000_000L } == true
            val ready = poseReady; val people = normalized.size
            if (capture == null && preparedCapture == null && !starting) main.post {
                autoStart(ready, !poseCandidate)
                // A refused start keeps its reason on screen until the user breaks the pose and tries again.
                if (!poseCandidate && ui.blocked) ui = ui.copy(blocked = false)
                if (cameraReady && !ui.blocked && finalizer.pending == null && !ui.saving) ui = ui.copy(status = when {
                    wearing == null && intent.hasExtra("watchOnLeft") -> "후보의 착용 정보가 올바르지 않아 촬영할 수 없어요"
                    wearing == null -> "워치 착용 정보를 먼저 저장해 주세요"
                    people > 1 -> "한 사람만 화면에 들어오게 해 주세요"
                    ready -> "자세 확인 · 곧 촬영을 시작해요"
                    guideFamily != null -> "회색 졸라맨에 맞춰 자세를 잡아 주세요"
                    else -> "필요한 관절이 다 보이게 맞춰 주세요"
                }, ready = ready && wearing != null)
            }
            var encodeNs = 0L; var storeNs = 0L; var storeDoneNs = 0L
            val active = capture
            if (active != null) {
                val first = firstCameraNs ?: cameraNs.also { firstCameraNs = it }
                val videoUs = (cameraNs - first) / 1_000L
                val encodeStartNs = SystemClock.elapsedRealtimeNanos()
                val writer = encoder ?: ReferenceVideoEncoder(store.videoFile(active.id), bitmap.width, bitmap.height).also { encoder = it }
                val written = writer.write(bitmap, videoUs)
                val storeStartNs = SystemClock.elapsedRealtimeNanos()
                store.appendFrame(active.id, CameraPoseFrame(frameIndex, cameraNs, receivedNs, modelMs, completedNs, videoUs,
                    bitmap.width, bitmap.height, rotation, true, timestampSource, active.phoneClockId, normalized, world, written)).getOrThrow()
                frameIndex++
                storeDoneNs = SystemClock.elapsedRealtimeNanos(); encodeNs = storeStartNs - encodeStartNs; storeNs = storeDoneNs - storeStartNs
                val now = SystemClock.elapsedRealtime()
                if (now - lastClockWindowAtMs >= 20_000) { lastClockWindowAtMs = now; probe(active.id, "MIDDLE") }
                val metric = if (world.size == 1 && normalized.size == 1)
                    CameraCaptureGuide.forExercise(exerciseId, active.watchOnLeft).observedMetric(normalized.single(), world.single(), active.watchOnLeft)
                else null
                counter?.observe(modelMs, metric)
                val message = when {
                    normalized.size > 1 -> "여러 사람이 보여요. 혼자 화면에 들어와 주세요."
                    normalized.isEmpty() || counter != null && metric == null -> "팔과 몸통이 가려지지 않게 해 주세요."
                    counter != null -> "촬영 중 · 영상 추정 ${counter!!.reps}회"
                    else -> "촬영 중 · 횟수는 종료 후 영상에서 확인해요"
                }
                main.post { ui = ui.copy(status = message) }
                if (videoUs >= 120_000_000L) stopCapture(null)
            }
            val frameWidth = bitmap.width; val frameHeight = bitmap.height
            val perfLine = perf.add(SystemClock.elapsedRealtimeNanos(), cameraNs, convertedNs - receivedNs, completedNs - convertedNs,
                encodeNs, storeNs, SystemClock.elapsedRealtimeNanos() - receivedNs)
            perfLine?.let { Log.i("KCoachPerf", "$exerciseId recording=${active != null} $it") }
            // Start-pose pictogram follows the user's own shoulder and size; readiness stays the angle check above.
            val figure = guideFamily?.takeIf { capture == null && !starting }?.let { family ->
                val anchor = normalized.singleOrNull()?.let { CameraPoseGuide.measure(it, frameWidth.toDouble(), frameHeight.toDouble(), family, previewLeft) }
                guideSmoother.update(anchor, modelMs)?.let { CameraPoseGuide.build(family, it) }
            }
            // Without wearing info capture cannot start, so the figure must not look ready (pose geometry itself is unchanged).
            val matched = poseReady && wearing != null
            main.post {
                overlay.points = normalized.singleOrNull().orEmpty(); overlay.frameWidth = frameWidth; overlay.frameHeight = frameHeight; overlay.diagnosis = diagnosis; overlay.perfText = perfLine ?: overlay.perfText
                overlay.update(figure, matched); overlay.invalidate()
            }
        } catch (error: Throwable) {
            val storageFull = captureStorageFull(error)
            val hadCapture = capture != null || preparedCapture != null
            if (hadCapture) stopCapture(if (storageFull) "Storage full while recording reference frames"
                else "Camera frame processing failed: ${error.javaClass.simpleName}")
            else poseReady = false
            val report = CameraInitializationFailure.report("FRAME_PROCESSING", error)
            main.post {
                ui = ui.copy(failureReport = report, status = when {
                    !hadCapture -> "카메라 프레임을 처리하지 못했어요. 오류 정보를 확인해 주세요."
                    storageFull -> "저장 공간이 부족해 촬영을 멈췄어요. 이미 받은 자료의 종료 상태를 저장하는 중이에요."
                    else -> "촬영이 중단됐어요. 받은 자료의 종료 상태를 저장하는 중이에요."
                })
            }
        } finally { bitmap?.recycle(); image.close() }
    }

    private val perf = FramePerf()
    private var lastDiagnosisLogMs = 0L
    /** Once a second: raw per-joint confidences behind the start-pose decision, readable with `adb logcat -s KCoachPose`. */
    private fun logDiagnosis(diagnosis: StartDiagnosis?, modelMs: Long) {
        if (modelMs - lastDiagnosisLogMs < 1000) return
        lastDiagnosisLogMs = modelMs
        val text = diagnosis?.let { d ->
            d.joints.joinToString(" ") { "${it.index}=${"%.2f".format(it.confidence)}${if (it.inFrame) "" else "(out)"}" } +
                " angle=${d.angle?.let { "%.0f".format(it) }} atRest=${d.atRest} ready=${d.ready}"
        } ?: "no single pose"
        Log.i("KCoachPose", "$exerciseId left=$previewLeft $text")
    }

    private fun probe(id: String, window: String) {
        repeat(3) { index -> main.postDelayed({
            clocks.prepareProbe(id, window).onSuccess { PhoneWorkoutService.send(this, WearSync.PATH_CLOCK_REQUEST, WireCodec.encode(it)) }
        }, index * 150L) }
    }

    @Synchronized private fun stopCapture(reason: String?) {
        val active = capture ?: preparedCapture ?: return
        if (stopping) return
        stopping = true
        main.post { ui = ui.copy(stopEnabled = false, saving = true, status = "영상과 워치 원본을 저장하는 중…") }
        if (capture != null) PhoneWorkoutService.send(this, WearSync.PATH_COMMAND, WearSync.command("STOP", active.id, "", 0, 0))
        probe(active.id, "END")
        worker.execute {
            val videoFinished = runCatching { encoder?.finish() == true }.getOrDefault(false)
            encoder = null
            finalizer.prepare(active, videoFinished, reason)
            saveStoppedCapture()
        }
    }

    private fun saveStoppedCapture() {
        val pending = finalizer.pending ?: return
        val active = pending.capture
        val saved = finalizer.save()
        if (saved.isSuccess) {
            capture = null; preparedCapture = null; counter = null; stopping = false
        }
        main.post {
            if (saved.isFailure) {
                // Keep stopping=true: retry writes metadata only, never resumes/overwrites the video.
                ui = ui.copy(saveRetry = true, saving = false, status = captureSaveFailureText(requireNotNull(saved.exceptionOrNull())))
            } else {
                starting = false; autoStartArmed = false
                ui = ui.copy(recording = false, stopEnabled = false, saving = false, saveRetry = false, recordingSinceMs = null, blocked = true,
                    status = if (saved.getOrThrow().status == CalibrationCaptureStatus.REVIEW_PENDING)
                        "영상 저장 완료 · 워치 원본 수신 후 동작을 확인해 주세요." else "중단 자료를 보관했어요. 새 촬영에서 이어갈 수 있어요.")
                if (pending.reason == null && !isFinishing && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                    startActivity(Intent(this, CalibrationReviewActivity::class.java).putExtra("captureId", active.id))
            }
        }
    }

    override fun onStop() { stopCapture("Camera screen left foreground"); super.onStop() }
    override fun onDestroy() {
        runCatching { unregisterReceiver(clockReceiver) }
        analysis?.clearAnalyzer()
        worker.execute { pose?.close(); pose = null }
        worker.shutdown()
        super.onDestroy()
    }

    private inner class PoseOverlay : View(this@CalibrationCameraActivity) {
        var points: List<CameraLandmark> = emptyList()
        var frameWidth = 1
        var frameHeight = 1
        private var figure: GuideFigure? = null
        private var matched = false
        /** Set while green is counting down to the automatic start; drawn as a ring around the check. */
        var ringSinceMs: Long? = null
        private val dp = resources.displayMetrics.density
        private val accent = Color.rgb(45, 212, 191)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 3 * dp; strokeCap = Paint.Cap.ROUND }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 1.5f * dp }
        private val shape = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(64, 220, 120) }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 4 * dp
        }

        fun update(next: GuideFigure?, ready: Boolean) { figure = next; matched = ready }

        /** Live per-joint confidences behind the start decision; long-press the camera view to toggle. */
        var diagnosis: StartDiagnosis? = null
        var perfText = ""
        private var showDiagnosis = intent.getBooleanExtra("debugVisibility", false)
        private val debugText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * dp; typeface = android.graphics.Typeface.MONOSPACE }
        init { setOnLongClickListener { showDiagnosis = !showDiagnosis; invalidate(); true } }

        private fun drawDiagnosis(canvas: Canvas) {
            val d = diagnosis ?: return
            var y = 96 * dp
            fun row(text: String, ok: Boolean) {
                debugText.color = if (ok) Color.rgb(120, 255, 170) else Color.rgb(255, 120, 110)
                canvas.drawText(text, 12 * dp, y, debugText); y += 15 * dp
            }
            d.joints.forEach { row("#${it.index} vis=${it.visibility?.let { v -> "%.2f".format(v) }} pres=${it.presence?.let { v -> "%.2f".format(v) }}${if (it.inFrame) "" else " OUT"}", it.ok) }
            row("angle=${d.angle?.let { "%.0f°".format(it) } ?: "-"}${d.requiredBand?.let { " (${it.start.toInt()}~${it.endInclusive.toInt()}°)" } ?: ""} atRest=${d.atRest ?: "-"} ≥${CameraCaptureGuide.MIN_CONFIDENCE}", d.ready)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
            val dx = (width - frameWidth * scale) / 2; val dy = (height - frameHeight * scale) / 2
            drawGuide(canvas, scale, dx, dy)
            if (showDiagnosis) {
                drawDiagnosis(canvas)
                if (perfText.isNotEmpty()) {
                    debugText.color = Color.rgb(255, 214, 120); debugText.textSize = 10 * dp
                    perfText.split(" · ").forEachIndexed { i, part -> canvas.drawText(part, 12 * dp, height - 150 * dp + i * 13 * dp, debugText) }
                    debugText.textSize = 12 * dp
                }
            }
            // Mirrored selfie preview: landmarks are in the unmirrored frame.
            fun vx(x: Double) = dx + (1 - x).toFloat() * frameWidth * scale
            fun vy(y: Double) = dy + y.toFloat() * frameHeight * scale
            fun confidence(p: CameraLandmark) = minOf(p.visibility ?: 0.0, p.presence ?: 0.0)
            val edges = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28)
            edges.forEach { (a, b) ->
                val first = points.getOrNull(a) ?: return@forEach; val second = points.getOrNull(b) ?: return@forEach
                val c = minOf(confidence(first), confidence(second))
                if (c < 0.2) return@forEach
                line.alpha = if (c < 0.6) 60 else 166
                canvas.drawLine(vx(first.x), vy(first.y), vx(second.x), vy(second.y), line)
            }
            edges.flatMap { listOf(it.first, it.second) }.toSet().forEach { index ->
                val p = points.getOrNull(index) ?: return@forEach
                val c = confidence(p)
                if (c < 0.2) return@forEach
                // Weak joints stay visible but small and faint, so the user sees what is not tracked.
                val strong = c >= 0.6
                dot.alpha = if (strong) 245 else 110; ring.alpha = if (strong) 255 else 110
                val r = (if (strong) 4.5f else 3f) * dp
                canvas.drawCircle(vx(p.x), vy(p.y), r, dot); canvas.drawCircle(vx(p.x), vy(p.y), r, ring)
            }
        }

        private fun drawGuide(canvas: Canvas, scale: Float, dx: Float, dy: Float) {
            val shown = figure ?: return
            val color = if (matched) Color.rgb(90, 222, 132) else Color.rgb(205, 209, 207)
            val alpha = ((if (matched) 0.42f else 0.36f) * 255).toInt()
            fun gx(p: GuidePoint) = dx + (frameWidth - p.x).toFloat() * scale
            fun gy(p: GuidePoint) = dy + p.y.toFloat() * scale
            fun limb(l: GuideLimb, extra: Float = 0f) {
                shape.style = Paint.Style.STROKE; shape.strokeWidth = l.width.toFloat() * scale + extra
                for (i in 0 until l.points.size - 1) canvas.drawLine(gx(l.points[i]), gy(l.points[i]), gx(l.points[i + 1]), gy(l.points[i + 1]), shape)
            }
            shape.color = color
            canvas.saveLayerAlpha(null, alpha / 2)
            shown.limbs.filter { !it.primary }.forEach { limb(it) }
            canvas.restore()
            canvas.saveLayerAlpha(null, alpha)
            if (shown.torso.size == 2) {
                shape.style = Paint.Style.STROKE; shape.strokeWidth = shown.torsoWidth.toFloat() * scale
                canvas.drawLine(gx(shown.torso[0]), gy(shown.torso[0]), gx(shown.torso[1]), gy(shown.torso[1]), shape)
            } else {
                val path = android.graphics.Path().apply {
                    moveTo(gx(shown.torso[0]), gy(shown.torso[0])); shown.torso.drop(1).forEach { lineTo(gx(it), gy(it)) }; close()
                }
                shape.style = Paint.Style.FILL; shape.pathEffect = android.graphics.CornerPathEffect(6 * dp)
                canvas.drawPath(path, shape); shape.pathEffect = null
            }
            shape.style = Paint.Style.FILL
            canvas.drawCircle(gx(shown.head), gy(shown.head), shown.headRadius.toFloat() * scale, shape)
            shown.limbs.filter { it.primary }.forEach { l ->
                // A dark edge keeps a target arm readable where it crosses the torso in side views.
                if (shown.torso.size == 2) { shape.color = Color.rgb(20, 24, 23); limb(l, 3 * dp); shape.color = color }
                limb(l)
            }
            canvas.restore()
            if (matched) {
                val cx = width / 2f; val cy = 72 * dp; val r = 22 * dp
                badge.alpha = 235; tick.alpha = 255
                canvas.drawCircle(cx, cy, r, badge)
                canvas.drawPath(android.graphics.Path().apply {
                    moveTo(cx - r * .42f, cy); lineTo(cx - r * .1f, cy + r * .34f); lineTo(cx + r * .45f, cy - r * .3f)
                }, tick)
                ringSinceMs?.let { since ->
                    val fraction = ((SystemClock.uptimeMillis() - since) / AUTO_START_MS.toFloat()).coerceIn(0f, 1f)
                    val ring = r + 7 * dp
                    canvas.drawArc(cx - ring, cy - ring, cx + ring, cy + ring, -90f, 360f * fraction, false, tick)
                    if (fraction < 1f) postInvalidateOnAnimation()
                }
            }
        }
    }
}
