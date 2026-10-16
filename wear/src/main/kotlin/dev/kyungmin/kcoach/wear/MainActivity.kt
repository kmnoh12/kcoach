package dev.kyungmin.kcoach.wear

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import android.text.InputType
import android.text.InputFilter
import android.text.Editable
import android.text.TextWatcher
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import dev.kyungmin.kcoach.core.EmulatorBridgeClient
import dev.kyungmin.kcoach.core.RestCountdownCalculator
import dev.kyungmin.kcoach.core.WearSync
import dev.kyungmin.kcoach.core.ManualSetCommand
import dev.kyungmin.kcoach.core.ManualSetOperation
import dev.kyungmin.kcoach.core.ManualSetStatus
import dev.kyungmin.kcoach.core.WireCodec

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val emulatorBridge = EmulatorBridgeClient { _, _ -> }
    private lateinit var otaCoordinator: dev.kyungmin.kcoach.updates.OtaCoordinator
    private var unsubscribeUpdates: (() -> Unit)? = null
    private var updateStateText: TextView? = null
    private val isEmulator: Boolean
        get() = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gwear", ignoreCase = true)

    private var paired = false
    private var running = false
    private var resting = false
    private var paused = false
    private var screenDimmed = false
    private var sessionId = ""
    private var exerciseId = ""
    private var setId = ""
    private var attemptId = ""
    private var manualRequired = false
    private var manualStatus = ""
    private var exerciseName = "운동 대기 중"
    private var setIndex = 0
    private var targetReps = 10
    private var weightKg: Double? = null
    private var restSeconds = 60
    private var restEndAtMs = 0L
    private var reps = 0
    private var heartRate = 0
    private var runtimePhase = "IDLE"
    private var personalizedTracking = false
    private var shadowCommandId = ""
    private var shadowCount = 0
    private var shadowEventIndex = -1
    private var shadowEmissionNs = -1L
    private var frameCommitPending = ""
    private lateinit var face: WatchWorkoutFace
    private lateinit var pages: WorkoutPagesView
    private lateinit var detailsTitle: TextView
    private lateinit var detailsSubtitle: TextView
    private lateinit var editCountButton: TextView
    private val homeStore by lazy { WatchHomeStore(this) }
    private var phoneResultStatus = ""
    private var phoneResultPending = false
    private var resultGeneration = 0
    private var displayedCompletionSession = ""
    internal var resultOpener: (Intent, (Boolean) -> Unit) -> Unit = { request, done -> WatchPhoneResult.open(this, request, done) }
    private val clockTicker = object : Runnable {
        override fun run() { if (::face.isInitialized) face.clock.text = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")); handler.postDelayed(this, 30_000) }
    }
    private lateinit var shadowText: TextView

    private val runtimeStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == WearWorkoutService.ACTION_STATE) applyRuntimeState(intent)
            else if (intent.action == WatchHomeStore.ACTION_OVERVIEW) updateUi()
            else if (intent.action == WearWorkoutService.ACTION_WORKOUT_CONTROL) {
                updateUi()
                val message = when (intent.getStringExtra("status")) {
                    "STALE", "NO_PLAN" -> "폰에서 루틴을 다시 선택해 주세요"
                    "BUSY" -> "폰에서 진행 중인 운동을 확인해 주세요"
                    "REJECTED" -> "요청한 세트가 변경됐어요"
                    else -> null
                }
                if (message != null) Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private lateinit var connectionText: TextView
    private lateinit var phaseText: TextView
    private lateinit var restText: TextView
    private lateinit var restRing: RestProgressRing
    private lateinit var manualButton: TextView
    private lateinit var pendingManualButton: TextView
    private val manualOutbox by lazy { ManualSetOutbox(this) }
    private val workoutControls by lazy { WatchWorkoutOutbox(this) }
    private lateinit var workoutControlButton: TextView
    private lateinit var directionBypassButton: TextView
    private lateinit var tempoText: TextView
    private lateinit var tempoMuteButton: TextView
    private var tempoEnabled = false
    private var tempoHint = ""
    private val restUiTicker = object : Runnable {
        override fun run() {
            if (!resting) return
            updateUi()
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        otaCoordinator = dev.kyungmin.kcoach.updates.OtaCoordinator.get(this)
        setContentView(buildUi())
        unsubscribeUpdates = otaCoordinator.observe { state ->
            updateStateText?.text = state.message
            updateStateText?.visibility = if (otaCoordinator.store.local()?.phase == dev.kyungmin.kcoach.updates.OtaPhase.USER_ACTION_REQUIRED) View.VISIBLE else View.GONE
        }
        emulatorBridge.start()
        startService(Intent(this, WearCommandService::class.java))
        startService(Intent(this, WearWorkoutService::class.java))
        registerReceiver(runtimeStateReceiver, IntentFilter(WearWorkoutService.ACTION_STATE).apply {
            addAction(WearWorkoutService.ACTION_WORKOUT_CONTROL)
            addAction(WatchHomeStore.ACTION_OVERVIEW)
        }, android.content.Context.RECEIVER_NOT_EXPORTED)
        if (!isEmulator) requestPermissionsIfNeeded()
        updateUi()
        startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, "QUERY"))
        applyCommandIntent(intent)
        sendWatchUiReady()
    }

    private fun sendWatchUiReady() {
        val payload = WearSync.watchUiReady(sessionId)
        Thread {
            runCatching {
                Tasks.await(Wearable.getNodeClient(this).connectedNodes).forEach { node ->
                    Tasks.await(Wearable.getMessageClient(this).sendMessage(node.id, WearSync.PATH_WATCH_UI_READY, payload.toByteArray()))
                }
            }
            emulatorBridge.send(WearSync.PATH_WATCH_UI_READY, payload)
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyCommandIntent(intent)
    }

    private fun applyCommandIntent(intent: Intent) {
        val command = intent.getStringExtra("command") ?: return
        startService(Intent(this, WearWorkoutService::class.java).apply {
            putExtra(WearWorkoutService.EXTRA_COMMAND, command)
            putExtra("session", intent.getStringExtra("session").orEmpty())
            putExtra("exerciseId", intent.getStringExtra("exerciseId").orEmpty())
            putExtra("exerciseName", intent.getStringExtra("exerciseName").orEmpty())
            putExtra("setIndex", intent.getIntExtra("setIndex", 0))
            putExtra("targetReps", intent.getIntExtra("targetReps", 10))
            putExtra("weightKg", intent.getDoubleExtra("weightKg", Double.NaN))
            putExtra("restSeconds", intent.getIntExtra("restSeconds", 60))
            putExtra("restEndAtMs", intent.getLongExtra("restEndAtMs", 0L))
        })
    }

    private fun applyRuntimeState(intent: Intent) {
        paired = intent.getBooleanExtra("paired", paired)
        running = intent.getBooleanExtra("running", running)
        resting = intent.getBooleanExtra("resting", resting)
        paused = intent.getBooleanExtra("paused", paused)
        sessionId = intent.getStringExtra("session").orEmpty()
        exerciseId = intent.getStringExtra("exerciseId").orEmpty()
        setId = intent.getStringExtra("setId").orEmpty()
        attemptId = intent.getStringExtra("attemptId").orEmpty()
        manualRequired = intent.getBooleanExtra("manualRequired", false)
        manualStatus = intent.getStringExtra("manualStatus").orEmpty()
        tempoEnabled = intent.getBooleanExtra("tempoEnabled", false)
        tempoHint = intent.getStringExtra("tempoHint").orEmpty()
        exerciseName = intent.getStringExtra("exerciseName").orEmpty().ifBlank { "운동 대기 중" }
        setIndex = intent.getIntExtra("setIndex", setIndex)
        targetReps = intent.getIntExtra("targetReps", targetReps).coerceAtLeast(1)
        weightKg = intent.getDoubleExtra("weightKg", Double.NaN).takeUnless { it.isNaN() }
        restSeconds = intent.getIntExtra("restSeconds", restSeconds).coerceAtLeast(0)
        restEndAtMs = intent.getLongExtra("restEndAtMs", restEndAtMs)
        reps = intent.getIntExtra("reps", reps)
        heartRate = intent.getIntExtra("heartRate", heartRate)
        runtimePhase = intent.getStringExtra("phase").orEmpty().ifBlank { runtimePhase }
        personalizedTracking = intent.getBooleanExtra("personalized", personalizedTracking)
        shadowCommandId = intent.getStringExtra("patternShadowCommandId").orEmpty()
        shadowCount = intent.getIntExtra("patternShadowCount", 0)
        shadowEventIndex = intent.getIntExtra("patternShadowEventIndex", -1)
        shadowEmissionNs = intent.getLongExtra("patternShadowEmissionNs", -1L)
        if (running || resting || paused || sessionId.isNotBlank()) {
            homeStore.leaveCompletion()
            resultGeneration += 1; phoneResultPending = false; phoneResultStatus = ""
        }
        updateScreenPolicy()
        updateUi()
        handler.removeCallbacks(restUiTicker)
        if (resting) handler.post(restUiTicker)
    }

    override fun onResume() {
        super.onResume()
        updateScreenPolicy()
        otaCoordinator.attach(this)
        handler.removeCallbacks(clockTicker)
        handler.post(clockTicker)
        updateUi()
    }

    override fun onPause() {
        otaCoordinator.detach(this)
        handler.removeCallbacks(clockTicker)
        super.onPause()
    }

    override fun onStop() {
        // The host relay and timers are intentionally kept alive across the
        // screen-off/launcher transition. A real session must not stop merely
        // because the round display is no longer foregrounded.
        super.onStop()
    }

    override fun onDestroy() {
        resultGeneration += 1
        handler.removeCallbacks(clockTicker)
        unsubscribeUpdates?.invoke()
        handler.removeCallbacks(restUiTicker)
        runCatching { unregisterReceiver(runtimeStateReceiver) }
        emulatorBridge.stop()
        super.onDestroy()
    }

    private fun buildUi(): View {
        face = WatchWorkoutFace(this)
        face.action.setOnClickListener { openFinishedWorkout() }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(38), dp(28), dp(38), dp(30)); setBackgroundColor(Color.BLACK)
        }
        fun add(view: TextView, action: Boolean = false) {
            details.addView(view, LinearLayout.LayoutParams(-1, if (action) dp(48) else -2).apply { bottomMargin = dp(6) })
        }
        fun button(text: String, primary: Boolean = false, click: () -> Unit) = label(text, 15f,
            if (primary) Color.rgb(6, 26, 19) else WatchWorkoutFace.WHITE, true).apply {
            gravity = Gravity.CENTER; minHeight = dp(48); isFocusable = true
            background = rounded(if (primary) WatchWorkoutFace.GREEN else Color.rgb(24, 29, 33), 6)
            setOnClickListener { click() }
        }
        detailsTitle = label("", 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER }; add(detailsTitle)
        detailsSubtitle = label("", 12f, WatchWorkoutFace.MUTED).apply { gravity = Gravity.CENTER }; add(detailsSubtitle)
        manualButton = button("세트 마치기", true) { finishCurrentSet() }; add(manualButton, true)
        editCountButton = button("횟수 수정") { showManualCount(editOnly = true) }; add(editCountButton, true)
        workoutControlButton = button("휴식 끝내고 다음 세트", true) { requestWorkoutControl() }; add(workoutControlButton, true)
        directionBypassButton = button("직접 시작") {
            startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, "START_DIRECTION_BYPASS")
                .putExtra("session", sessionId).putExtra("setId", setId).putExtra("attemptId", attemptId))
        }; add(directionBypassButton, true)
        phaseText = label("", 12f, WatchWorkoutFace.ORANGE).apply { gravity = Gravity.CENTER }; add(phaseText)
        connectionText = label("", 12f, WatchWorkoutFace.ORANGE).apply { gravity = Gravity.CENTER }; add(connectionText)
        shadowText = label("", 11f, WatchWorkoutFace.MUTED).apply { gravity = Gravity.CENTER }; add(shadowText)
        tempoText = label("", 12f, WatchWorkoutFace.MUTED).apply { gravity = Gravity.CENTER }; add(tempoText)
        tempoMuteButton = button("템포 알림 끄기") {
            startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, "TEMPO_MUTE")
                .putExtra("session", sessionId).putExtra("setId", setId).putExtra("attemptId", attemptId))
        }; add(tempoMuteButton, true)
        restText = label("", 12f, WatchWorkoutFace.MUTED).apply { gravity = Gravity.CENTER }; add(restText)
        pendingManualButton = button("보관 중인 횟수") {
            AlertDialog.Builder(this).setTitle("보관 중인 횟수").setMessage(manualOutbox.unresolved().joinToString("\n") {
                "${it.command.setIndex + 1}세트 · ${it.command.performedReps}회 · " +
                    if (it.ack?.status == ManualSetStatus.CONFLICT) "폰 기록과 달라요" else "폰에서 확인해 주세요"
            }).setPositiveButton("닫기", null).show()
        }; add(pendingManualButton, true)
        updateStateText = button("폰이 요청한 업데이트 계속") { otaCoordinator.confirmInstall(this) }
            .apply { visibility = View.GONE }; add(requireNotNull(updateStateText), true)
        add(label("⌃ 위로 · 횟수 화면", 11f, WatchWorkoutFace.MUTED).apply { gravity = Gravity.CENTER })
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(face, FrameLayout.LayoutParams(-1, -1)) }
        restRing = RestProgressRing().apply { visibility = View.GONE; isClickable = false }
        frame.addView(restRing, FrameLayout.LayoutParams(-1, -1))
        pages = WorkoutPagesView(this, frame, details)
        return pages
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun requestPermissionsIfNeeded() {
        val permissions = listOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val missing = permissions
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 42)
    }

    private fun updateScreenPolicy() {
        // The open app must remain visible while waiting for the phone too.
        // This window flag only keeps the display awake while this activity is foregrounded.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Dim only while a set is actively being measured; rest, pause and idle use the normal brightness.
        val dim = running && !resting && !paused
        if (dim != screenDimmed) {
            screenDimmed = dim
            window.attributes = window.attributes.also {
                it.screenBrightness = if (dim) WORKING_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    private fun updateUi() {
        if (!::face.isInitialized) return
        updateScreenPolicy()
        face.clock.text = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val active = running || resting || paused || sessionId.isNotBlank() && setId.isNotBlank()
        val complete = !active && homeStore.showsCompletion()
        if (complete && displayedCompletionSession != homeStore.completion()?.sessionId) {
            displayedCompletionSession = homeStore.completion()?.sessionId.orEmpty()
            resultGeneration += 1; phoneResultPending = false; phoneResultStatus = ""
        }
        val count = displayedCount()
        face.primary(!active || resting)
        face.homeLayout(!active && !complete)
        face.value.maxLines = if (complete) 2 else 1
        face.showDivider = active && !resting
        face.invalidate()
        listOf(face.target, face.weight, face.unit, face.heart, face.heartRate).forEach { it.visibility = if (active) View.VISIBLE else View.GONE }
        face.down.text = "⌄"; face.down.visibility = if (active || manualOutbox.unresolved().isNotEmpty()) View.VISIBLE else View.GONE
        face.action.visibility = if (complete) View.VISIBLE else View.GONE
        face.action.text = "폰에서 보기"; face.action.isEnabled = !phoneResultPending
        face.action.alpha = if (phoneResultPending) 0.5f else 1f
        face.hint.text = ""
        face.value.textSize = if (active) 48f else 24f
        face.value.setTextColor(WatchWorkoutFace.GREEN)
        if (active) {
            face.title.text = exerciseName
            face.subtitle.text = when { resting -> "휴식"; paused -> "일시정지"; manualRequired -> "${setIndex + 1}세트 · 직접 기록"; else -> "${setIndex + 1}세트 · 자동 측정" }
            face.target.text = "/ ${targetReps}회"
            face.value.text = count?.toString() ?: "—"
            face.value.setTextColor(if (count != null && count > targetReps) WatchWorkoutFace.ORANGE else WatchWorkoutFace.GREEN)
            face.weight.text = weightKg?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) } ?: "—"
            face.unit.text = "kg"; face.heart.text = "♥"; face.heartRate.text = heartRate.takeIf { it > 0 }?.toString() ?: "—"
            face.hint.text = if (manualRequired && count == null) "아래로 · 횟수 기록" else ""
            if (running && runtimePhase in setOf("POSITIONING", "PREPARING", "BASELINE", "START_DIRECTION_WAITING")) {
                face.value.text = "준비"; face.value.textSize = 28f
                face.hint.text = if (runtimePhase == "START_DIRECTION_WAITING") "손목 방향을 맞춰 주세요" else "손목을 잠시 멈춰 주세요"
            }
            if (resting) {
                val left = RestCountdownCalculator.calculate(System.currentTimeMillis(), restEndAtMs, restSeconds).remainingSeconds
                face.value.text = String.format("%d:%02d", left / 60, left % 60)
                face.target.visibility = View.GONE
                face.hint.text = "다음 세트 · ${targetReps}회"
            }
            if (paused) face.hint.text = "폰에서 계속할 수 있어요"
        } else if (complete) {
            face.title.text = "운동 완료"; face.subtitle.text = "수고했어요"
            face.value.text = if (phoneResultStatus.isBlank()) "기록을 확인해 보세요" else phoneResultStatus
            face.value.textSize = 14f; face.value.setTextColor(WatchWorkoutFace.WHITE)
        } else {
            face.title.text = "이번 주 운동"; face.subtitle.text = ""
            face.value.text = homeStore.weekCount()?.let { "${it}회" } ?: "—"
            face.value.textSize = 40f
            val recent = homeStore.overview()?.optJSONObject("recent")
            face.hint.text = recent?.let {
                val date = java.time.Instant.ofEpochMilli(it.getLong("endedAtMs")).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                "최근 · ${it.getString("routineName")}\n${date.monthValue}월 ${date.dayOfMonth}일"
            } ?: "최근 기록이 아직 없어요"
            // A missing overview is unknown, not an empty workout history.
            if (homeStore.overview() == null) face.hint.text = "폰에서 기록을 확인하세요"
        }
        detailsTitle.text = if (active) exerciseName else if (complete) "운동 완료" else "운동 기록"
        detailsSubtitle.text = if (active) "${setIndex + 1}세트 · 현재 ${count?.let { "${it}회" } ?: "미입력"}" else ""
        connectionText.text = "폰 연결이 끊겼어요 · 기록은 보관돼요"
        connectionText.visibility = if (active && !paired) View.VISIBLE else View.GONE
        phaseText.text = when (runtimePhase) {
            "CAPTURE_RECOVERY_REQUIRED" -> "센서 기록 복구가 필요해요"
            "MANUAL_PENDING" -> "폰에 기록 전달 중"
            "MANUAL_CONFLICT" -> "폰과 워치의 횟수를 확인해 주세요"
            else -> ""
        }
        phaseText.visibility = if (phaseText.text.isEmpty()) View.GONE else View.VISIBLE
        val editable = active && !resting && !sessionId.startsWith("calibration-") && setId.isNotBlank() && attemptId.isNotBlank() && runtimePhase != "CAPTURE_RECOVERY_REQUIRED"
        manualButton.visibility = if (editable) View.VISIBLE else View.GONE
        editCountButton.visibility = if (editable) View.VISIBLE else View.GONE
        val pending = manualStatus in setOf("PENDING", "STALE", "NO_SESSION", "REJECTED")
        manualButton.isEnabled = !pending && !paused; editCountButton.isEnabled = !pending && !paused
        manualButton.text = when (manualStatus) {
            "PENDING" -> "폰에 기록 전달 중"
            "CONFLICT" -> "횟수 확인"
            "STALE", "NO_SESSION", "REJECTED" -> "폰에서 세트를 확인하세요"
            "APPLIED", "DUPLICATE" -> "기록 수정"
            else -> if (count == null) "횟수 입력" else "세트 마치기\n${count}회로 기록"
        }
        workoutControlButton.visibility = if (resting && workoutControls.supportsControls() && setId.isNotBlank() && attemptId.isNotBlank()) View.VISIBLE else View.GONE
        workoutControlButton.isEnabled = workoutControls.pending().isEmpty() && !paused
        workoutControlButton.text = if (workoutControls.pending().isEmpty()) "휴식 끝내고 다음 세트" else "폰에 요청 전달 중"
        directionBypassButton.visibility = if (running && runtimePhase == "START_DIRECTION_WAITING") View.VISIBLE else View.GONE
        // Tempo and diagnostics stay in secondary controls, never below the main counter.
        tempoText.visibility = View.GONE
        tempoMuteButton.visibility = if (active && !resting && tempoEnabled) View.VISIBLE else View.GONE
        shadowText.visibility = if (running && shadowCommandId.isNotBlank()) View.VISIBLE else View.GONE
        shadowText.text = "보정 비교 · ${shadowCount}회"; recordShadowFrameCommit()
        pendingManualButton.visibility = if (manualOutbox.unresolved().isEmpty()) View.GONE else View.VISIBLE
        pendingManualButton.text = "보관 중인 횟수 ${manualOutbox.unresolved().size}건"
        restText.visibility = View.GONE
        if (resting && restEndAtMs > System.currentTimeMillis()) {
            restRing.setWindow(restEndAtMs - restSeconds.coerceAtLeast(1) * 1_000L, restEndAtMs); restRing.visibility = View.VISIBLE
        } else restRing.visibility = View.GONE
        val presentation = if (active) "active" else if (complete) "complete" else "home"
        if (lastPresentation != presentation) {
            lastPresentation = presentation
            pages.scrollTo(0, 0)
        }
    }

    private var lastPresentation = ""
    private fun draftKey() = "$sessionId:$setId:$attemptId"
    private fun displayedCount(): Int? = getSharedPreferences("manual-count-drafts", MODE_PRIVATE).getString(draftKey(), null)?.toIntOrNull()
        ?: if (manualRequired && manualStatus.isBlank()) null else reps

    private fun finishCurrentSet() {
        if (paused || resting || setId.isBlank() || attemptId.isBlank() || manualStatus in setOf("PENDING", "STALE", "NO_SESSION", "REJECTED")) return
        if (manualStatus == "CONFLICT" || displayedCount() == null) { showManualCount(); return }
        val count = requireNotNull(displayedCount())
        val existing = manualOutbox.latest(sessionId, setId, attemptId)
        val command = ManualSetCommand(java.util.UUID.randomUUID().toString(), sessionId, exerciseId, setIndex, setId, attemptId,
            count, existing?.ack?.currentRevision ?: 0L, if (existing?.ack != null) ManualSetOperation.EDIT else ManualSetOperation.COMPLETE)
        startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, "MANUAL_SUBMIT")
            .putExtra("payload", WireCodec.encode(command.toEnvelope(android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis()))))
        manualStatus = "PENDING"; updateUi()
    }

    private fun openFinishedWorkout() {
        if (running || resting || paused || sessionId.isNotBlank() || phoneResultPending) return
        val completed = homeStore.completion() ?: return
        val generation = ++resultGeneration
        phoneResultPending = true; phoneResultStatus = "폰으로 요청하는 중"; updateUi()
        fun finish(success: Boolean) {
            if (isDestroyed || generation != resultGeneration) return
            resultGeneration += 1; phoneResultPending = false
            phoneResultStatus = if (success) "폰에서 확인해 주세요" else "연결을 확인하고\n다시 눌러 주세요"
            updateUi()
        }
        handler.postDelayed({ finish(false) }, 15_000)
        runCatching { resultOpener(WatchPhoneResult.intent(completed.sessionId), ::finish) }.onFailure { finish(false) }
    }

    private fun recordShadowFrameCommit() {
        if (!running || shadowCommandId.isBlank() || shadowEventIndex < 0 || shadowEmissionNs < 0 ||
            !shadowText.isAttachedToWindow || !shadowText.isHardwareAccelerated || !window.decorView.hasWindowFocus() ||
            !shadowText.getGlobalVisibleRect(android.graphics.Rect())) return
        val identity = "$sessionId|$setId|$attemptId|$shadowCommandId|$shadowEventIndex|$shadowEmissionNs"
        if (frameCommitPending == identity) return
        frameCommitPending = identity
        val session = sessionId; val set = setId; val attempt = attemptId
        val command = shadowCommandId; val index = shadowEventIndex; val emission = shadowEmissionNs
        shadowText.viewTreeObserver.registerFrameCommitCallback {
            val submittedAt = android.os.SystemClock.elapsedRealtimeNanos()
            runOnUiThread {
                if (isFinishing || isDestroyed || sessionId != session || setId != set || attemptId != attempt ||
                    shadowCommandId != command || shadowEventIndex != index || shadowEmissionNs != emission ||
                    !shadowText.getGlobalVisibleRect(android.graphics.Rect())) return@runOnUiThread
                startService(Intent(this, WearWorkoutService::class.java)
                    .putExtra(WearWorkoutService.EXTRA_COMMAND, "PATTERN_FRAME_COMMIT")
                    .putExtra("session", session).putExtra("setId", set).putExtra("attemptId", attempt)
                    .putExtra("patternCommandId", command).putExtra("eventIndex", index)
                    .putExtra("sourceEmissionNs", emission).putExtra("feedbackAtNs", submittedAt))
            }
        }
        shadowText.invalidate()
    }

    private fun requestWorkoutControl() {
        if (!resting || paused || workoutControls.pending().isNotEmpty() || !workoutControls.supportsControls() || setId.isBlank() || attemptId.isBlank()) return
        val request = dev.kyungmin.kcoach.core.WatchWorkoutControl(java.util.UUID.randomUUID().toString(),
            dev.kyungmin.kcoach.core.WatchWorkoutAction.NEXT_SET, sessionId, setId, attemptId)
        startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, "WORKOUT_CONTROL_SUBMIT")
            .putExtra("payload", request.encode()))
    }

    private fun showManualCount(editOnly: Boolean = false) {
        if (manualStatus == "PENDING" || setId.isBlank() || attemptId.isBlank()) return
        val session = sessionId
        val exercise = exerciseId
        val index = setIndex
        val set = setId
        val attempt = attemptId
        val existing = manualOutbox.latest(session, set, attempt)
        val revision = existing?.ack?.currentRevision ?: 0L
        fun submit(count: Int) {
            if (sessionId != session || setId != set || attemptId != attempt || resting || paused) {
                Toast.makeText(this, "세트가 바뀌었어요. 현재 세트를 확인해 주세요", Toast.LENGTH_LONG).show()
                return
            }
            if (editOnly) {
                getSharedPreferences("manual-count-drafts", MODE_PRIVATE).edit().putString("$session:$set:$attempt", count.toString()).apply()
                updateUi()
                return
            }
            val command = ManualSetCommand(java.util.UUID.randomUUID().toString(), session, exercise, index, set, attempt,
                count, revision, if (existing?.ack != null) ManualSetOperation.EDIT else ManualSetOperation.COMPLETE)
            startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, "MANUAL_SUBMIT")
                .putExtra("payload", WireCodec.encode(command.toEnvelope(android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis()))))
            manualStatus = "PENDING"; updateUi()
        }
        fun editor() {
            val draftPrefs = getSharedPreferences("manual-count-drafts", MODE_PRIVATE)
            val key = "$session:$set:$attempt"
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(4))
                hint = "횟수"
                setText(draftPrefs.getString(key, null) ?: existing?.command?.performedReps?.toString() ?: if (!manualRequired) reps.toString() else "")
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        draftPrefs.edit().putString(key, s.toString()).apply()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(input) }
            controls.addView(LinearLayout(this).apply {
                listOf("−" to -1, "+" to 1).forEach { (label, delta) ->
                    addView(this@MainActivity.label(label, 22f, Color.WHITE, true).apply {
                        gravity = Gravity.CENTER
                        setPadding(12, 8, 12, 8)
                        setOnClickListener { input.setText(((input.text.toString().toIntOrNull() ?: 0) + delta).coerceIn(0, 9_999).toString()) }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
            })
            val dialog = AlertDialog.Builder(this).setTitle("${index + 1}세트 · 목표 ${targetReps}회")
                .setView(controls).setNegativeButton("취소", null).setPositiveButton(if (editOnly) "적용" else "기록", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val count = input.text.toString().toIntOrNull()?.takeIf { it in 0..9_999 }
                    if (count == null) { input.error = "실제 횟수를 입력해 주세요"; return@setOnClickListener }
                    submit(count)
                    dialog.dismiss()
                }
            }
            dialog.show()
        }
        if (!editOnly && existing?.ack?.status == ManualSetStatus.CONFLICT) {
            val phoneCount = existing.ack.currentReps
            AlertDialog.Builder(this).setTitle("어떤 횟수를 기록할까요?")
                .setItems(arrayOf("폰 ${phoneCount ?: "—"}회 유지", "워치 ${existing.command.performedReps}회 적용", "직접 수정")) { _, which ->
                    when (which) { 0 -> phoneCount?.let(::submit); 1 -> submit(existing.command.performedReps); else -> editor() }
                }.show()
        } else editor()
    }

    private inner class RestProgressRing : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(255, 177, 0)
        }
        private var startAtMs = 0L
        private var endAtMs = 0L

        fun setWindow(startAtMs: Long, endAtMs: Long) {
            this.startAtMs = startAtMs
            this.endAtMs = endAtMs
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = System.currentTimeMillis()
            val total = endAtMs - startAtMs
            val remaining = (endAtMs - now).coerceAtLeast(0L)
            if (total <= 0L || remaining <= 0L) return
            val fraction = (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            val density = resources.displayMetrics.density
            // A real round Wear display is normally square. Some emulator profiles
            // expose a tall canvas, so use the top square display region instead of
            // centering the ring in the entire scrollable-height surface.
            val canvasWidth = width.toFloat()
            val canvasHeight = height.toFloat()
            val roundDiameter = if (canvasHeight > canvasWidth * 1.15f) {
                canvasWidth
            } else {
                minOf(canvasWidth, canvasHeight)
            }
            val roundTop = if (canvasHeight > canvasWidth * 1.15f) 0f else {
                (canvasHeight - roundDiameter) / 2f
            }
            val inset = 8f * density
            val left = (canvasWidth - roundDiameter) / 2f + inset
            val top = roundTop + inset
            val right = (canvasWidth + roundDiameter) / 2f - inset
            val bottom = roundTop + roundDiameter - inset
            paint.strokeWidth = 2.5f * density
            // -90 degrees is 12 o'clock: the ring advances clockwise from the
            // top-center of the circular watch face.
            canvas.drawArc(RectF(left, top, right, bottom), -90f, 360f * fraction, false, paint)
            postInvalidateOnAnimation()
        }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        minHeight = 0
        minimumHeight = 0
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 0)
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
            if (strokeColor != null) setStroke(strokeDp, strokeColor)
        }
}

private const val WORKING_BRIGHTNESS = 0.1f
