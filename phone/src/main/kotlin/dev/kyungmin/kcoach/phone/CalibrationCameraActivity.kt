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
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
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
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import dev.kyungmin.kcoach.core.*
import java.util.concurrent.Executors

/** A reference clip and its exact analyzed frame timestamps stay on this phone. */
class CalibrationCameraActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { CalibrationCaptureStore(this) }
    private val clocks by lazy { CaptureClockStore(this) }
    private lateinit var preview: PreviewView
    private lateinit var overlay: PoseOverlay
    private lateinit var status: TextView
    private lateinit var copyError: Button
    private var failureReport: String? = null
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var hand: CheckBox
    private lateinit var orientation: Spinner
    private var startConditions: Button? = null
    private var deviceTop = "UNKNOWN"
    private var startVariant = "UNKNOWN"
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
    private lateinit var startReviewed: CheckBox
    private var firstCameraNs: Long? = null
    private var lastModelMs = -1L
    private var frameIndex = 0L
    private var timestampSource = CameraTimestampSource.UNKNOWN
    private var counter: UpperBodyPoseCounter? = null
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
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) bindCamera() else { status.text = "촬영하려면 카메라 권한이 필요해요."; start.text = "카메라 권한 허용"; start.isEnabled = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        ContextCompat.registerReceiver(this, clockReceiver, IntentFilter(PhoneWearListenerService.ACTION_CAPTURE_CLOCK).apply {
            addAction(PhoneWearListenerService.ACTION_PATTERN_PROFILE)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        store.recoverInterrupted().onFailure { status.text = "이전 촬영 자료를 확인해야 해요." }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 16, 24, 20); setBackgroundColor(Color.rgb(9, 12, 11))
        addView(TextView(this@CalibrationCameraActivity).apply { text = "${exerciseName}\n영상으로 동작 확인"; textSize = 22f; setTextColor(Color.WHITE) })
        addView(TextView(this@CalibrationCameraActivity).apply {
            val guide = CameraCaptureGuide.forExercise(exerciseId, intent.getBooleanExtra("watchOnLeft", true))
            text = guide.direction + "\n" + guide.framing + "\n시작 · " + guide.startDescription + "\n평소 속도로 촬영하고 목표 횟수는 종료 후 확인해요."
            setTextColor(Color.LTGRAY); textSize = 14f; setPadding(0, 10, 0, 10)
        })
        hand = CheckBox(this@CalibrationCameraActivity).apply { text = "워치를 왼손에 착용"; isChecked = intent.getBooleanExtra("watchOnLeft", true); setTextColor(Color.WHITE) }
        previewLeft = hand.isChecked
        hand.setOnCheckedChangeListener { _, checked -> previewLeft = checked; poseReady = false; poseReadySinceNs = null }
        addView(hand)
        startReviewed = CheckBox(this@CalibrationCameraActivity).apply { text = "안내의 시작 자세를 직접 확인했어요"; setTextColor(Color.WHITE) }
        addView(startReviewed)
        this@CalibrationCameraActivity.orientation = Spinner(this@CalibrationCameraActivity).apply {
            adapter = ArrayAdapter(this@CalibrationCameraActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("워치 화면 방향 선택", "손등 쪽", "손바닥 쪽"))
        }
        addView(this@CalibrationCameraActivity.orientation)
        this@CalibrationCameraActivity.orientation.setSelection(when (intent.getStringExtra("watchOrientation")) { "OUTSIDE" -> 1; "INSIDE" -> 2; else -> 0 })
        if (StartWristContext.expectedVariant(exerciseId) != null) {
            startConditions = Button(this@CalibrationCameraActivity).apply {
                text = "시작 방향 조건 · 미확인"
                setOnClickListener { chooseStartConditions() }
            }
            addView(startConditions)
        }
        val frame = FrameLayout(this@CalibrationCameraActivity)
        preview = PreviewView(this@CalibrationCameraActivity).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        this@CalibrationCameraActivity.overlay = PoseOverlay()
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1)); frame.addView(this@CalibrationCameraActivity.overlay, FrameLayout.LayoutParams(-1, -1))
        addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this@CalibrationCameraActivity).apply { text = "카메라 준비 중…"; textSize = 14f; setTextColor(Color.rgb(30, 210, 190)); setPadding(0, 12, 0, 8) }
        addView(status)
        copyError = Button(this@CalibrationCameraActivity).apply {
            text = "오류 정보 복사"; visibility = View.GONE
            setOnClickListener {
                failureReport?.let { report ->
                    (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("카메라 초기화 오류", report))
                    Toast.makeText(this@CalibrationCameraActivity, "오류 정보를 복사했어요", Toast.LENGTH_SHORT).show()
                }
            }
        }
        addView(copyError)
        addView(LinearLayout(this@CalibrationCameraActivity).apply {
            start = Button(this@CalibrationCameraActivity).apply { text = "워치와 함께 촬영 시작"; isEnabled = false; setOnClickListener { beginCapture() } }
            stop = Button(this@CalibrationCameraActivity).apply { text = "촬영 종료 · 저장"; isEnabled = false; setOnClickListener { stopCapture(null) } }
            addView(start, LinearLayout.LayoutParams(0, -2, 1f)); addView(stop, LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(Button(this@CalibrationCameraActivity).apply { text = "저장한 촬영 확인"; setOnClickListener {
            if (capture == null) startActivity(Intent(this@CalibrationCameraActivity, CalibrationReviewActivity::class.java))
        } })
    }

    private fun chooseStartConditions() {
        val mounts = listOf("UNKNOWN", "ELBOW", "HAND")
        val variants = listOf("UNKNOWN", requireNotNull(StartWristContext.expectedVariant(exerciseId)))
        val mount = Spinner(this).apply {
            adapter = ArrayAdapter(this@CalibrationCameraActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("본체 방향 미확인", "본체 위쪽이 팔꿈치 쪽", "본체 위쪽이 손 쪽"))
            setSelection(mounts.indexOf(deviceTop).coerceAtLeast(0))
        }
        val variant = Spinner(this).apply {
            val label = when (ExerciseMotionCatalog.normalizeExerciseId(exerciseId)) {
                "external_rotation" -> "손바닥 위 · 전완 수평"
                "crossover_reverse_fly" -> "손등 위 그립"
                else -> "기본 뉴트럴 · 팔 내림"
            }
            adapter = ArrayAdapter(this@CalibrationCameraActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("다른 방식 · 미확인", label))
            setSelection(variants.indexOf(startVariant).coerceAtLeast(0))
        }
        val options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12)
            addView(TextView(this@CalibrationCameraActivity).apply {
                text = "실제 본체 방향과 이번 시작 변형을 기록해요. 이 선택만으로 방향이 검증되지는 않아요. 모르면 미확인으로 두세요."
            })
            addView(mount); addView(variant)
        }
        android.app.AlertDialog.Builder(this).setTitle("시작 방향 조건").setView(options).setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                deviceTop = mounts[mount.selectedItemPosition]; startVariant = variants[variant.selectedItemPosition]
                startConditions?.text = if (deviceTop == "UNKNOWN" || startVariant == "UNKNOWN")
                    "시작 방향 조건 · 미확인" else "시작 방향 조건 · 선택한 조건 저장"
            }.show()
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
                        main.post { start.isEnabled = true; status.text = "몸이 보이면 촬영을 시작해 주세요. 영상은 폰 안에 저장돼요." }
                    }.onFailure { error ->
                        val report = CameraInitializationFailure.save(this, "POSE_INIT", error)
                        main.post {
                            cameraReady = false; start.isEnabled = false
                            failureReport = report; copyError.visibility = View.VISIBLE
                            status.text = "관절 인식을 시작하지 못했어요. 몸의 위치를 바꿀 필요는 없어요. 오류 정보를 복사해 주세요."
                        }
                    }
                }
            }.onFailure { error ->
                failureReport = CameraInitializationFailure.save(this, "CAMERA_BIND", error)
                copyError.visibility = View.VISIBLE
                status.text = "전면 카메라를 시작하지 못했어요. 오류 정보를 복사해 주세요."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun beginCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { permission.launch(Manifest.permission.CAMERA); return }
        if (!cameraReady || capture != null || starting) return
        if (!poseReady) { status.text = "필수 관절과 시작 위치가 보이게 맞춘 뒤 촬영해 주세요."; return }
        if (orientation.selectedItemPosition == 0) { status.text = "워치 화면이 손등 쪽인지 손바닥 쪽인지 선택해 주세요."; return }
        if (CameraCaptureGuide.forExercise(exerciseId, hand.isChecked).metric == null && !startReviewed.isChecked) { status.text = "이 동작의 시작 자세는 안내와 영상을 직접 확인해 주세요."; return }
        starting = true; start.isEnabled = false
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) { starting = false; start.isEnabled = true; status.text = "워치를 연결한 뒤 함께 촬영해 주세요."; return@addOnSuccessListener }
            val onLeft = hand.isChecked
            val wearing = when (orientation.selectedItemPosition) { 1 -> "OUTSIDE"; 2 -> "INSIDE"; else -> "UNKNOWN" }
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
                    val motion = cameraMotion(exerciseId)
                    counter = motion?.let(::UpperBodyPoseCounter)
                    preparedCapture = next
                    lastClockWindowAtMs = SystemClock.elapsedRealtime()
                    probe(next.id, "START")
                    main.post {
                        stop.isEnabled = true; hand.isEnabled = false; orientation.isEnabled = false; startConditions?.isEnabled = false
                        status.text = "워치의 동시 촬영 지원과 응답을 확인하는 중…"
                        main.postDelayed({ if (preparedCapture?.id == next.id) stopCapture("Watch reference capture support not confirmed") }, 5_000)
                        startWhenReady(next)
                    }
                }.onFailure { main.post { starting = false; start.isEnabled = true; status.text = "다른 운동이 진행 중이거나 저장을 시작하지 못했어요." } }
            }
        }.addOnFailureListener { starting = false; start.isEnabled = true; status.text = "워치 연결을 확인하지 못했어요." }
    }

    private fun startWhenReady(prepared: CalibrationCapture) {
        if (!clocks.pairedCaptureSupported(prepared.id)) return
        if (prepared.startWristContext != null && !clocks.startWristContextSupported(prepared.id)) {
            status.text = "시작 방향 자료를 저장하려면 워치도 업데이트해 주세요. 응답을 다시 확인하는 중…"
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
            main.post { starting = false; status.text = "촬영 시작 · 워치 준비 자세를 잡고 평소 동작으로 움직여 주세요." }
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
            val result = BitmapImageBuilder(bitmap).build().use { mp -> requireNotNull(pose).detectForVideo(mp, modelMs) }
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
            val readyMetric = if (pose != null && worldPose != null) guide.metric?.let { motion -> UpperBodyPoseGeometry.metric(worldPose.mapIndexed { i, p -> PosePoint(p.x, p.y, p.z, minOf(pose[i].visibility ?: 0.0, pose[i].presence ?: 0.0)) }, motion, previewLeft) } else null
            val poseCandidate = pose != null && guide.visible(pose) && (guide.metric == null || readyMetric?.let { guide.metric!!.atRest(it) } == true)
            if (poseCandidate) { if (poseReadySinceNs == null) poseReadySinceNs = cameraNs } else poseReadySinceNs = null
            poseReady = poseReadySinceNs?.let { cameraNs - it >= 200_000_000L } == true
            if (capture == null && !starting) main.post { if (cameraReady) status.text = if (poseReady) { if (guide.metric == null) "필수 관절 확인 · 안내의 시작 자세를 직접 확인해 주세요" else "관절과 시작 위치 확인 · 촬영을 시작할 수 있어요" } else "필수 관절과 시작 위치가 보이게 맞춰 주세요" }
            val active = capture
            if (active != null) {
                val first = firstCameraNs ?: cameraNs.also { firstCameraNs = it }
                val videoUs = (cameraNs - first) / 1_000L
                val writer = encoder ?: ReferenceVideoEncoder(store.videoFile(active.id), bitmap.width, bitmap.height).also { encoder = it }
                val written = writer.write(bitmap, videoUs)
                store.appendFrame(active.id, CameraPoseFrame(frameIndex, cameraNs, receivedNs, modelMs, completedNs, videoUs,
                    bitmap.width, bitmap.height, rotation, true, timestampSource, active.phoneClockId, normalized, world, written)).getOrThrow()
                frameIndex++
                val now = SystemClock.elapsedRealtime()
                if (now - lastClockWindowAtMs >= 20_000) { lastClockWindowAtMs = now; probe(active.id, "MIDDLE") }
                val metric = if (world.size == 1 && normalized.size == 1) cameraMotion(exerciseId)?.let { motion ->
                    UpperBodyPoseGeometry.metric(world.single().mapIndexed { index, point ->
                        val confidence = normalized.single()[index]
                        PosePoint(point.x, point.y, point.z, minOf(confidence.visibility ?: 0.0, confidence.presence ?: 0.0))
                    }, motion, active.watchOnLeft)
                } else null
                counter?.observe(modelMs, metric)
                val message = when {
                    normalized.size > 1 -> "여러 사람이 보여요. 혼자 화면에 들어와 주세요."
                    normalized.isEmpty() || counter != null && metric == null -> "팔과 몸통이 가려지지 않게 해 주세요."
                    counter != null -> "촬영 중 · 영상 추정 ${counter!!.reps}회 · 종료 후 확인이 필요해요."
                    else -> "촬영 중 · 횟수와 동작 구간은 영상에서 확인해요."
                }
                main.post { status.text = message }
                if (videoUs >= 120_000_000L) stopCapture(null)
            }
            val frameWidth = bitmap.width; val frameHeight = bitmap.height
            main.post { overlay.points = normalized.singleOrNull().orEmpty(); overlay.frameWidth = frameWidth; overlay.frameHeight = frameHeight; overlay.invalidate() }
        } catch (error: Throwable) {
            if (capture != null) stopCapture("Camera frame processing failed: ${error.javaClass.simpleName}")
            main.post { status.text = "촬영이 중단됐어요. 받은 영상과 관절 자료는 보관해요." }
        } finally { bitmap?.recycle(); image.close() }
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
        main.post { stop.isEnabled = false; status.text = "영상과 워치 원본을 저장하는 중…" }
        if (capture != null) PhoneWorkoutService.send(this, WearSync.PATH_COMMAND, WearSync.command("STOP", active.id, "", 0, 0))
        probe(active.id, "END")
        worker.execute {
            val videoFinished = runCatching { encoder?.finish() == true }.getOrDefault(false)
            encoder = null
            val saved = store.finish(active.id, videoFinished, reason)
            capture = null; preparedCapture = null; counter = null; stopping = false
            main.post {
                starting = false; start.isEnabled = cameraReady; hand.isEnabled = true; orientation.isEnabled = true; startConditions?.isEnabled = true
                status.text = if (saved.getOrNull()?.status == CalibrationCaptureStatus.REVIEW_PENDING)
                    "영상 저장 완료 · 워치 원본 수신 후 동작을 확인해 주세요." else "중단 자료를 보관했어요. 새 촬영에서 이어갈 수 있어요."
                if (reason == null && saved.isSuccess && !isFinishing && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
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

    private fun cameraMotion(id: String): CameraMotion? = when (ExerciseMeasurementContracts.resolve(id).recipe) {
        MeasurementRecipe.CURL -> CameraMotion.ELBOW_FOLD
        MeasurementRecipe.LATERAL_RAISE -> CameraMotion.SHOULDER_RAISE
        MeasurementRecipe.TRICEPS_EXTENSION, MeasurementRecipe.SHOULDER_PRESS -> CameraMotion.ELBOW_PRESS
        else -> null
    }

    private inner class PoseOverlay : View(this@CalibrationCameraActivity) {
        var points: List<CameraLandmark> = emptyList()
        var frameWidth = 1
        var frameHeight = 1
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 210, 190); strokeWidth = 5f }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
            val dx = (width - frameWidth * scale) / 2; val dy = (height - frameHeight * scale) / 2
            val edges = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28)
            edges.forEach { (a, b) ->
                val first = points.getOrNull(a) ?: return@forEach; val second = points.getOrNull(b) ?: return@forEach
                if (minOf(first.visibility ?: 0.0, first.presence ?: 0.0, second.visibility ?: 0.0, second.presence ?: 0.0) < 0.6) return@forEach
                canvas.drawLine(dx + (1 - first.x).toFloat() * frameWidth * scale, dy + first.y.toFloat() * frameHeight * scale,
                    dx + (1 - second.x).toFloat() * frameWidth * scale, dy + second.y.toFloat() * frameHeight * scale, paint)
            }
        }
    }
}
