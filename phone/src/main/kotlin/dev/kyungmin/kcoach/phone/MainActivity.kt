package dev.kyungmin.kcoach.phone

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.DragEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import dev.kyungmin.kcoach.core.WearSync
import dev.kyungmin.kcoach.core.KCoachEventMarker
import dev.kyungmin.kcoach.core.KCoachEventTrace
import dev.kyungmin.kcoach.core.CaptureState
import dev.kyungmin.kcoach.core.PlannedSetSnapshot
import dev.kyungmin.kcoach.core.WorkoutPhase
import dev.kyungmin.kcoach.core.WorkoutSnapshot
import dev.kyungmin.kcoach.core.WireCodec
import dev.kyungmin.kcoach.core.WirePayloadKeys
import dev.kyungmin.kcoach.core.SetCompletionEvent
import dev.kyungmin.kcoach.core.SetCompletionResult
import dev.kyungmin.kcoach.core.ExerciseTrackingProfiles
import dev.kyungmin.kcoach.core.PersonalCalibrationBuilder
import dev.kyungmin.kcoach.core.PersonalCalibrationProfileCodec
import dev.kyungmin.kcoach.core.TrackingMode
import com.google.android.gms.wearable.Wearable
import java.time.LocalDate
import java.time.YearMonth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    private enum class RootTab { HOME, RECORDS, STATS, CALIBRATION, MENU }
    private enum class Screen { ROOT, ROUTINE, FREE, LIBRARY, EXERCISE_INFO, EXERCISE_RECORD, ACTIVE, REST, MY_MOUNTAIN }

    private data class SetAnalysisUi(
        val exerciseId: String,
        val exerciseName: String,
        val setIndex: Int,
        val watchReps: Int,
        val phoneReps: Int,
        val confirmedReps: Int,
        val confidence: Double,
        val trackingMode: String = "WRIST_IMU_CAUTION",
        val trackingProfileId: String = "generic-wrist",
        val trackingStatus: String = "",
        val capabilityTier: String = "C_PERSONALIZED_AUTO_COUNT_BETA",
        val repConfidence: Double? = null,
        val wristMotionSpeed: Double? = null,
        val wristMotionRange: Double? = null,
        val tempoConsistency: Int? = null,
        val signalQuality: Double? = null,
        val averageSpeed: Double? = null,
        val relativeRom: Double? = null,
    )

    private data class WorkoutResultUi(
        val sessionId: String,
        val title: String,
        val durationMinutes: Int,
        val completedSets: Int,
        val analyses: MutableMap<String, SetAnalysisUi> = mutableMapOf(),
    )

    private data class ActiveWorkout(
        val routine: WorkoutRoutine,
        val startedAtMs: Long = System.currentTimeMillis(),
        val sessionId: String = "phone-${System.currentTimeMillis()}",
        var exerciseIndex: Int = 0,
        var setIndex: Int = 0,
        var completedSets: Int = 0,
        val performedRepsByExercise: MutableMap<String, Int> = mutableMapOf(),
        var paused: Boolean = false,
        var pausedSinceMs: Long = 0L,
        var totalPausedMs: Long = 0L,
        var usePounds: Boolean = false,
        var selectedEditSetIndex: Int = 0,
        var scrollY: Int = 0,
        val setIndexByExercise: MutableMap<String, Int> = mutableMapOf(),
        val selectedSetIndexByExercise: MutableMap<String, Int> = mutableMapOf(),
        val scrollYByExercise: MutableMap<String, Int> = mutableMapOf(),
        val analyses: MutableMap<String, SetAnalysisUi> = mutableMapOf(),
        var phase: WorkoutPhase = WorkoutPhase.WORKING,
        var lastSequence: Long = 0L,
        var lastCompletedSetKey: String? = null,
        val captureStates: MutableMap<String, CaptureState> = mutableMapOf(),
    )

    private data class ActiveWorkoutUi(
        val workout: ActiveWorkout,
        val exerciseIndex: Int,
        val setRows: LinearLayout,
        val target: TextView,
        val performed: TextView,
        val summary: TextView,
        val restDuration: TextView,
        val restProgress: ProgressBar,
    )

    private data class NextWorkoutTarget(
        val exerciseId: String,
        val exerciseName: String,
        val setIndex: Int,
        val targetReps: Int,
        val weightKg: Double?,
    )

    private val bg = Color.rgb(10, 12, 12)
    private val surface = Color.rgb(23, 26, 26)
    private val surfaceRaised = Color.rgb(31, 34, 34)
    private val white = Color.rgb(245, 246, 246)
    private val muted = Color.rgb(145, 150, 150)
    private val faint = Color.rgb(79, 84, 84)
    private val amber = Color.rgb(255, 177, 0)
    private val amberDark = Color.rgb(208, 128, 0)
    private val blue = Color.rgb(83, 142, 255)
    private val green = Color.rgb(102, 226, 153)

    private val routines = WorkoutData.defaultRoutines()
    private val freeExercises = mutableListOf<RoutineExercise>()
    private val handler = Handler(Looper.getMainLooper())

    private var wearLiveStatus: TextView? = null
    private var activeLiveStatus: TextView? = null
    private var activeWorkoutUi: ActiveWorkoutUi? = null
    private val animationConstantStates = mutableMapOf<Int, Drawable.ConstantState>()
    private var restHeartStatus: TextView? = null
    private var activeRestStartAtMs = 0L
    private var activeRestEndAtMs = 0L
    private var pausedRestRemainingMs = 0L
    private var lastCompletedLiveKey: String? = null
    private var lastWearLiveSessionId = ""
    private var lastWearLiveTitle = ""
    private var lastWearLiveTelemetry = ""
    private var workoutTransitionRunning = false
    private var detailTransitionRunning = false
    private val wearReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                val payload = intent.getStringExtra("payload").orEmpty()
                when (intent.action) {
                PhoneWearListenerService.ACTION_PAIR -> {
                    wearLiveStatus?.apply {
                        if (tag != WATCH_PANEL_TELEMETRY) {
                            text = "⌚ Watch8 연결됨"
                            setTextColor(green)
                        }
                    }
                }
                PhoneWearListenerService.ACTION_WATCH_UI_READY -> {
                    watchUiReady = true
                    wearLiveStatus?.apply {
                        if (tag != WATCH_PANEL_TELEMETRY) {
                            text = "⌚ Watch8 연결됨 · 운동 시작 가능"
                            setTextColor(green)
                        }
                    }
                    pendingWatchStartPayload?.let { payload ->
                        pendingWatchStartPayload = null
                        sendWear(WearSync.PATH_COMMAND, payload)
                        activeLiveStatus?.text = "워치 앱 준비 완료 · 운동 시작"
                    } ?: activeWorkout?.let { workout ->
                        resyncActiveWorkoutToWatch(workout)
                    }
                }
                PhoneWearListenerService.ACTION_LIVE -> {
                    val fields = WearSync.parse(payload)
                    if (fields.getOrNull(1) == calibrationSessionId) {
                        val phase = fields.getOrNull(9).orEmpty()
                        val reps = fields.getOrNull(5)?.toIntOrNull() ?: 0
                        calibrationButton?.text = when (phase) {
                            "BASELINE" -> "기준 자세 유지 중…"
                            "CONTROLLED_REPS" -> "자연스러운 왕복 $reps/5회"
                            "CALIBRATION_RESULT" -> "원본을 폰으로 전송 중…"
                            else -> calibrationButton?.text
                        }
                    } else {
                        handleLivePayload(payload)
                    }
                }
                PhoneWearListenerService.ACTION_STATE -> {
                    val fields = WearSync.parse(payload)
                    if (
                        fields.size >= 7 &&
                        currentScreen != Screen.ACTIVE &&
                        wearLiveStatus?.tag != WATCH_PANEL_TELEMETRY
                    ) {
                        val reps = fields[4]
                        val heart = fields[5]
                        val active = fields[6].toBooleanStrictOrNull() == true
                        wearLiveStatus?.text = if (active) {
                            "⌚ Watch8 연결됨 · ${reps}회 · ❤️ ${heart}bpm"
                        } else {
                            "⌚ Watch8 연결됨 · ❤️ ${heart}bpm"
                        }
                        wearLiveStatus?.setTextColor(green)
                    }
                }
                PhoneWearListenerService.ACTION_COMPLETE -> {
                    val fields = WearSync.parse(payload)
                    val phase = fields.getOrNull(6).orEmpty()
                    wearLiveStatus?.takeIf { it.tag != WATCH_PANEL_TELEMETRY }?.apply {
                        text = if (phase == "COMPLETE") "⌚ Watch8 운동 데이터 수신 완료" else "⌚ Watch8 운동 종료"
                        setTextColor(green)
                    }
                }
                PhoneWearListenerService.ACTION_CAPTURE_MANIFEST -> {
                    runCatching { WireCodec.decode(payload) }.getOrNull()?.let { manifest ->
                        val captureId = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty()
                        activeWorkout?.takeIf { it.sessionId == manifest.sessionId }?.let { workout ->
                            if (captureId.isNotBlank()) workout.captureStates[captureId] = CaptureState.PENDING_UPLOAD
                            persistActiveWorkout(workout)
                        }
                    }
                }
                PhoneWearListenerService.ACTION_CAPTURE_STATE -> {
                    val captureId = intent.getStringExtra("captureId").orEmpty()
                    val sessionId = intent.getStringExtra("sessionId").orEmpty()
                    val state = intent.getStringExtra("captureState")
                        ?.let { name -> CaptureState.entries.firstOrNull { it.name == name } }
                        ?: return
                    activeWorkout?.takeIf { it.sessionId == sessionId }?.let { workout ->
                        if (captureId.isNotBlank()) workout.captureStates[captureId] = state
                        persistActiveWorkout(workout)
                    }
                    if (state == CaptureState.ANALYZING) {
                        activeLiveStatus
                            ?.takeIf { it.tag != WATCH_PANEL_TELEMETRY }
                            ?.text = "센서 원본 저장 완료 · 분석 중"
                    }
                }
                PhoneWearListenerService.ACTION_CALIBRATION_RAW_SAVED -> {
                    if (intent.getStringExtra("sessionId").orEmpty() == calibrationSessionId) {
                        calibrationButton?.apply {
                            text = "원본 저장 완료 · 개인 프로필 적용 중…"
                            isEnabled = false
                        }
                    }
                }
                PhoneWearListenerService.ACTION_CALIBRATION_RESULT -> {
                    val sessionId = intent.getStringExtra("sessionId").orEmpty()
                    if (sessionId != calibrationSessionId) return
                    cancelCalibrationSaveTimeout()
                    val succeeded = intent.getBooleanExtra("succeeded", false)
                    val validReps = intent.getIntExtra("validReps", 0)
                    if (succeeded) {
                        Toast.makeText(
                            this@MainActivity,
                            "완료! 유효 ${validReps}회 · 개인 프로필을 저장하고 실 운동에 적용했어",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        Toast.makeText(this@MainActivity, "유효 반복이 ${validReps}회라 프로필을 만들지 않았어 · 다시 보정해줘", Toast.LENGTH_LONG).show()
                    }
                    calibrationSessionId = null
                    activeCalibrationExerciseId = null
                    calibrationStopRequested = false
                    if (currentTab == RootTab.CALIBRATION) showCalibration()
                }
                PhoneWearListenerService.ACTION_CAPTURE -> {
                    val captureId = intent.getStringExtra("captureId").orEmpty()
                    val watch = intent.getIntExtra("watchReps", -1)
                    val phone = intent.getIntExtra("phoneReps", 0)
                    val confirmed = intent.getIntExtra("confirmedReps", phone)
                    val sessionId = intent.getStringExtra("sessionId").orEmpty()
                    val exerciseId = intent.getStringExtra("exerciseId").orEmpty()
                    val exerciseName = intent.getStringExtra("exerciseName").orEmpty().ifBlank { "운동" }
                    val setIndex = intent.getIntExtra("setIndex", 0)
                    if (calibrationSessionId == sessionId) {
                        val calibratedName = WorkoutData.catalog
                            .firstOrNull { it.id == activeCalibrationExerciseId }
                            ?.name ?: exerciseName
                        calibrationSessionId = null
                        activeCalibrationExerciseId = null
                        calibrationStopRequested = false
                        Toast.makeText(this@MainActivity, "$calibratedName 보정 원본을 저장했어", Toast.LENGTH_LONG).show()
                        if (currentTab == RootTab.CALIBRATION) showCalibration()
                        return@onReceive
                    }
                    val analysis = SetAnalysisUi(
                        exerciseId = exerciseId,
                        exerciseName = exerciseName,
                        setIndex = setIndex,
                        watchReps = watch.coerceAtLeast(0),
                        phoneReps = phone,
                        confirmedReps = confirmed,
                        confidence = intent.getDoubleExtra("analysisConfidence", 0.0),
                        trackingMode = intent.getStringExtra("trackingMode").orEmpty().ifBlank { "WRIST_IMU_CAUTION" },
                        trackingProfileId = intent.getStringExtra("trackingProfileId").orEmpty().ifBlank { "generic-wrist" },
                        trackingStatus = intent.getStringExtra("trackingStatus").orEmpty(),
                        capabilityTier = intent.getStringExtra("capabilityTier").orEmpty().ifBlank { "C_PERSONALIZED_AUTO_COUNT_BETA" },
                        repConfidence = intent.getDoubleExtra("repConfidence", Double.NaN).takeUnless { it.isNaN() },
                        wristMotionSpeed = intent.getDoubleExtra("wristMotionSpeed", Double.NaN).takeUnless { it.isNaN() },
                        wristMotionRange = intent.getDoubleExtra("wristMotionRange", Double.NaN).takeUnless { it.isNaN() },
                        tempoConsistency = intent.getIntExtra("tempoConsistency", -1).takeUnless { it < 0 },
                        signalQuality = intent.getDoubleExtra("signalQuality", Double.NaN).takeUnless { it.isNaN() },
                        averageSpeed = intent.getDoubleExtra("averageAngularSpeed", Double.NaN).takeUnless { it.isNaN() },
                        relativeRom = intent.getDoubleExtra("relativeRom", Double.NaN).takeUnless { it.isNaN() },
                    )
                    activeWorkout?.takeIf { it.sessionId == sessionId }?.let { workout ->
                        workout.analyses["$exerciseId:$setIndex"] = analysis
                        if (
                            captureId.isNotBlank() &&
                            workout.captureStates[captureId] != CaptureState.ACKED
                        ) {
                            workout.captureStates[captureId] = CaptureState.RECEIVED
                        }
                        persistActiveWorkout(workout)
                    }
                    lastWorkoutResult?.takeIf { it.sessionId == sessionId }?.analyses?.set("$exerciseId:$setIndex", analysis)
                    lastWorkoutResult?.takeIf { it.sessionId == sessionId }?.let(::persistLastWorkoutResult)
                    wearLiveStatus?.takeIf { it.tag != WATCH_PANEL_TELEMETRY }?.apply {
                        text = "⌚ 세트 분석 완료 · 워치 ${watch.coerceAtLeast(0)}회 · 폰 ${phone}회 · 확정 ${confirmed}회"
                        setTextColor(green)
                    }
                    if (currentScreen == Screen.ROOT && lastWorkoutResult?.sessionId == sessionId) showWorkoutResult(lastWorkoutResult!!)
                }
                PhoneWearListenerService.ACTION_CAPTURE_ERROR -> {
                    val captureId = intent.getStringExtra("captureId").orEmpty()
                    val stage = intent.getStringExtra("stage").orEmpty()
                    if (intent.getStringExtra("sessionId").orEmpty() == calibrationSessionId) {
                        cancelCalibrationSaveTimeout()
                        calibrationStopRequested = false
                        calibrationButton?.apply {
                            isEnabled = true
                            text = "보정 종료·저장"
                        }
                    }
                    activeWorkout?.let { workout ->
                        if (captureId.isNotBlank()) workout.captureStates[captureId] = CaptureState.ANALYSIS_FAILED
                        persistActiveWorkout(workout)
                    }
                    wearLiveStatus?.takeIf { it.tag != WATCH_PANEL_TELEMETRY }?.apply {
                        text = if (stage == "analysis") {
                            "⌚ 센서 원본 저장 완료 · 분석만 실패"
                        } else {
                            "⌚ 센서 원본 검증 실패 · 재전송 대기"
                        }
                        setTextColor(amber)
                    }
                }
                }
            } catch (error: Throwable) {
                Log.e(EVENT_TAG, "wear UI event failed action=${intent.action}", error)
                getSharedPreferences("wear-sync", MODE_PRIVATE).edit()
                    .putString("lastUiError", "${intent.action}:${error.message}")
                    .putLong("lastUiErrorAt", System.currentTimeMillis())
                    .apply()
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this@MainActivity, "워치 데이터 표시 오류가 있었지만 운동 기록은 유지했어", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private lateinit var root: FrameLayout
    private lateinit var updateManager: UpdateManager
    private lateinit var sessionStore: SessionStore
    private var currentTab = RootTab.HOME
    private var currentScreen = Screen.ROOT
    private var selectedRoutine: WorkoutRoutine? = null
    private var activeWorkout: ActiveWorkout? = null
    private var previewWorkout: ActiveWorkout? = null
    private var lastWorkoutResult: WorkoutResultUi? = null
    private var recordMonth: YearMonth = YearMonth.of(2026, 7)
    private var ticker: Runnable? = null
    private var watchUiReady = false
    private var pendingWatchStartPayload: String? = null
    private var calibrationSessionId: String? = null
    private var activeCalibrationExerciseId: String? = null
    private var selectedCalibrationExerciseId = "external_rotation"
    private var calibrationStopRequested = false
    private var calibrationButton: Button? = null
    private var calibrationSaveTimeoutTask: Runnable? = null

    private var returnFromLibrary: (() -> Unit)? = null
    private var returnFromExerciseInfo: (() -> Unit)? = null
    private var returnFromExerciseRecord: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        updateManager = UpdateManager(this, BuildConfig.UPDATE_MANIFEST_URL)
        sessionStore = SessionStore(this)
        lastWorkoutResult = restoreLastWorkoutResult()
        activeWorkout = restoreActiveWorkout()
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        setContentView(root)
        startService(Intent(this, PhoneWearListenerService::class.java))
        startService(Intent(this, PhoneWorkoutService::class.java))
        activeWorkout?.let { workout ->
            lastCompletedLiveKey = workout.lastCompletedSetKey
            showActiveWorkout(forceRebuild = true)
            sendWear(
                WearSync.PATH_COMMAND,
                WearSync.command("QUERY", workout.sessionId, "", workout.setIndex, 0),
            )
        } ?: showHome()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(
            wearReceiver,
            IntentFilter().apply {
                addAction(PhoneWearListenerService.ACTION_PAIR)
                addAction(PhoneWearListenerService.ACTION_WATCH_UI_READY)
                addAction(PhoneWearListenerService.ACTION_STATE)
                addAction(PhoneWearListenerService.ACTION_LIVE)
                addAction(PhoneWearListenerService.ACTION_COMPLETE)
                addAction(PhoneWearListenerService.ACTION_CAPTURE_MANIFEST)
                addAction(PhoneWearListenerService.ACTION_CAPTURE_STATE)
                addAction(PhoneWearListenerService.ACTION_CALIBRATION_RAW_SAVED)
                addAction(PhoneWearListenerService.ACTION_CALIBRATION_RESULT)
                addAction(PhoneWearListenerService.ACTION_CAPTURE)
                addAction(PhoneWearListenerService.ACTION_CAPTURE_ERROR)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        updateWearConnectionStatus()
    }

    override fun onStop() {
        unregisterReceiver(wearReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        calibrationSaveTimeoutTask?.let(handler::removeCallbacks)
        calibrationSaveTimeoutTask = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateManager.resumePendingInstall()?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }
    }

    override fun onBackPressed() {
        when (currentScreen) {
            Screen.ROOT -> {
                if (currentTab == RootTab.HOME) super.onBackPressed() else showHome()
            }
            Screen.ROUTINE, Screen.FREE -> showHome()
            Screen.LIBRARY -> returnFromLibrary?.invoke() ?: showHome()
            Screen.EXERCISE_INFO -> returnFromExerciseInfo?.invoke() ?: showHome()
            Screen.EXERCISE_RECORD -> returnFromExerciseRecord?.invoke() ?: showHome()
            Screen.ACTIVE, Screen.REST -> showHome()
            Screen.MY_MOUNTAIN -> showRecords()
        }
    }

    private fun showHome() {
        stopTicker()
        if (activeWorkout == null) previewWorkout = null
        currentTab = RootTab.HOME
        currentScreen = Screen.ROOT

        val content = vertical()
        content.setPadding(dp(18), dp(16), dp(18), dp(12))
        content.addView(topBar("홈", "☷   ◉   ●"))
        content.addView(space(18))
        content.addView(label("내 루틴", 22f, white, bold = true))
        content.addView(space(12))
        content.addView(row().apply {
            addView(label("최근  ▾", 14f, muted, bold = true))
            addView(space(16))
            addView(label("요약  ▾", 14f, muted, bold = true))
            addView(weightSpace())
            addView(label("☷", 18f, amber, bold = true))
            addView(space(14))
            addView(label("▦", 17f, muted))
        })
        content.addView(space(10))
        routines.forEach { routine ->
            content.addView(routineCard(routine))
            content.addView(space(10))
        }

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(content)
        }

        val running = activeWorkout
        val elapsed = label("00:00", 18f, white, bold = true)
        val actions = if (running == null) {
            row().apply {
                setPadding(dp(20), dp(10), dp(20), dp(12))
                addView(primaryButton("⚡  자유운동", amberDark) { showFreeWorkout() }, weightParams(1f, 64))
                addView(space(12))
                addView(primaryButton("＋  추가", Color.WHITE, amberDark) { showAddSheet() }, weightParams(1f, 64))
            }
        } else {
            row().apply {
                minimumHeight = dp(78)
                setPadding(dp(20), dp(8), dp(20), dp(8))
                background = rounded(Color.rgb(14, 16, 16), 0, amber)
                addView(vertical().apply {
                    addView(label(running.routine.name, 17f, white, bold = true))
                    addView(elapsed)
                    isClickable = true
                    setOnClickListener { showActiveWorkout() }
                }, weightParams(1f, -2))
                addView(label(if (running.paused) "▶" else "Ⅱ", 28f, white, bold = true).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener {
                        toggleWorkoutPause(running)
                        showHome()
                    }
                }, fixedParams(70, 70))
                addView(label("■", 26f, white, bold = true).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { confirmStopWorkout() }
                }, fixedParams(70, 70))
            }
        }

        showScaffold(scroll, actions, RootTab.HOME)
        running?.let { startHomeWorkoutTicker(it, elapsed) }
    }

    private fun routineCard(routine: WorkoutRoutine): View {
        val card = horizontalCard().apply {
            minimumHeight = dp(88)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedRoutine = routine
                showRoutine(
                    routine,
                    active = activeWorkout?.takeIf { it.routine === routine },
                    animateIn = true,
                )
            }
        }
        card.addView(label("⚡", 22f, amber, bold = true), fixedParams(38, -1))
        card.addView(vertical().apply {
            addView(label(routine.name, 17f, white, bold = true))
            addView(space(2))
            addView(label(routine.muscles, 13f, blue, bold = true))
            addView(space(2))
            addView(label("${routine.lastPerformed} · ${routine.exercises.size}종목", 12f, muted))
        }, weightParams(1f, -2))
        card.addView(label("•••", 16f, muted, bold = true).apply {
            setPadding(dp(8), dp(10), 0, dp(10))
            setOnClickListener { showRoutineMenu(routine) }
        })
        return card
    }

    private fun showRoutine(
        routine: WorkoutRoutine,
        reorderMode: Boolean = false,
        active: ActiveWorkout? = null,
        onExit: (() -> Unit)? = null,
        animateIn: Boolean = false,
    ) {
        stopTicker()
        currentScreen = Screen.ROUTINE
        selectedRoutine = routine

        val list = vertical().apply {
            setPadding(dp(20), dp(16), dp(20), dp(110))
            val top = detailTopBar(routine.name)
            (top as ViewGroup).getChildAt(0).setOnClickListener { onExit?.invoke() ?: onBackPressed() }
            addView(top)
            addView(space(18))
            addView(anatomyPlaceholder(285))
            addView(space(10))
            // Populate the entire list as one frame. LayoutTransition used to
            // animate every row while this screen was first being constructed,
            // which made the page appear in broken, staggered fragments.
            val exerciseRows = vertical()
            addView(exerciseRows)
            var draggedExercise: RoutineExercise? = null
            var draggedRow: View? = null
            var dragMoved = false
            routine.exercises.forEachIndexed { index, item ->
                val targetReps = item.sets.sumOf { it.targetReps }
                val itemRow = exerciseRow(item.exercise, "${item.sets.workSetCount()}세트 · 총 ${targetReps}회", onInfo = {
                    showExerciseInfo(item.exercise) {
                        showRoutine(routine, reorderMode, active, onExit)
                    }
                }, onClick = {
                    if (active != null && active.routine === routine) {
                        showWorkoutExercise(active, index)
                    } else {
                        showExercisePreview(routine, index)
                    }
                }, onMenu = {
                    showExerciseMenu(routine, index, active) {
                        showRoutine(routine, reorderMode, active, onExit)
                    }
                }, showHandle = reorderMode)
                itemRow.tag = index
                itemRow.setOnLongClickListener {
                    draggedExercise = item
                    draggedRow = itemRow
                    dragMoved = false
                    itemRow.alpha = 0.28f
                    val data = ClipData.newPlainText("routine-exercise", item.exercise.id)
                    itemRow.startDragAndDrop(data, View.DragShadowBuilder(itemRow), null, 0)
                    true
                }
                itemRow.setOnDragListener { _, event ->
                    when (event.action) {
                        DragEvent.ACTION_DRAG_STARTED ->
                            draggedExercise != null && event.clipDescription?.hasMimeType("text/plain") == true
                        DragEvent.ACTION_DRAG_ENTERED -> {
                            val moving = draggedExercise
                            val movingView = draggedRow
                            val from = routine.exercises.indexOfFirst { it === moving }
                            val to = routine.exercises.indexOfFirst { it === item }
                            if (moving != null && movingView != null && moving !== item && from >= 0 && to >= 0) {
                                moveRoutineExercise(routine, from, to, active, announce = false)
                                exerciseRows.removeView(movingView)
                                exerciseRows.addView(movingView, to)
                                movingView.alpha = 0.28f
                                dragMoved = true
                            }
                            true
                        }
                        DragEvent.ACTION_DRAG_EXITED -> true
                        DragEvent.ACTION_DROP -> true
                        DragEvent.ACTION_DRAG_ENDED -> {
                            draggedRow?.alpha = 1f
                            exerciseRows.children.forEach { it.alpha = 1f }
                            if (dragMoved) {
                                active?.takeIf { it.routine === routine }?.let(::persistActiveWorkout)
                                Toast.makeText(this@MainActivity, "운동 순서가 반영됐어", Toast.LENGTH_SHORT).show()
                            }
                            draggedExercise = null
                            draggedRow = null
                            dragMoved = false
                            true
                        }
                        else -> true
                    }
                }
                exerciseRows.addView(itemRow, matchParams(-2).apply { bottomMargin = dp(8) })
            }
        }

        val actions = row().apply {
            setPadding(dp(20), dp(10), dp(20), dp(18))
            val workoutButton = if (active != null) "⚑  운동 완료" else "▶  시작"
            addView(primaryButton(workoutButton, amberDark) {
                if (active != null) showMyMountainDecision() else showStartGuide(routine)
            }, weightParams(1f, 66))
            addView(space(12))
            addView(primaryButton("＋ 운동 추가", Color.WHITE, amberDark) {
                openLibrary(
                    onSelect = {
                        routine.exercises += RoutineExercise(
                            exercise = it,
                            sets = mutableListOf(PlannedSet(null, 10)),
                        )
                    },
                    onReturn = { showRoutine(routine) },
                )
            }, weightParams(1f, 66))
        }
        showDetail(ScrollView(this).apply { addView(list) }, actions, animateFromRight = animateIn)
    }

    private fun showFreeWorkout() {
        stopTicker()
        currentScreen = Screen.FREE
        val list = vertical().apply {
            setPadding(dp(20), dp(16), dp(20), dp(116))
            addView(detailTopBar("7월 27일, 밤 운동"))
            addView(space(18))
            addView(anatomyPlaceholder(if (freeExercises.isEmpty()) 410 else 250))
            if (freeExercises.isNotEmpty()) {
                addView(space(18))
                freeExercises.forEachIndexed { index, item ->
                    addView(exerciseRow(item.exercise, "${item.sets.workSetCount()}세트 · 총 ${item.sets.sumOf { it.targetReps }}회", onInfo = {
                        showExerciseInfo(item.exercise) { showFreeWorkout() }
                    }, onClick = {
                        val freeRoutine = WorkoutRoutine("free", "자유운동", "선택 운동", "오늘", freeExercises)
                        showExerciseRecord(freeRoutine, index, null) { showFreeWorkout() }
                    }, onMenu = {
                        val freeRoutine = WorkoutRoutine("free", "자유운동", "선택 운동", "오늘", freeExercises)
                        showExerciseMenu(freeRoutine, index, null) { showFreeWorkout() }
                    }))
                    addView(space(8))
                }
            }
        }
        val actions = row().apply {
            setPadding(dp(20), dp(10), dp(20), dp(18))
            if (freeExercises.isNotEmpty()) {
                addView(primaryButton("▶  시작", amberDark) {
                    showStartGuide(
                        WorkoutRoutine("free", "자유운동", "선택 운동", "오늘", freeExercises),
                    )
                }, weightParams(1f, 66))
                addView(space(12))
            }
            addView(primaryButton("＋ 운동 추가", if (freeExercises.isEmpty()) amberDark else Color.WHITE, if (freeExercises.isEmpty()) white else amberDark) {
                openLibrary(
                    onSelect = {
                        if (freeExercises.none { row -> row.exercise.id == it.id }) {
                            freeExercises += RoutineExercise(it, mutableListOf(PlannedSet(null, 10)))
                        }
                    },
                    onReturn = { showFreeWorkout() },
                )
            }, weightParams(1f, 66))
        }
        showDetail(ScrollView(this).apply { addView(list) }, actions)
    }

    private fun openLibrary(onSelect: (ExerciseDefinition) -> Unit, onReturn: () -> Unit) {
        stopTicker()
        currentScreen = Screen.LIBRARY
        returnFromLibrary = onReturn
        var mode = "전체"
        var body = "전체"
        var equipment = "전체"
        var query = ""

        val page = vertical().apply { setPadding(dp(18), dp(14), dp(18), dp(20)) }
        val search = EditText(this).apply {
            hint = "운동 검색"
            setHintTextColor(muted)
            setTextColor(white)
            textSize = 17f
            setSingleLine()
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(surfaceRaised, 14)
        }
        page.addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("‹", 48f, white).apply {
                gravity = Gravity.CENTER
                setOnClickListener { onReturn() }
            }, fixedParams(54, 58))
            addView(search, weightParams(1f, 58))
            addView(space(12))
            addView(label("⊞", 34f, white).apply {
                gravity = Gravity.CENTER
                setOnClickListener { showCustomExerciseDialog(onSelect, onReturn) }
            }, fixedParams(48, 58))
        })
        page.addView(space(18))

        val results = vertical()
        fun refresh() {
            results.removeAllViews()
            val frequent = setOf(
                "pull_up", "lat_pull_down", "flat_bench", "incline_bench",
                "dumbbell_lateral_raise", "leg_press", "cable_pushdown",
            )
            val filtered = WorkoutData.catalog
                .filter { item ->
                    (query.isBlank() || item.name.contains(query, ignoreCase = true)) &&
                        (body == "전체" || item.bodyCategory == body) &&
                        (equipment == "전체" || item.equipment == equipment) &&
                        when (mode) {
                            "좋아하는" -> item.favorite
                            "자주하는" -> item.id in frequent
                            "커스텀" -> item.id.startsWith("custom_")
                            else -> true
                        }
                }
                .sortedWith(compareByDescending<ExerciseDefinition> { it.favorite }.thenBy { it.name })

            results.addView(label("${filtered.size}개 운동", 13f, muted).apply {
                setPadding(dp(6), dp(8), 0, dp(12))
            })
            filtered.forEach { exercise ->
                results.addView(libraryExerciseRow(exercise) {
                    onSelect(exercise)
                    Toast.makeText(this, "${exercise.name} 추가됨", Toast.LENGTH_SHORT).show()
                })
                results.addView(space(4))
            }
        }

        fun chipStrip(values: List<String>, selected: () -> String, onSelected: (String) -> Unit): View {
            val strip = row()
            fun rebuild() {
                strip.removeAllViews()
                values.forEach { value ->
                    strip.addView(chip(value, selected() == value) {
                        onSelected(value)
                        rebuild()
                        refresh()
                    })
                    strip.addView(space(8))
                }
            }
            rebuild()
            return HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(strip)
            }
        }

        page.addView(chipStrip(listOf("전체", "좋아하는", "자주하는", "최근", "커스텀"), { mode }) { mode = it })
        page.addView(space(10))
        page.addView(chipStrip(listOf("전체", "가슴", "등", "어깨", "삼두", "이두", "하체"), { body }) { body = it })
        page.addView(space(10))
        page.addView(chipStrip(listOf("전체", "맨몸", "덤벨", "바벨", "머신", "케이블"), { equipment }) { equipment = it })
        page.addView(space(16))
        page.addView(label("＋  커스텀 운동 추가", 17f, amber, bold = true).apply {
            setPadding(dp(14), dp(16), dp(14), dp(16))
            setOnClickListener { showCustomExerciseDialog(onSelect, onReturn) }
        })
        page.addView(results)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                refresh()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refresh()
        showStandalone(ScrollView(this).apply { addView(page) })
    }

    private fun persistLastWorkoutResult(result: WorkoutResultUi) {
        val encode = { value: String -> Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) }
        val analyses = result.analyses.values.joinToString(";") { analysis ->
            listOf(
                analysis.exerciseId,
                analysis.exerciseName,
                analysis.setIndex.toString(),
                analysis.watchReps.toString(),
                analysis.phoneReps.toString(),
                analysis.confirmedReps.toString(),
                analysis.confidence.toString(),
                analysis.trackingMode,
                analysis.trackingProfileId,
                analysis.trackingStatus,
                analysis.capabilityTier,
                analysis.repConfidence?.toString() ?: "",
                analysis.wristMotionSpeed?.toString() ?: "",
                analysis.wristMotionRange?.toString() ?: "",
                analysis.tempoConsistency?.toString() ?: "",
                analysis.signalQuality?.toString() ?: "",
                analysis.averageSpeed?.toString() ?: "",
                analysis.relativeRom?.toString() ?: "",
            ).joinToString(",", transform = encode)
        }
        getSharedPreferences("workout_results", MODE_PRIVATE).edit()
            .putString("last", listOf(encode(result.sessionId), encode(result.title), result.durationMinutes, result.completedSets, analyses).joinToString("#"))
            .apply()
    }

    private fun restoreLastWorkoutResult(): WorkoutResultUi? {
        val raw = getSharedPreferences("workout_results", MODE_PRIVATE).getString("last", null) ?: return null
        return runCatching {
            val fields = raw.split("#", limit = 5)
            require(fields.size == 5)
            val decode = { value: String -> String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8) }
            val analyses = mutableMapOf<String, SetAnalysisUi>()
            fields[4].takeIf { it.isNotBlank() }?.split(';')?.forEach { encoded ->
                val values = encoded.split(',', limit = 40)
                if (values.size == 7 || values.size == 15 || values.size == 18 || values.size == 24 || values.size >= 30) {
                    val currentFormat = values.size == 18
                    val legacyExpanded = values.size >= 30
                    val tempoIndex = when {
                        currentFormat -> 14
                        legacyExpanded -> 21
                        else -> 20
                    }
                    val signalIndex = when {
                        currentFormat -> 15
                        legacyExpanded -> 22
                        else -> 21
                    }
                    val speedIndex = when {
                        currentFormat -> 16
                        legacyExpanded -> 25
                        else -> 22
                    }
                    val rangeIndex = when {
                        currentFormat -> 17
                        legacyExpanded -> 26
                        else -> 23
                    }
                    val analysis = SetAnalysisUi(
                        exerciseId = decode(values[0]),
                        exerciseName = decode(values[1]),
                        setIndex = decode(values[2]).toInt(),
                        watchReps = decode(values[3]).toInt(),
                        phoneReps = decode(values[4]).toInt(),
                        confirmedReps = decode(values[5]).toInt(),
                        confidence = decode(values[6]).toDouble(),
                        trackingMode = values.getOrNull(7)?.let(decode).orEmpty().ifBlank { "WRIST_IMU_CAUTION" },
                        trackingProfileId = values.getOrNull(8)?.let(decode).orEmpty().ifBlank { "generic-wrist" },
                        trackingStatus = values.getOrNull(9)?.let(decode).orEmpty(),
                        capabilityTier = values.getOrNull(10)?.let(decode).orEmpty().ifBlank { "C_PERSONALIZED_AUTO_COUNT_BETA" },
                        repConfidence = values.getOrNull(11)?.let(decode)?.toDoubleOrNull(),
                        wristMotionSpeed = values.getOrNull(12)?.let(decode)?.toDoubleOrNull(),
                        wristMotionRange = values.getOrNull(13)?.let(decode)?.toDoubleOrNull(),
                        tempoConsistency = values.getOrNull(tempoIndex)?.let(decode)?.toIntOrNull(),
                        signalQuality = values.getOrNull(signalIndex)?.let(decode)?.toDoubleOrNull(),
                        averageSpeed = values.getOrNull(speedIndex)?.let(decode)?.toDoubleOrNull(),
                        relativeRom = values.getOrNull(rangeIndex)?.let(decode)?.toDoubleOrNull(),
                    )
                    analyses["${analysis.exerciseId}:${analysis.setIndex}"] = analysis
                }
            }
            WorkoutResultUi(
                sessionId = decode(fields[0]),
                title = decode(fields[1]),
                durationMinutes = fields[2].toInt(),
                completedSets = fields[3].toInt(),
                analyses = analyses,
            )
        }.getOrNull()
    }

    private fun showRecords() {
        stopTicker()
        currentTab = RootTab.RECORDS
        currentScreen = Screen.ROOT
        val page = vertical().apply { setPadding(dp(20), dp(20), dp(20), dp(24)) }
        page.addView(topBar("운동 기록", "＋"))
        page.addView(space(30))
        lastWorkoutResult?.let { result ->
            page.addView(primaryButton("최근 완료 결과 · ${result.title}", surfaceRaised) { showWorkoutResult(result) }, matchParams(58))
            page.addView(space(18))
        }
        page.addView(calendarCard(recordMonth))
        page.addView(space(26))
        page.addView(label("최근 운동", 25f, white, bold = true))
        page.addView(space(14))
        WorkoutData.records.take(4).forEach { record ->
            page.addView(recordCard(record))
            page.addView(space(10))
        }
        showScaffold(ScrollView(this).apply { addView(page) }, null, RootTab.RECORDS)
    }

    private fun showWorkoutResult(result: WorkoutResultUi?) {
        if (result == null) return showRecords()
        stopTicker()
        currentTab = RootTab.RECORDS
        currentScreen = Screen.ROOT
        val page = vertical().apply { setPadding(dp(20), dp(20), dp(20), dp(24)) }
        page.addView(detailTopBar("운동 완료 결과"))
        page.addView(space(24))
        page.addView(label(result.title, 28f, white, bold = true))
        page.addView(space(8))
        page.addView(label("${result.durationMinutes}분 · ${result.completedSets}세트 · 세션 ${result.sessionId.takeLast(8)}", 14f, muted))
        page.addView(space(22))
        page.addView(label("세트별 확정 분석", 20f, white, bold = true))
        page.addView(space(10))
        if (result.analyses.isEmpty()) {
            page.addView(sectionCard("분석 대기", listOf("센서 원본" to "워치에서 수신 대기", "확정 반복수" to "capture 수신 후 표시")))
        } else {
            result.analyses.values.sortedWith(compareBy<SetAnalysisUi> { it.exerciseName }.thenBy { it.setIndex }).forEach { analysis ->
                val confidenceText = "%.0f%%".format(Locale.US, analysis.confidence * 100.0)
                val metricRows = mutableListOf(
                    "tracking" to "${analysis.capabilityTier} · ${analysis.trackingStatus.ifBlank { analysis.trackingMode }} · ${analysis.trackingProfileId}",
                    "워치 provisional" to "${analysis.watchReps}회",
                    "폰 재분석" to if (analysis.trackingMode == "MANUAL_REQUIRED") "자동 산출 안 함" else "${analysis.phoneReps}회",
                    "확정 반복수" to if (analysis.trackingMode == "MANUAL_REQUIRED") "수동 입력 필요" else "${analysis.confirmedReps}회",
                    "분석 신뢰도" to confidenceText,
                )
                (analysis.wristMotionSpeed ?: analysis.averageSpeed)?.let { metricRows += "손목 관측 속도" to "%.2f rad/s".format(Locale.US, it) }
                (analysis.wristMotionRange ?: analysis.relativeRom)?.let { metricRows += "손목 관측 범위" to "%.0f%%".format(Locale.US, it * 100.0) }
                analysis.tempoConsistency?.let { metricRows += "템포 일관성" to "$it/100" }
                analysis.signalQuality?.let { metricRows += "신호 품질" to "%.0f%%".format(Locale.US, it * 100.0) }
                page.addView(sectionCard("${analysis.exerciseName} · 세트 ${analysis.setIndex + 1}", metricRows))
                page.addView(space(10))
            }
        }
        page.addView(space(18))
        page.addView(label("확정 반복수는 워치 표시값이 아니라 폰의 원본 capture 재분석 결과를 우선해.", 13f, muted))
        page.addView(space(18))
        page.addView(primaryButton("운동 기록으로 돌아가기", surfaceRaised) { showRecords() }, matchParams(58))
        showStandalone(ScrollView(this).apply { addView(page) })
    }

    private fun showStats() {
        stopTicker()
        currentTab = RootTab.STATS
        currentScreen = Screen.ROOT
        val page = vertical().apply { setPadding(dp(20), dp(20), dp(20), dp(24)) }
        page.addView(topBar("통계", "7월  ▾"))
        page.addView(space(30))
        page.addView(label("최근 4분할", 27f, white, bold = true))
        page.addView(space(16))
        page.addView(metricGrid(listOf("4회" to "운동", "270분" to "총 시간", "96세트" to "총 세트", "40,809kg" to "총 볼륨")))
        page.addView(space(22))
        page.addView(sectionCard("부위별 흐름", listOf(
            "어깨" to "특화 · 주 2회",
            "등" to "풀업 총 26회",
            "가슴" to "프레스 8세트",
            "팔·하체" to "29세트",
        )))
        page.addView(space(18))
        page.addView(sectionCard("다음 목표", listOf(
            "풀업" to "총 28회",
            "벤치프레스" to "60kg 총 반복 +1~2",
            "덤벨 레터럴 레이즈" to "10kg 16회",
        )))
        showScaffold(ScrollView(this).apply { addView(page) }, null, RootTab.STATS)
    }

    private fun startOrStopCalibration() {
        val session = calibrationSessionId
        if (session != null) {
            if (calibrationStopRequested) return
            calibrationStopRequested = true
            calibrationButton?.apply {
                isEnabled = false
                text = "보정 원본 저장 중…"
            }
            sendWear(WearSync.PATH_COMMAND, WearSync.command("STOP", session, "", 0, 0))
            Toast.makeText(this, "워치 센서 원본을 저장하는 중이야", Toast.LENGTH_LONG).show()
            scheduleCalibrationSaveTimeout(session)
            return
        }

        if (
            Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK", ignoreCase = true) ||
            Build.PRODUCT.startsWith("sdk", ignoreCase = true)
        ) {
            beginCalibrationSession()
            return
        }
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Toast.makeText(this, "연결된 Watch8을 찾지 못했어", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                beginCalibrationSession()
            }
            .addOnFailureListener {
                Toast.makeText(this, "워치 연결 상태를 확인하지 못했어", Toast.LENGTH_LONG).show()
            }
    }

    private fun beginCalibrationSession() {
        val exercise = WorkoutData.catalog.firstOrNull { it.id == selectedCalibrationExerciseId } ?: return
        val tracking = ExerciseTrackingProfiles.resolve(exercise.id, exercise.name)
        if (tracking.mode == TrackingMode.MANUAL_REQUIRED) {
            Toast.makeText(this, "이 운동은 손목 센서로 직접 관측할 수 없어 수동 기록만 지원해", Toast.LENGTH_LONG).show()
            return
        }
        val newSession = "calibration-${exercise.id}-${System.currentTimeMillis()}"
        calibrationSessionId = newSession
        cancelCalibrationSaveTimeout()
        activeCalibrationExerciseId = exercise.id
        calibrationStopRequested = false
        calibrationButton?.apply {
            text = "보정 종료·저장"
            setTextColor(white)
        }
        sendWear(
            WearSync.PATH_COMMAND,
            WearSync.richCommand(
                "START", newSession, exercise.id, exercise.name,
                0, 5, null, 0,
            ),
        )
        Toast.makeText(this, "시작 자세 2초 유지 → 진동 후 평소 범위·속도로 5회 수행해", Toast.LENGTH_LONG).show()
    }

    private fun scheduleCalibrationSaveTimeout(sessionId: String) {
        cancelCalibrationSaveTimeout()
        val timeout = Runnable {
            if (calibrationSessionId != sessionId || !calibrationStopRequested) return@Runnable
            calibrationSessionId = null
            activeCalibrationExerciseId = null
            calibrationStopRequested = false
            calibrationButton?.apply {
                isEnabled = true
                text = "보정 전송 지연 · 다시 시작"
                setTextColor(amber)
            }
            Toast.makeText(this, "20초 동안 워치 원본을 받지 못했어 · 연결 후 다시 보정해줘", Toast.LENGTH_LONG).show()
        }
        calibrationSaveTimeoutTask = timeout
        handler.postDelayed(timeout, CALIBRATION_SAVE_TIMEOUT_MS)
    }

    private fun cancelCalibrationSaveTimeout() {
        calibrationSaveTimeoutTask?.let(handler::removeCallbacks)
        calibrationSaveTimeoutTask = null
    }

    private fun showCalibration() {
        stopTicker()
        currentTab = RootTab.CALIBRATION
        currentScreen = Screen.ROOT
        val selected = WorkoutData.catalog.firstOrNull { it.id == selectedCalibrationExerciseId }
            ?: WorkoutData.catalog.first()
        val selectedProfile = ExerciseTrackingProfiles.resolve(selected.id, selected.name)
        val manualRequired = selectedProfile.mode == TrackingMode.MANUAL_REQUIRED
        val calibrationPrefs = getSharedPreferences("wear-sync", MODE_PRIVATE)
        fun personalProfile(exerciseId: String) = calibrationPrefs
            .getString("personalProfile:$exerciseId", null)
            ?.let { runCatching { PersonalCalibrationProfileCodec.decode(it) }.getOrNull() }
        fun calibrationLabel(exerciseId: String): String {
            val profile = personalProfile(exerciseId)
            if (profile != null) return "개인 프로필 보정 완료 · 유효 ${profile.validRepCount}회"
            return when (calibrationPrefs.getString("calibration:$exerciseId:status", null)) {
                "CALIBRATION_FAILED" -> "보정 실패 · 다시 수행 필요"
                "RAW_SAVED" -> "원본 저장 완료 · 개인 프로필 적용 중"
                else -> "자동 인식 후보 · 프로필 미보정"
            }
        }
        val instructions = if (selected.id == "external_rotation") {
            "1. 왼손 워치를 손목뼈 위에 고정\n" +
                "2. 팔꿈치를 몸 옆에 붙이고 90도 유지\n" +
                "3. 손바닥이 앞쪽인 기준 자세로 2초\n" +
                "4. 진동 후 평소 범위·속도로 바깥 회전·복귀 5회\n" +
                "5. 5회째 즉시 폰 원본 저장과 개인 프로필 적용"
        } else {
            "워치를 평소 위치에 고정하고 기준 자세 2초\n" +
                "→ 진동 후 ${selectedProfile.label}\n" +
                "→ 평소 범위·속도로 왕복 5회\n" +
                "→ 즉시 폰 저장과 개인 프로필 적용"
        }
        val page = vertical().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            addView(topBar("개인 보정", ""))
            addView(space(24))
            addView(label(selected.name, 22f, white, bold = true))
            addView(space(7))
            addView(label(
                if (manualRequired) "손목 센서 직접 관측 불가 · 수동 기록" else calibrationLabel(selected.id),
                14f,
                if (manualRequired) amber else blue,
                bold = true,
            ))
            addView(space(12))
            addView(label(instructions, 15f, muted))
            addView(space(20))
            calibrationButton = primaryButton(
                if (manualRequired) "이 운동은 수동 기록만 지원"
                else if (calibrationSessionId == null) "워치 연결 후 5회 보정 시작"
                else if (calibrationStopRequested) "보정 원본 저장 중…"
                else "보정 종료·저장",
                if (calibrationSessionId == null) surfaceRaised else amberDark,
                if (calibrationSessionId == null || manualRequired) muted else white,
            ) {
                startOrStopCalibration()
            }.apply {
                isEnabled = !calibrationStopRequested && !manualRequired
            }
            addView(calibrationButton, matchParams(58))
            personalProfile(selected.id)?.let { profile ->
                val applied = PersonalCalibrationBuilder.personalizedTrackingProfile(profile, selectedProfile)
                val axis = listOf("X", "Y", "Z").getOrElse(profile.dominantAxis) { "?" }
                val created = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA)
                    .format(Date(profile.createdAtMs))
                val naturalRange = Math.toDegrees(profile.minimumRom / 2.0).coerceAtLeast(0.0)
                addView(space(14))
                addView(sectionCard("저장된 개인 프로필 · 실 운동 적용 중", listOf(
                    "저장 시각" to created,
                    "유효 반복" to "${profile.validRepCount}회",
                    "학습 회전 범위" to "편도 약 ${"%.0f".format(naturalRange)}° 이상",
                    "학습 반복 시간" to "${"%.2f".format(profile.medianRepDurationMs / 1_000.0)}초",
                    "주 회전축" to "$axis 축",
                    "실 운동 허용 시간" to "${"%.2f".format(applied.minimumRepMs / 1_000.0)}–${"%.2f".format(applied.maximumRepMs / 1_000.0)}초",
                    "적용 위치" to "워치 실시간 + 폰 재분석",
                    "가중치" to "개인 80% + 일반 20%",
                )))
                addView(space(8))
                addView(label(
                    "시작 자세 2초는 센서 기준축과 흔들림 기준을 잡는 단계야. 손목 센서만으로 팔꿈치·몸 전체 자세까지 확정하진 않아.",
                    12f,
                    muted,
                ))
                addView(space(12))
                addView(primaryButton("보정된 기록 삭제", Color.rgb(72, 38, 32), amber) {
                    confirmDeletePersonalProfile(selected.id, selected.name)
                }, matchParams(54))
            }
            addView(space(28))
            addView(label("보정할 운동 선택", 21f, white, bold = true).apply {
                gravity = Gravity.START
            })
            addView(space(10))
            WorkoutData.catalog.forEach { exercise ->
                val profile = ExerciseTrackingProfiles.resolve(exercise.id, exercise.name)
                val isManual = profile.mode == TrackingMode.MANUAL_REQUIRED
                addView(exerciseRow(
                    exercise,
                    if (isManual) {
                        "수동 기록만"
                    } else {
                        val status = calibrationLabel(exercise.id)
                        if (exercise.id == selectedCalibrationExerciseId) "선택됨 · $status" else status
                    },
                    onClick = {
                        if (calibrationSessionId == null) {
                            selectedCalibrationExerciseId = exercise.id
                            showCalibration()
                        }
                    },
                ).apply {
                    if (exercise.id == selectedCalibrationExerciseId) {
                        background = rounded(surfaceRaised, 16)
                        setPadding(dp(8), dp(7), dp(8), dp(7))
                    }
                    alpha = if (calibrationSessionId == null) 1f else 0.55f
                })
                addView(space(4))
            }
        }
        showScaffold(ScrollView(this).apply { addView(page) }, null, RootTab.CALIBRATION)
    }

    private fun confirmDeletePersonalProfile(exerciseId: String, exerciseName: String) {
        AlertDialog.Builder(this)
            .setTitle("보정된 기록 삭제")
            .setMessage(
                "$exerciseName 개인 보정 프로필을 폰과 워치에서 삭제할까?\n" +
                    "삭제 후에는 이 운동이 일반 기준 프로필로 측정돼.",
            )
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                val prefs = getSharedPreferences("wear-sync", MODE_PRIVATE)
                prefs.edit()
                    .remove("personalProfile:$exerciseId")
                    .remove("calibration:$exerciseId:status")
                    .remove("calibration:$exerciseId:rawSavedAt")
                    .remove("calibration:$exerciseId:completedAt")
                    .remove("calibration:$exerciseId:error")
                    .apply()
                sendWear(WearSync.PATH_PERSONAL_PROFILE_DELETE, exerciseId)
                Toast.makeText(this, "$exerciseName 보정 기록을 삭제했어", Toast.LENGTH_LONG).show()
                showCalibration()
            }
            .show()
    }

    private fun showMenu() {
        stopTicker()
        currentTab = RootTab.MENU
        currentScreen = Screen.ROOT
        val page = vertical().apply { setPadding(dp(20), dp(20), dp(20), dp(24)) }
        page.addView(topBar("메뉴", ""))
        page.addView(space(28))
        page.addView(sectionCard("기기", listOf(
            "Galaxy Watch8" to "연결 대기",
            "착용 손" to "왼손",
            "센서 기록" to "세트 중에만",
        )))
        page.addView(space(16))
        page.addView(sectionCard("앱", listOf(
            "현재 버전" to BuildConfig.VERSION_NAME,
            "데이터 저장" to "이 기기에만",
        )))
        page.addView(space(16))
        val status = label("업데이트를 확인할 수 있어", 14f, muted)
        lateinit var update: Button
        update = primaryButton("업데이트 확인", amberDark) {
            status.text = "최신 버전 확인 중…"
            updateManager.checkAndDownload { state ->
                runOnUiThread {
                    status.text = state.message
                    if (state.readyToInstall) {
                        update.text = "다운로드 완료 · 설치"
                        update.setOnClickListener {
                            updateManager.requestInstall()?.let { message -> status.text = message }
                        }
                    }
                }
            }
        }
        page.addView(status)
        page.addView(space(10))
        page.addView(update, matchParams(58))
        showScaffold(ScrollView(this).apply { addView(page) }, null, RootTab.MENU)
    }

    private fun showStartGuide(
        routine: WorkoutRoutine,
        startExerciseIndex: Int = 0,
        startSetIndex: Int = 0,
        onStarted: ((ActiveWorkout) -> Unit)? = null,
    ) {
        val dialog = bottomDialog()
        val content = vertical().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(16), dp(24), dp(28))
            addView(label("▶", 38f, blue, bold = true))
            addView(space(8))
            addView(label("운동 시작", 28f, white, bold = true))
            addView(space(14))
            addView(label("운동을 시작하시겠습니까?\n\n워치가 연결되면 현재 세트·횟수·심박수가 동기화됩니다.", 16f, white).apply {
                gravity = Gravity.CENTER
            })
            addView(space(24))
            addView(row().apply {
                addView(label("취소", 17f, amber, bold = true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { dialog.dismiss() }
                }, weightParams(1f, 58))
                addView(label("확인", 17f, amber, bold = true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        dialog.dismiss()
                        previewWorkout = null
                        val safeExerciseIndex = startExerciseIndex.coerceIn(
                            0,
                            routine.exercises.lastIndex.coerceAtLeast(0),
                        )
                        val safeSetIndex = startSetIndex.coerceIn(
                            0,
                            routine.exercises.getOrNull(safeExerciseIndex)
                                ?.sets?.lastIndex?.coerceAtLeast(0) ?: 0,
                        )
                        activeWorkout = ActiveWorkout(
                            routine = routine,
                            exerciseIndex = safeExerciseIndex,
                            setIndex = safeSetIndex,
                        )
                        activeWorkout?.let { workout ->
                            if (!persistActiveWorkout(workout)) return@setOnClickListener
                            val first = routine.exercises.getOrNull(safeExerciseIndex)
                            if (first != null) {
                                val firstSet = first.sets.getOrNull(safeSetIndex)
                                val startPayload = WearSync.richCommand(
                                    "START", workout.sessionId, first.exercise.id, first.exercise.name,
                                    safeSetIndex, firstSet?.targetReps ?: 10, firstSet?.weightKg, first.restSeconds,
                                )
                                if (watchUiReady) {
                                    sendWear(WearSync.PATH_COMMAND, startPayload)
                                    activeLiveStatus?.text = "워치 앱 준비 완료 · 운동 시작"
                                } else {
                                    pendingWatchStartPayload = startPayload
                                    activeLiveStatus?.text = "워치 연동 중… 워치 앱을 열어줘"
                                    wearLiveStatus?.apply {
                                        text = "⌚ 워치 연동 중… 워치 앱을 열어줘"
                                        setTextColor(amber)
                                    }
                                }
                            }
                            if (onStarted != null) {
                                onStarted(workout)
                            } else {
                                showActiveWorkout()
                            }
                        }
                    }
                }, weightParams(1f, 58))
            })
        }
        dialog.setContentView(content)
        showBottomDialog(dialog)
    }

    private fun adjustRestDuration(
        workout: ActiveWorkout,
        routineExercise: RoutineExercise,
        deltaSeconds: Int,
    ) {
        routineExercise.restSeconds = (routineExercise.restSeconds + deltaSeconds).coerceAtLeast(0)
        if (activeRestEndAtMs > 0L) {
            val now = System.currentTimeMillis()
            activeRestEndAtMs = (activeRestEndAtMs + deltaSeconds * 1_000L).coerceAtLeast(now)
            workout.phase = WorkoutPhase.RESTING
            if (!persistActiveWorkout(workout)) return
            sendWear(
                WearSync.PATH_COMMAND,
                restCommand(workout, routineExercise.restSeconds, activeRestEndAtMs),
            )
            refreshActiveWorkoutUi(workout)
            updateRestUi(workout)
        } else {
            showActiveWorkout()
        }
    }

    private fun resyncActiveWorkoutToWatch(workout: ActiveWorkout) {
        val exercise = workout.routine.exercises.getOrNull(workout.exerciseIndex) ?: return
        val payload = if (workout.phase == WorkoutPhase.RESTING && activeRestEndAtMs > System.currentTimeMillis()) {
            restCommand(workout, exercise.restSeconds, activeRestEndAtMs)
        } else {
            val set = exercise.sets.getOrNull(workout.setIndex)
            WearSync.richCommand(
                "SET",
                workout.sessionId,
                exercise.exercise.id,
                exercise.exercise.name,
                workout.setIndex,
                set?.targetReps ?: 10,
                set?.weightKg,
                exercise.restSeconds,
            )
        }
        sendWear(WearSync.PATH_COMMAND, payload)
        activeLiveStatus?.apply {
            text = "현재 운동 상태를 워치와 다시 맞추는 중"
            tag = WATCH_PANEL_CONNECTION
        }
    }

    private fun showActiveWorkout(preview: ActiveWorkout? = null, forceRebuild: Boolean = false) {
        val existingWorkout = activeWorkout ?: previewWorkout
        if (!forceRebuild && preview == null && currentScreen == Screen.ACTIVE &&
            activeWorkoutUi?.workout === existingWorkout &&
            activeWorkoutUi?.exerciseIndex == existingWorkout?.exerciseIndex
        ) {
            refreshActiveWorkoutUi(existingWorkout!!)
            return
        }
        stopTicker()
        activeWorkoutUi = null
        currentScreen = Screen.ACTIVE
        if (preview != null) previewWorkout = preview
        val workout = activeWorkout ?: previewWorkout ?: return showHome()
        val isPreview = activeWorkout == null
        if (workout.exerciseIndex >= workout.routine.exercises.size) {
            showMyMountainDecision()
            return
        }
        val routineExercise = workout.routine.exercises[workout.exerciseIndex]
        workout.selectedEditSetIndex = workout.selectedEditSetIndex.coerceIn(0, routineExercise.sets.lastIndex)
        val elapsed = label("00:00", 17f, white, bold = true)
        val completedCount = routineExercise.sets.completedWorkSetCount()
        val totalTarget = routineExercise.sets.sumOf { it.targetReps }
        val performed = workout.performedRepsByExercise[routineExercise.exercise.id] ?: 0
        lateinit var setRows: LinearLayout
        lateinit var targetStatus: TextView
        lateinit var performedStatus: TextView
        lateinit var summary: TextView
        lateinit var restDuration: TextView
        lateinit var restProgress: ProgressBar

        val page = vertical().apply {
            setPadding(dp(16), dp(12), dp(16), dp(20))
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label("‹", 38f, white).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        if (isPreview) {
                            previewWorkout = null
                            showRoutine(workout.routine)
                        } else showHome()
                    }
                }, fixedParams(42, 44))
                addView(vertical().apply {
                    addView(label(workout.routine.name, 16f, white, bold = true))
                    addView(label("운동 시간", 11f, muted))
                }, weightParams(1f, -2))
                addView(elapsed)
                addView(label("•••", 16f, muted, bold = true).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { showWorkoutMenu(workout) }
                }, fixedParams(42, 44))
            })
            addView(space(10))
            val hasCachedWearState = lastWearLiveSessionId == workout.sessionId &&
                lastWearLiveTitle.isNotBlank() && lastWearLiveTelemetry.isNotBlank()
            val liveTitle = label(
                if (hasCachedWearState) lastWearLiveTitle else "⌚ Watch8 연결 확인 중",
                12f,
                green,
                bold = true,
            ).apply {
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                tag = if (hasCachedWearState) WATCH_PANEL_TELEMETRY else WATCH_PANEL_CONNECTION
            }
            val liveTelemetry = label(
                if (hasCachedWearState) lastWearLiveTelemetry else "워치 운동 상태 수신 대기 중",
                12f,
                green,
                bold = true,
            ).apply {
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                tag = if (hasCachedWearState) WATCH_PANEL_TELEMETRY else WATCH_PANEL_CONNECTION
            }
            val livePanel = vertical().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(20, 24, 28))
                    setStroke(1, Color.rgb(44, 52, 60))
                    cornerRadius = 12f
                }
                addView(liveTitle, matchParams(23))
                addView(liveTelemetry, matchParams(23))
            }
            activeLiveStatus = liveTelemetry
            wearLiveStatus = liveTitle
            addView(livePanel, matchParams(60))
            updateWearConnectionStatus()
            addView(space(12))
            addView(row().apply {
                addView(label("${workout.exerciseIndex + 1} / ${workout.routine.exercises.size}  운동", 12f, blue, bold = true), weightParams(1f, -2))
                addView(label("●  ●  ●  ●  ●", 10f, muted))
            })
            addView(space(6))
            addView(exerciseAnimationView(routineExercise.exercise, 190))
            addView(space(6))
            addView(row().apply {
                addView(label(routineExercise.exercise.name, 19f, white, bold = true), weightParams(1f, -2))
                addView(label("ⓘ", 18f, muted).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        showExerciseInfo(routineExercise.exercise) { showActiveWorkout() }
                    }
                }, fixedParams(38, 42))
            })
            addView(label(routineExercise.exercise.muscles, 13f, muted))
            addView(space(4))
            targetStatus = label("목표 ${totalTarget}회", 14f, blue, bold = true)
            performedStatus = label("수행 ${performed}회", 14f, if (performed > 0) amber else muted, bold = true)
            addView(row().apply {
                addView(targetStatus, weightParams(1f, -2))
                addView(performedStatus)
            })
            addView(space(10))
            addView(row().apply {
                addView(label("›  기록", 14f, white, bold = true), weightParams(1f, 38))
                addView(label("?", 14f, white).apply {
                    gravity = Gravity.CENTER
                    background = rounded(muted, 99)
                }, fixedParams(28, 28))
            })
            addView(space(4))
            addView(activeSetGridRow(routineExercise.exercise.equipment != "맨몸", 32).apply {
                val weighted = routineExercise.exercise.equipment != "맨몸"
                addActiveSetCell(this, label("세트", 12f, muted, bold = true).apply { gravity = Gravity.CENTER }, 0)
                if (weighted) addActiveSetCell(this, label(if (workout.usePounds) "LB" else "KG", 12f, muted, bold = true).apply { gravity = Gravity.CENTER }, 1)
                addActiveSetCell(this, label("회", 12f, muted, bold = true).apply { gravity = Gravity.CENTER }, if (weighted) 2 else 1)
                addActiveSetCell(this, label("완료", 12f, muted, bold = true).apply { gravity = Gravity.CENTER }, if (weighted) 3 else 2)
                addActiveSetCell(this, Space(this@MainActivity), if (weighted) 4 else 3)
            })
            setRows = vertical()
            routineExercise.sets.forEachIndexed { setIndex, plannedSet ->
                setRows.addView(workoutSetRow(workout, routineExercise, setIndex, plannedSet))
                setRows.addView(space(3))
            }
            addView(setRows)
            addView(space(4))
            addView(row().apply {
                addView(label("＋ 세트추가", 15f, amber, bold = true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        val source = routineExercise.sets.lastOrNull() ?: PlannedSet(null, 10)
                        routineExercise.sets += PlannedSet(source.weightKg, source.targetReps)
                        showActiveWorkout()
                    }
                }, weightParams(1f, 44))
                addView(space(8))
                addView(label("− 세트삭제", 15f, blue, bold = true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        if (routineExercise.sets.size > 1) routineExercise.sets.removeAt(routineExercise.sets.lastIndex)
                        workout.selectedEditSetIndex = workout.selectedEditSetIndex.coerceAtMost(routineExercise.sets.lastIndex)
                        showActiveWorkout()
                    }
                }, weightParams(1f, 44))
            })
            addView(space(4))
            if (!isPreview) addView(row().apply {
                addView(label("☑  모든 세트완료", 15f, blue, bold = true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        routineExercise.sets.forEach { it.completed = true }
                        workout.performedRepsByExercise[routineExercise.exercise.id] = totalTarget
                        showActiveWorkout()
                    }
                }, weightParams(1f, 44))
                addView(space(8))
                addView(label("→  다음운동", 15f, white, bold = true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { advanceWorkoutExercise(workout) }
                }, weightParams(1f, 44))
            })
            addView(space(10))
            summary = label(workoutSetSummary(workout, routineExercise, completedCount), 13f, green, bold = true)
            addView(summary)
            restProgress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = 0
                isIndeterminate = false
                progressTintList = android.content.res.ColorStateList.valueOf(amber)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(61, 52, 25))
                visibility = View.INVISIBLE
            }
            addView(restProgress, LinearLayout.LayoutParams(-1, dp(3)).apply {
                topMargin = dp(7)
                bottomMargin = dp(7)
            })
            addView(label("휴식 시간", 12f, amber, bold = true))
            addView(space(2))
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                restDuration = label("${routineExercise.restSeconds}초", 22f, white, bold = true)
                addView(restDuration, weightParams(1f, 44))
                addView(label("−", 18f, white, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(amber, 99)
                    setOnClickListener { adjustRestDuration(workout, routineExercise, -10) }
                }, fixedParams(42, 42))
                addView(space(10))
                addView(label("+", 18f, bg, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(amber, 99)
                    setOnClickListener { adjustRestDuration(workout, routineExercise, 10) }
                }, fixedParams(42, 42))
                addView(space(10))
                addView(label(if (workout.paused) "▶" else "Ⅱ", 18f, white, bold = true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { toggleWorkoutPause(workout); showActiveWorkout() }
                }, fixedParams(42, 42))
                addView(space(10))
                addView(label("■", 16f, white, bold = true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { confirmStopWorkout() }
                }, fixedParams(42, 42))
            })
        }
        val scroll = ScrollView(this).apply {
            addView(page)
            setOnScrollChangeListener { _, _, y, _, _ ->
                workout.scrollY = y
                workout.scrollYByExercise[routineExercise.exercise.id] = y
            }
        }
        val pager = WorkoutPagerHost(workout, scroll)
        showStandalone(pager, null)
        activeWorkoutUi = ActiveWorkoutUi(
            workout = workout,
            exerciseIndex = workout.exerciseIndex,
            setRows = setRows,
            target = targetStatus,
            performed = performedStatus,
            summary = summary,
            restDuration = restDuration,
            restProgress = restProgress,
        )
        updateRestUi(workout)
        if (!isPreview && !workout.paused && workout.phase == WorkoutPhase.WORKING && activeRestEndAtMs <= 0L) {
            val currentSet = routineExercise.sets.getOrNull(workout.setIndex)
            if (persistActiveWorkout(workout)) {
                sendWear(
                    WearSync.PATH_COMMAND,
                    WearSync.richCommand(
                        "SET", workout.sessionId, routineExercise.exercise.id, routineExercise.exercise.name,
                        workout.setIndex, currentSet?.targetReps ?: 10, currentSet?.weightKg, routineExercise.restSeconds,
                    ),
                )
            }
        }
        scroll.post {
            scroll.scrollTo(
                0,
                workout.scrollYByExercise[routineExercise.exercise.id] ?: workout.scrollY,
            )
        }
        if (isPreview) return
        ticker = object : Runnable {
            override fun run() {
                try {
                    val seconds = workoutElapsedSeconds(workout)
                    elapsed.text = timeText(seconds)
                    if (activeRestEndAtMs > 0L && System.currentTimeMillis() >= activeRestEndAtMs) {
                        continueAfterRest(workout)
                        return
                    }
                    updateRestUi(workout)
                    handler.postDelayed(this, if (activeRestEndAtMs > 0L) 100 else 1_000)
                } catch (error: Exception) {
                    reportWorkoutUiError("active_ticker", error)
                    // Keep the Activity alive and rebuild the same workout screen
                    // from the already committed session snapshot.
                    handler.post {
                        runCatching { showActiveWorkout(forceRebuild = true) }
                            .onFailure { reportWorkoutUiError("active_ticker_rebuild", it) }
                    }
                }
            }
        }.also { handler.post(it) }
    }

    /**
     * A three-page workout pager. Previous and next exercises are already
     * rendered beside the current ScrollView, so the finger always reveals a
     * real page instead of the root background.
     */
    private inner class WorkoutPagerHost(
        private val workout: ActiveWorkout,
        private val currentPage: ScrollView,
    ) : FrameLayout(this@MainActivity) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val currentIndex = workout.exerciseIndex
        private val previousPage = (currentIndex - 1)
            .takeIf { it in workout.routine.exercises.indices }
            ?.let { buildWorkoutSwipePreview(workout, it) }
        private val nextPage = (currentIndex + 1)
            .takeIf { it in workout.routine.exercises.indices }
            ?.let { buildWorkoutSwipePreview(workout, it) }
        private var downX = 0f
        private var downY = 0f
        private var dragX = 0f
        private var horizontal = false
        private var vertical = false
        private var velocityTracker: VelocityTracker? = null

        init {
            setBackgroundColor(bg)
            isClickable = true
            previousPage?.let { addView(it, FrameLayout.LayoutParams(-1, -1)) }
            nextPage?.let { addView(it, FrameLayout.LayoutParams(-1, -1)) }
            addView(currentPage, FrameLayout.LayoutParams(-1, -1))
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (!horizontal && !workoutTransitionRunning) positionPages(0f)
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> beginGesture(event)
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    classifyGesture(event)
                    if (horizontal) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        updateDrag(event.x - downX)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycleTrackerIfIdle()
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginGesture(event)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    classifyGesture(event)
                    if (horizontal) updateDrag(event.x - downX)
                    return horizontal
                }
                MotionEvent.ACTION_UP -> {
                    if (horizontal) finishDrag(cancelled = false) else recycleTrackerIfIdle()
                    return horizontal
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (horizontal) finishDrag(cancelled = true) else recycleTrackerIfIdle()
                    return horizontal
                }
            }
            return false
        }

        fun animateTo(index: Int) {
            if (index !in workout.routine.exercises.indices || index == currentIndex) return
            if (kotlin.math.abs(index - currentIndex) != 1) {
                showWorkoutExercise(workout, index)
                return
            }
            settleTo(index)
        }

        private fun beginGesture(event: MotionEvent) {
            currentPage.animate().cancel()
            previousPage?.animate()?.cancel()
            nextPage?.animate()?.cancel()
            downX = event.x
            downY = event.y
            dragX = 0f
            horizontal = false
            vertical = false
            velocityTracker?.recycle()
            velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
        }

        private fun classifyGesture(event: MotionEvent) {
            if (horizontal || vertical) return
            val dx = event.x - downX
            val dy = event.y - downY
            if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) <= touchSlop) return
            horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.15f
            vertical = !horizontal
        }

        private fun updateDrag(rawDx: Float) {
            val hasDestination = if (rawDx < 0f) nextPage != null else previousPage != null
            dragX = if (hasDestination) rawDx else rawDx * 0.24f
            positionPages(dragX)
        }

        private fun positionPages(offset: Float) {
            val pageWidth = width.takeIf { it > 0 }?.toFloat()
                ?: resources.displayMetrics.widthPixels.toFloat()
            currentPage.translationX = offset
            previousPage?.translationX = -pageWidth + offset
            nextPage?.translationX = pageWidth + offset
        }

        private fun finishDrag(cancelled: Boolean) {
            velocityTracker?.computeCurrentVelocity(1_000)
            val velocityX = velocityTracker?.xVelocity ?: 0f
            velocityTracker?.recycle()
            velocityTracker = null
            val pageWidth = width.coerceAtLeast(resources.displayMetrics.widthPixels).toFloat()
            val direction = when {
                kotlin.math.abs(velocityX) > 900f -> if (velocityX < 0f) 1 else -1
                dragX < 0f -> 1
                else -> -1
            }
            val destination = currentIndex + direction
            val shouldChange = !cancelled && destination in workout.routine.exercises.indices &&
                (kotlin.math.abs(dragX) >= pageWidth * 0.18f ||
                    (kotlin.math.abs(velocityX) > 900f && kotlin.math.abs(dragX) > dp(12)))
            if (shouldChange) settleTo(destination) else settleBack()
            horizontal = false
            vertical = false
        }

        private fun settleBack() {
            val pageWidth = width.coerceAtLeast(resources.displayMetrics.widthPixels).toFloat()
            animatePage(currentPage, 0f)
            previousPage?.let { animatePage(it, -pageWidth) }
            nextPage?.let { animatePage(it, pageWidth) }
        }

        private fun settleTo(index: Int) {
            if (workoutTransitionRunning) return
            val direction = if (index > currentIndex) 1 else -1
            val pageWidth = width.coerceAtLeast(resources.displayMetrics.widthPixels).toFloat()
            val incoming = if (direction > 0) nextPage else previousPage
            if (incoming == null) {
                settleBack()
                return
            }
            workoutTransitionRunning = true
            animatePage(currentPage, -direction * pageWidth)
            if (direction > 0) previousPage?.let { animatePage(it, -2f * pageWidth) }
            else nextPage?.let { animatePage(it, 2f * pageWidth) }
            incoming.animate()
                .translationX(0f)
                .setDuration(230L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .withEndAction {
                    showWorkoutExercise(workout, index)
                    workoutTransitionRunning = false
                }
                .start()
        }

        private fun animatePage(view: View, target: Float) {
            view.animate()
                .translationX(target)
                .setDuration(230L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
        }

        private fun recycleTrackerIfIdle() {
            if (!horizontal) {
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
    }

    private fun buildWorkoutSwipePreview(workout: ActiveWorkout, index: Int): ScrollView {
        val routineExercise = workout.routine.exercises[index]
        val weighted = routineExercise.exercise.equipment != "맨몸"
        val selectedSet = workout.setIndexByExercise[routineExercise.exercise.id] ?: 0
        val completedCount = routineExercise.sets.completedWorkSetCount()
        val performed = workout.performedRepsByExercise[routineExercise.exercise.id] ?: 0
        val page = vertical().apply {
            setPadding(dp(16), dp(12), dp(16), dp(20))
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label("‹", 38f, white).apply { gravity = Gravity.CENTER }, fixedParams(42, 44))
                addView(vertical().apply {
                    addView(label(workout.routine.name, 16f, white, bold = true))
                    addView(label("운동 시간", 11f, muted))
                }, weightParams(1f, -2))
                addView(label(timeText(workoutElapsedSeconds(workout)), 17f, white, bold = true))
                addView(label("•••", 16f, muted, bold = true).apply { gravity = Gravity.CENTER }, fixedParams(42, 44))
            })
            addView(space(10))
            addView(vertical().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(20, 24, 28))
                    setStroke(1, Color.rgb(44, 52, 60))
                    cornerRadius = 12f
                }
                addView(label("⌚ Watch8 연결 상태 유지", 12f, green, bold = true), matchParams(23))
                addView(label("운동을 놓으면 이 페이지로 전환", 12f, green, bold = true), matchParams(23))
            }, matchParams(60))
            addView(space(12))
            addView(row().apply {
                addView(label("${index + 1} / ${workout.routine.exercises.size}  운동", 12f, blue, bold = true), weightParams(1f, -2))
                addView(label("●  ●  ●  ●  ●", 10f, muted))
            })
            addView(space(6))
            addView(exerciseAnimationView(routineExercise.exercise, 190))
            addView(space(6))
            addView(row().apply {
                addView(label(routineExercise.exercise.name, 19f, white, bold = true), weightParams(1f, -2))
                addView(label("ⓘ", 18f, muted).apply { gravity = Gravity.CENTER }, fixedParams(38, 42))
            })
            addView(label(routineExercise.exercise.muscles, 13f, muted))
            addView(space(4))
            addView(row().apply {
                addView(label("목표 ${routineExercise.sets.sumOf { it.targetReps }}회", 14f, blue, bold = true), weightParams(1f, -2))
                addView(label("수행 ${performed}회", 14f, if (performed > 0) amber else muted, bold = true))
            })
            addView(space(10))
            addView(row().apply {
                addView(label("›  기록", 14f, white, bold = true), weightParams(1f, 38))
                addView(label("?", 14f, white).apply {
                    gravity = Gravity.CENTER
                    background = rounded(muted, 99)
                }, fixedParams(28, 28))
            })
            addView(space(4))
            addView(activeSetGridRow(weighted, 32).apply {
                addActiveSetCell(this, label("세트", 12f, muted, bold = true).apply { gravity = Gravity.CENTER }, 0)
                if (weighted) addActiveSetCell(this, label(if (workout.usePounds) "LB" else "KG", 12f, muted, bold = true).apply { gravity = Gravity.CENTER }, 1)
                addActiveSetCell(this, label("회", 12f, muted, bold = true).apply { gravity = Gravity.CENTER }, if (weighted) 2 else 1)
                addActiveSetCell(this, label("완료", 12f, muted, bold = true).apply { gravity = Gravity.CENTER }, if (weighted) 3 else 2)
                addActiveSetCell(this, Space(this@MainActivity), if (weighted) 4 else 3)
            })
            routineExercise.sets.forEachIndexed { setIndex, set ->
                addView(workoutSetPreviewRow(workout, routineExercise, setIndex, set, selectedSet))
                addView(space(3))
            }
            addView(space(4))
            addView(row().apply {
                addView(label("＋ 세트추가", 15f, amber, bold = true).apply { gravity = Gravity.CENTER }, weightParams(1f, 44))
                addView(space(8))
                addView(label("− 세트삭제", 15f, blue, bold = true).apply { gravity = Gravity.CENTER }, weightParams(1f, 44))
            })
            if (activeWorkout === workout) {
                addView(space(4))
                addView(row().apply {
                    addView(label("☑  모든 세트완료", 15f, blue, bold = true).apply { gravity = Gravity.CENTER }, weightParams(1f, 44))
                    addView(space(8))
                    addView(label("→  다음운동", 15f, white, bold = true).apply { gravity = Gravity.CENTER }, weightParams(1f, 44))
                })
            }
            addView(space(10))
            val currentLabel = routineExercise.sets.workSetLabel(selectedSet).let { if (it == "W") "웜업" else "세트 $it / ${routineExercise.sets.workSetCount()}" }
            addView(label("현재 $currentLabel · 완료 ${completedCount}세트", 13f, green, bold = true))
            addView(space(12))
            addView(label("휴식 시간", 12f, amber, bold = true))
            addView(label("${routineExercise.restSeconds}초", 22f, white, bold = true), matchParams(44))
        }
        return ScrollView(this).apply {
            addView(page)
            post { scrollTo(0, workout.scrollYByExercise[routineExercise.exercise.id] ?: 0) }
        }
    }

    private fun workoutSetPreviewRow(
        workout: ActiveWorkout,
        routineExercise: RoutineExercise,
        setIndex: Int,
        plannedSet: PlannedSet,
        currentSetIndex: Int,
    ): View = activeSetGridRow(
        routineExercise.exercise.equipment != "맨몸",
        42,
        if (currentSetIndex == setIndex) Color.rgb(38, 39, 39) else surfaceRaised,
    ).apply {
        val weighted = routineExercise.exercise.equipment != "맨몸"
        addActiveSetCell(this, label(routineExercise.sets.workSetLabel(setIndex), 13f, if (setIndex == currentSetIndex) white else muted, bold = true).apply { gravity = Gravity.CENTER }, 0)
        if (weighted) addActiveSetCell(this, label(formatActiveWeight(plannedSet.weightKg, workout.usePounds), 13f, white, bold = true).apply { gravity = Gravity.CENTER }, 1)
        addActiveSetCell(this, label(plannedSet.targetReps.toString(), 13f, white, bold = true).apply { gravity = Gravity.CENTER }, if (weighted) 2 else 1)
        addActiveSetCell(this, label(if (plannedSet.completed) "✓" else "", 15f, if (plannedSet.completed) amber else muted, bold = true).apply { gravity = Gravity.CENTER }, if (weighted) 3 else 2)
        addActiveSetCell(this, label("•••", 13f, muted, bold = true).apply { gravity = Gravity.CENTER }, if (weighted) 4 else 3, filled = false)
    }

    private fun refreshActiveWorkoutUi(workout: ActiveWorkout) {
        val ui = activeWorkoutUi ?: return
        if (ui.workout !== workout || currentScreen != Screen.ACTIVE) return
        val routineExercise = workout.routine.exercises.getOrNull(workout.exerciseIndex) ?: return
        workout.selectedEditSetIndex = workout.selectedEditSetIndex.coerceIn(0, routineExercise.sets.lastIndex)
        val completedCount = routineExercise.sets.completedWorkSetCount()
        val totalTarget = routineExercise.sets.sumOf { it.targetReps }
        val performed = workout.performedRepsByExercise[routineExercise.exercise.id] ?: 0

        ui.target.text = "목표 ${totalTarget}회"
        ui.performed.text = "수행 ${performed}회"
        ui.performed.setTextColor(if (performed > 0) amber else muted)
        ui.summary.text = workoutSetSummary(workout, routineExercise, completedCount)
        ui.restDuration.text = "${routineExercise.restSeconds}초"

        ui.setRows.removeAllViews()
        routineExercise.sets.forEachIndexed { setIndex, plannedSet ->
            ui.setRows.addView(workoutSetRow(workout, routineExercise, setIndex, plannedSet))
            ui.setRows.addView(space(3))
        }
    }

    private fun handleLivePayload(payload: String) {
        val fields = WearSync.parse(payload)
        if (fields.size < 12 || fields[0] != "2") return
        val sessionId = fields[1]
        val exerciseId = fields[2]
        val exerciseName = fields[3]
        val setIndex = fields[4].toIntOrNull() ?: return
        val reps = fields[5].toIntOrNull() ?: 0
        val targetReps = fields[6].toIntOrNull() ?: 0
        val weight = fields[7].takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val heartRate = fields[8].toIntOrNull() ?: 0
        val phase = fields[9]
        val sequence = fields[11].toLongOrNull() ?: 0L
        val synchronizedRestEndAtMs = fields.getOrNull(12)?.toLongOrNull() ?: 0L
        val personalizedTracking = fields.getOrNull(15)?.toBooleanStrictOrNull() == true
        val workout = activeWorkout ?: return
        if (workout.sessionId != sessionId) return
        if (sequence > 0L && sequence <= workout.lastSequence) return
        if (sequence > 0L) workout.lastSequence = sequence

        val previousPhase = workout.phase
        when (phase) {
            "PAUSED" -> {
                workout.phase = WorkoutPhase.PAUSED
                workout.paused = true
            }
            "RESTING" -> {
                workout.phase = WorkoutPhase.RESTING
                workout.paused = false
                if (synchronizedRestEndAtMs > 0L) {
                    activeRestEndAtMs = synchronizedRestEndAtMs
                    val restSeconds = workout.routine.exercises
                        .getOrNull(workout.exerciseIndex)?.restSeconds ?: 0
                    activeRestStartAtMs = synchronizedRestEndAtMs - restSeconds * 1_000L
                }
            }
            "POSITIONING", "PREPARING", "WORKING", "MANUAL_REQUIRED", "READY" -> {
                workout.phase = WorkoutPhase.WORKING
                workout.paused = false
                activeRestEndAtMs = 0L
                activeRestStartAtMs = 0L
            }
        }
        if (workout.phase != previousPhase) persistActiveWorkout(workout)

        val weightText = weight?.let { " · ${cleanWeight(it)}kg" } ?: ""
        val firstLine = "⌚ $exerciseName · 세트 ${setIndex + 1}" +
            if (personalizedTracking) " · 개인보정 적용" else ""
        val secondLine = when (phase) {
            "POSITIONING" -> "팔을 준비 자세로 옮겨 · 움직이는 동안 카운트 안 함"
            "PREPARING" -> "자세 고정 · 흔들림 없이 2초 후 시작"
            "RESTING" -> "휴식 중 · ${reps}/${targetReps}회$weightText · ❤️ ${heartRate}bpm"
            "SET_COMPLETE" -> "세트 완료 · ${reps}회$weightText · ❤️ ${heartRate}bpm"
            else -> "${reps}/${targetReps}회$weightText · ❤️ ${heartRate}bpm"
        }
        lastWearLiveSessionId = sessionId
        lastWearLiveTitle = firstLine
        lastWearLiveTelemetry = secondLine
        activeLiveStatus?.apply {
            text = secondLine
            tag = WATCH_PANEL_TELEMETRY
            setTextColor(green)
        }
        restHeartStatus?.text = "워치 심박수  ❤️ ${heartRate} bpm"
        wearLiveStatus?.apply {
            text = firstLine
            tag = WATCH_PANEL_TELEMETRY
            setTextColor(green)
        }

        if (phase != "SET_COMPLETE") return
        Log.i(
            EVENT_TAG,
            KCoachEventTrace.format(
                KCoachEventMarker.SET_COMPLETE_RECEIVED,
                sessionId,
                exerciseId,
                setIndex,
            ),
        )
        val routineExercise = workout.routine.exercises.getOrNull(workout.exerciseIndex) ?: return
        val restSeconds = routineExercise.restSeconds
        val restEndAtMs = System.currentTimeMillis() + restSeconds * 1_000L
        val transaction = sessionStore.completeSet(
            SetCompletionEvent(
                sessionId = sessionId,
                exerciseId = exerciseId,
                setIndex = setIndex,
                reps = reps,
                restEndAtMs = restEndAtMs,
                sequence = sequence,
            ),
        ).getOrElse { error ->
            Log.e(
                EVENT_TAG,
                KCoachEventTrace.format(
                    KCoachEventMarker.EVENT_FAILED,
                    sessionId,
                    exerciseId,
                    setIndex,
                    detail = "set_transaction:${error.message}",
                ),
                error,
            )
            activeLiveStatus?.takeIf { it.tag != WATCH_PANEL_TELEMETRY }?.apply {
                text = "세트 완료 저장 실패 · 세션은 유지 중"
                setTextColor(amber)
            }
            return
        }
        when (transaction) {
            is SetCompletionResult.Duplicate -> {
                lastCompletedLiveKey = transaction.snapshot.lastCompletedSetKey
                workout.lastCompletedSetKey = transaction.snapshot.lastCompletedSetKey
                return
            }
            is SetCompletionResult.Rejected -> {
                Log.w(
                    EVENT_TAG,
                    KCoachEventTrace.format(
                        KCoachEventMarker.EVENT_FAILED,
                        sessionId,
                        exerciseId,
                        setIndex,
                        detail = "set_rejected:${transaction.reason}",
                    ),
                )
                activeLiveStatus?.takeIf { it.tag != WATCH_PANEL_TELEMETRY }?.apply {
                    text = "잘못된 세트 완료 이벤트 무시 · 세션 유지 중"
                    setTextColor(amber)
                }
                return
            }
            is SetCompletionResult.Applied -> applySnapshotToWorkout(workout, transaction.snapshot)
        }
        lastCompletedLiveKey = workout.lastCompletedSetKey
        activeRestStartAtMs = restEndAtMs - restSeconds * 1_000L
        activeRestEndAtMs = restEndAtMs
        Log.i(
            EVENT_TAG,
            KCoachEventTrace.format(
                KCoachEventMarker.SNAPSHOT_SAVED,
                sessionId,
                exerciseId,
                setIndex,
            ),
        )
        Log.i(
            EVENT_TAG,
            KCoachEventTrace.format(
                KCoachEventMarker.SET_STATE_MUTATED,
                sessionId,
                exerciseId,
                setIndex,
            ),
        )
        sendWear(
            WearSync.PATH_COMMAND,
            restCommand(workout, restSeconds, restEndAtMs),
        )
        Log.i(
            EVENT_TAG,
            KCoachEventTrace.format(
                KCoachEventMarker.REST_COMMAND_SENT,
                sessionId,
                exerciseId,
                setIndex,
            ),
        )
        val previousActiveUi = activeWorkoutUi
        showRest(restSeconds, restEndAtMs)
        Log.i(
            EVENT_TAG,
            KCoachEventTrace.format(
                KCoachEventMarker.ACTIVE_UI_UPDATED,
                sessionId,
                exerciseId,
                setIndex,
                detail = "uiReused=${previousActiveUi != null && activeWorkoutUi === previousActiveUi}",
            ),
        )
    }

    private fun updateWearConnectionStatus() {
        if (wearLiveStatus == null) return
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                wearLiveStatus?.takeIf { it.tag == WATCH_PANEL_CONNECTION }?.apply {
                    text = if (nodes.isNotEmpty()) {
                        "⌚ Watch8 연결됨 · 운동 시작 가능"
                    } else {
                        "⌚ Watch8 연결 안 됨"
                    }
                    setTextColor(if (nodes.isNotEmpty()) green else muted)
                }
            }
            .addOnFailureListener {
                wearLiveStatus?.takeIf { it.tag == WATCH_PANEL_CONNECTION }?.apply {
                    text = "⌚ Watch8 연결 안 됨"
                    setTextColor(muted)
                }
            }
    }

    private fun workoutElapsedSeconds(workout: ActiveWorkout, now: Long = System.currentTimeMillis()): Long {
        val pausedNow = if (workout.paused) now - workout.pausedSinceMs else 0L
        return ((now - workout.startedAtMs - workout.totalPausedMs - pausedNow) / 1000).coerceAtLeast(0)
    }

    private fun toggleWorkoutPause(workout: ActiveWorkout) {
        val now = System.currentTimeMillis()
        if (workout.paused) {
            workout.totalPausedMs += now - workout.pausedSinceMs
            workout.pausedSinceMs = 0L
            workout.paused = false
            if (pausedRestRemainingMs > 0L) {
                activeRestEndAtMs = now + pausedRestRemainingMs
                activeRestStartAtMs = now
                pausedRestRemainingMs = 0L
                workout.phase = WorkoutPhase.RESTING
            } else {
                workout.phase = WorkoutPhase.WORKING
            }
            if (!persistActiveWorkout(workout)) return
            sendWear(WearSync.PATH_COMMAND, WearSync.command("RESUME", workout.sessionId, "", 0, 0))
        } else {
            if (activeRestEndAtMs > 0L) {
                pausedRestRemainingMs = (activeRestEndAtMs - now).coerceAtLeast(0L)
                activeRestEndAtMs = 0L
                activeRestStartAtMs = 0L
            }
            workout.pausedSinceMs = now
            workout.paused = true
            workout.phase = WorkoutPhase.PAUSED
            if (!persistActiveWorkout(workout)) return
            sendWear(WearSync.PATH_COMMAND, WearSync.command("PAUSE", workout.sessionId, "", 0, 0))
        }
        showActiveWorkout(forceRebuild = true)
    }

    private fun startHomeWorkoutTicker(workout: ActiveWorkout, label: TextView) {
        ticker = object : Runnable {
            override fun run() {
                if (activeWorkout !== workout || currentScreen != Screen.ROOT) return
                label.text = timeText(workoutElapsedSeconds(workout))
                handler.postDelayed(this, 1_000)
            }
        }.also {
            it.run()
        }
    }

    private fun formatActiveWeight(weightKg: Double?, usePounds: Boolean): String {
        if (weightKg == null) return "체중"
        val value = if (usePounds) weightKg * 2.20462 else weightKg
        return cleanWeight(value)
    }

    private fun workoutSetSummary(
        workout: ActiveWorkout,
        routineExercise: RoutineExercise,
        completedWorkSets: Int = routineExercise.sets.completedWorkSetCount(),
    ): String {
        val current = routineExercise.sets.workSetLabel(workout.setIndex)
        val currentLabel = if (current == "W") "웜업" else "세트 $current / ${routineExercise.sets.workSetCount()}"
        return "현재 $currentLabel · 완료 ${completedWorkSets}세트"
    }

    private fun activeSetGridRow(weighted: Boolean, heightDp: Int, fill: Int? = null): GridLayout {
        val weights = if (weighted) listOf(0.78f, 1.25f, 1.15f, 0.78f, 0.38f) else listOf(0.82f, 1.28f, 0.82f, 0.38f)
        return GridLayout(this).apply {
            columnCount = weights.size
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            setPadding(dp(4), dp(2), dp(4), dp(2))
            tag = Triple(weights, heightDp, fill)
        }
    }

    private fun addActiveSetCell(grid: GridLayout, view: View, column: Int, filled: Boolean = true) {
        val (weights, heightDp, fill) = grid.tag as Triple<*, *, *>
        if (fill != null && filled && view !is Space) view.background = rounded(fill as Int, 10)
        grid.addView(view, GridLayout.LayoutParams().apply {
            width = 0
            height = dp((heightDp as Int) - if (fill != null) 6 else 0)
            columnSpec = GridLayout.spec(column, 1, ((weights as List<*>)[column] as Float))
            rowSpec = GridLayout.spec(0)
            if (fill != null) setMargins(if (filled) dp(3) else 0, dp(3), if (filled) dp(3) else 0, dp(3))
        })
    }

    private fun workoutSetRow(
        workout: ActiveWorkout,
        routineExercise: RoutineExercise,
        setIndex: Int,
        plannedSet: PlannedSet,
    ): View = activeSetGridRow(routineExercise.exercise.equipment != "맨몸", 42, if (workout.selectedEditSetIndex == setIndex) Color.rgb(38, 39, 39) else surfaceRaised).apply {
        val weighted = routineExercise.exercise.equipment != "맨몸"
        addActiveSetCell(this, label(routineExercise.sets.workSetLabel(setIndex), 13f, if (setIndex == workout.setIndex) white else muted, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                workout.selectedEditSetIndex = setIndex
                showSetMenu(workout, routineExercise, setIndex)
            }
        }, 0)
        if (weighted) {
            addActiveSetCell(this, label(formatActiveWeight(plannedSet.weightKg, workout.usePounds), 13f, white, bold = true).apply {
                gravity = Gravity.CENTER
                setOnClickListener {
                    workout.selectedEditSetIndex = setIndex
                    val initial = formatActiveWeight(plannedSet.weightKg, workout.usePounds).replace("체중", "")
                    showNumberPad(if (workout.usePounds) "중량 lb" else "중량 kg", initial, true,
                        onCommit = { value ->
                            value.toDoubleOrNull()?.let { parsed -> plannedSet.weightKg = if (workout.usePounds) parsed / 2.20462 else parsed }
                            showActiveWorkout()
                        },
                        onPrevious = { workout.selectedEditSetIndex = (setIndex - 1).coerceAtLeast(0); showActiveWorkout() },
                        onNext = { workout.selectedEditSetIndex = (setIndex + 1).coerceAtMost(routineExercise.sets.lastIndex); showActiveWorkout() },
                    )
                }
            }, 1)
        }
        addActiveSetCell(this, label(plannedSet.targetReps.toString(), 13f, white, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                workout.selectedEditSetIndex = setIndex
                showNumberPad("횟수", plannedSet.targetReps.toString(), false,
                    onCommit = { value -> plannedSet.targetReps = value.toIntOrNull()?.coerceAtLeast(0) ?: plannedSet.targetReps; showActiveWorkout() },
                    onPrevious = { workout.selectedEditSetIndex = (setIndex - 1).coerceAtLeast(0); showActiveWorkout() },
                    onNext = { workout.selectedEditSetIndex = (setIndex + 1).coerceAtMost(routineExercise.sets.lastIndex); showActiveWorkout() },
                )
            }
        }, if (weighted) 2 else 1)
        addActiveSetCell(this, label(if (plannedSet.completed) "✓" else "", 15f, if (plannedSet.completed) amber else muted, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                if (!plannedSet.completed) {
                    if (activeWorkout !== workout) {
                        showStartGuide(
                            routine = workout.routine,
                            startExerciseIndex = workout.exerciseIndex,
                            startSetIndex = setIndex,
                        ) { startedWorkout ->
                            val startedExercise = startedWorkout.routine.exercises
                                .getOrNull(startedWorkout.exerciseIndex) ?: return@showStartGuide
                            val startedSet = startedExercise.sets.getOrNull(setIndex)
                                ?: return@showStartGuide
                            completeWorkoutSet(startedWorkout, startedExercise, setIndex, startedSet)
                        }
                        return@setOnClickListener
                    }
                    completeWorkoutSet(workout, routineExercise, setIndex, plannedSet)
                } else {
                    plannedSet.completed = false
                    val current = workout.performedRepsByExercise[routineExercise.exercise.id] ?: 0
                    workout.performedRepsByExercise[routineExercise.exercise.id] =
                        (current - plannedSet.targetReps).coerceAtLeast(0)
                    workout.phase = WorkoutPhase.WORKING
                    if (workout.lastCompletedSetKey ==
                        "${workout.sessionId}:${routineExercise.exercise.id}:$setIndex"
                    ) {
                        workout.lastCompletedSetKey = null
                        lastCompletedLiveKey = null
                    }
                    persistActiveWorkout(workout)
                    showActiveWorkout()
                }
            }
        }, if (weighted) 3 else 2)
        addActiveSetCell(this, label("•••", 13f, muted, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { showSetMenu(workout, routineExercise, setIndex) }
        }, if (weighted) 4 else 3, filled = false)
    }

    private fun completeWorkoutSet(
        workout: ActiveWorkout,
        routineExercise: RoutineExercise,
        setIndex: Int,
        plannedSet: PlannedSet,
    ) {
        val restEndAtMs = System.currentTimeMillis() + routineExercise.restSeconds * 1_000L
        val transaction = sessionStore.completeSet(
            SetCompletionEvent(
                sessionId = workout.sessionId,
                exerciseId = routineExercise.exercise.id,
                setIndex = setIndex,
                reps = plannedSet.targetReps,
                restEndAtMs = restEndAtMs,
                sequence = System.currentTimeMillis(),
            ),
        ).getOrElse {
            activeLiveStatus?.takeIf { it.tag != WATCH_PANEL_TELEMETRY }?.apply {
                text = "세트 완료 저장 실패 · 세션은 유지 중"
                setTextColor(amber)
            }
            return
        }
        if (transaction !is SetCompletionResult.Applied) return
        applySnapshotToWorkout(workout, transaction.snapshot)
        lastCompletedLiveKey = workout.lastCompletedSetKey
        activeRestStartAtMs = restEndAtMs - routineExercise.restSeconds * 1_000L
        activeRestEndAtMs = restEndAtMs
        if (activeWorkout === workout) {
            val next = nextWorkoutTarget(workout)
            sendWear(
                WearSync.PATH_COMMAND,
                WearSync.richCommand(
                    "SET_COMPLETE", workout.sessionId, next.exerciseId, next.exerciseName,
                    next.setIndex, next.targetReps, next.weightKg, routineExercise.restSeconds, restEndAtMs,
                ),
            )
        }
        showRest(routineExercise.restSeconds, restEndAtMs)
    }

    private fun advanceWorkoutExercise(workout: ActiveWorkout) {
        animateWorkoutExercise(workout, workout.exerciseIndex + 1, root.getChildAt(0))
    }

    private fun animateWorkoutExercise(workout: ActiveWorkout, index: Int, currentView: View?) {
        if (index !in workout.routine.exercises.indices) {
            currentView?.animate()
                ?.translationX(0f)
                ?.alpha(1f)
                ?.setDuration(180L)
                ?.setInterpolator(DecelerateInterpolator())
                ?.start()
            return
        }
        if (workoutTransitionRunning) return
        if (currentView is WorkoutPagerHost) {
            currentView.animateTo(index)
            return
        }
        val outgoing = currentView ?: run {
            showWorkoutExercise(workout, index)
            return
        }
        workoutTransitionRunning = true
        handler.postDelayed({ workoutTransitionRunning = false }, 650L)
        val widthPx = outgoing.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val direction = if (index > workout.exerciseIndex) 1f else -1f
        val outgoingTarget = -direction * widthPx
        outgoing.animate()
            .translationX(outgoingTarget)
            .alpha(0.94f)
            .setDuration(170L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (!showWorkoutExercise(workout, index)) {
                    outgoing.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(180L)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction { workoutTransitionRunning = false }
                        .start()
                    return@withEndAction
                }
                root.getChildAt(0)?.apply {
                    translationX = direction * widthPx
                    alpha = 0.94f
                    animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(220L)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction { workoutTransitionRunning = false }
                        .start()
                } ?: run {
                    workoutTransitionRunning = false
                }
            }
            .start()
    }

    private fun showWorkoutExercise(workout: ActiveWorkout, index: Int): Boolean {
        if (index !in workout.routine.exercises.indices) return false
        val previousExerciseId = workout.routine.exercises
            .getOrNull(workout.exerciseIndex)?.exercise?.id
        if (previousExerciseId != null) {
            workout.setIndexByExercise[previousExerciseId] = workout.setIndex
            workout.selectedSetIndexByExercise[previousExerciseId] = workout.selectedEditSetIndex
            workout.scrollYByExercise[previousExerciseId] = workout.scrollY
        }
        val previousExerciseIndex = workout.exerciseIndex
        val previousSetIndex = workout.setIndex
        val previousSelectedSetIndex = workout.selectedEditSetIndex
        val previousScrollY = workout.scrollY
        val previousPhase = workout.phase
        val previousRestStartAtMs = activeRestStartAtMs
        val previousRestEndAtMs = activeRestEndAtMs
        val targetExercise = workout.routine.exercises[index]
        val targetExerciseId = targetExercise.exercise.id
        workout.exerciseIndex = index
        workout.setIndex = workout.setIndexByExercise[targetExerciseId]
            ?.coerceIn(0, targetExercise.sets.lastIndex.coerceAtLeast(0)) ?: 0
        workout.selectedEditSetIndex = workout.selectedSetIndexByExercise[targetExerciseId]
            ?.coerceIn(0, targetExercise.sets.lastIndex.coerceAtLeast(0)) ?: workout.setIndex
        workout.scrollY = workout.scrollYByExercise[targetExerciseId] ?: 0
        workout.phase = WorkoutPhase.WORKING
        activeRestStartAtMs = 0L
        activeRestEndAtMs = 0L
        if (activeWorkout === workout && !persistActiveWorkout(workout)) {
            workout.exerciseIndex = previousExerciseIndex
            workout.setIndex = previousSetIndex
            workout.selectedEditSetIndex = previousSelectedSetIndex
            workout.scrollY = previousScrollY
            workout.phase = previousPhase
            activeRestStartAtMs = previousRestStartAtMs
            activeRestEndAtMs = previousRestEndAtMs
            return false
        }
        showActiveWorkout(forceRebuild = true)
        return true
    }

    private fun showWorkoutMenu(workout: ActiveWorkout) {
        val routineExercise = workout.routine.exercises.getOrNull(workout.exerciseIndex) ?: return
        val dialog = bottomDialog()
        val content = vertical().apply {
            setPadding(dp(22), dp(8), dp(22), dp(28))
            addView(label("━", 22f, faint).apply { gravity = Gravity.CENTER })
            addView(label(routineExercise.exercise.name, 18f, white, bold = true).apply {
                setPadding(0, dp(4), 0, dp(18))
            })
            addView(sheetOption(if (workout.usePounds) "kg" else "lb", if (workout.usePounds) "킬로그램으로 단위 변경" else "파운드로 단위 변경") {
                workout.usePounds = !workout.usePounds
                dialog.dismiss()
                showActiveWorkout()
            })
            addView(sheetOption("⏱", "휴식 시간") {
                dialog.dismiss()
                showChoiceSheet("휴식 시간", listOf("30초" to "짧은 휴식", "60초" to "일반 휴식", "90초" to "근비대 기본", "120초" to "고중량 휴식", "160초" to "긴 휴식")) { value ->
                    routineExercise.restSeconds = value.removeSuffix("초").toInt()
                    showActiveWorkout()
                }
            })
            addView(sheetOption("⇄", "운동 대체") {
                dialog.dismiss()
                showExerciseAlternatives(workout.routine, workout.exerciseIndex) { showActiveWorkout() }
            })
        }
        dialog.setContentView(content)
        showBottomDialog(dialog)
    }

    private fun showSetMenu(workout: ActiveWorkout, routineExercise: RoutineExercise, setIndex: Int) {
        val set = routineExercise.sets.getOrNull(setIndex) ?: return
        val dialog = bottomDialog()
        val content = vertical().apply {
            setPadding(dp(18), dp(6), dp(18), dp(20))
            addView(label("━", 22f, faint).apply { gravity = Gravity.CENTER })
            val setLabel = routineExercise.sets.workSetLabel(setIndex).let { if (it == "W") "웜업" else "${it}세트" }
            addView(label("${routineExercise.exercise.name} $setLabel · ${set.targetReps}회", 15f, white, bold = true).apply {
                setPadding(0, dp(3), 0, dp(12))
            })
            addView(sheetOption("◉", "세트 타입\n${set.setType.displayName}") {
                dialog.dismiss()
                showChoiceSheet("세트 타입", listOf("일반" to "일반 세트", "웜업" to "근육을 준비하기 위한 워밍업", "슈퍼" to "두 가지 운동을 휴식 없이", "드랍" to "휴식시간 없이 무게를 낮춰서", "실패" to "실패한 세트", "보조" to "보조 받아 세트를 완료")) { value ->
                    set.setType = SetType.fromStorage(value)
                    showActiveWorkout()
                }
            })
            addView(sheetOption("✊", "그립 타입\n${set.gripType}") {
                dialog.dismiss()
                showChoiceSheet("그립 타입", listOf("노멀" to "표준 그립", "오버핸드" to "손바닥이 몸 반대쪽을 향함", "언더핸드" to "손바닥이 몸 쪽으로 향함", "뉴트럴" to "양손이 서로 마주보게", "와이드" to "어깨 너비보다 넓은 손 배치", "클로즈" to "손을 어깨 너비보다 가깝게", "얼터네이트" to "한 손은 오버핸드, 다른 한 손은 언더핸드")) { value ->
                    set.gripType = value
                    showActiveWorkout()
                }
            })
            addView(sheetOption("Ⓡ", "운동자각도\n${set.rpe ?: "선택해"}") {
                dialog.dismiss()
                showChoiceSheet("RPE", listOf("선택해" to "", "10" to "이게 한계다", "9.5" to "1개 정도 더 할 수 있으려나?", "9" to "확실히 1개 정도 더 할 수 있다", "8.5" to "아마 2개 정도 더 할 수 있다", "8" to "확실히 2개 정도 더 할 수 있다", "7" to "꽤 할만 했다", "6" to "웜업 정도?")) { value ->
                    set.rpe = value.takeUnless { it == "선택해" }
                    showActiveWorkout()
                }
            })
            addView(sheetOption("−", "세트 삭제") {
                dialog.dismiss()
                if (routineExercise.sets.size > 1) routineExercise.sets.removeAt(setIndex)
                workout.selectedEditSetIndex = workout.selectedEditSetIndex.coerceAtMost(routineExercise.sets.lastIndex)
                showActiveWorkout()
            })
            addView(sheetOption("＋", "이전 세트 추가") {
                dialog.dismiss()
                routineExercise.sets.add(setIndex, PlannedSet(set.weightKg, set.targetReps))
                showActiveWorkout()
            })
        }
        dialog.setContentView(content)
        showBottomDialog(dialog)
    }

    private fun showChoiceSheet(title: String, items: List<Pair<String, String>>, onSelect: (String) -> Unit) {
        val dialog = bottomDialog()
        val content = vertical().apply {
            setPadding(dp(18), dp(6), dp(18), dp(20))
            addView(label("━", 22f, faint).apply { gravity = Gravity.CENTER })
            addView(label(title, 16f, white, bold = true).apply { setPadding(0, dp(3), 0, dp(12)) })
            items.forEach { (value, description) ->
                addView(row().apply {
                    setPadding(0, dp(4), 0, dp(4))
                    isClickable = true
                    setOnClickListener {
                        dialog.dismiss()
                        onSelect(value)
                    }
                    addView(label("○", 25f, white), fixedParams(58, 62))
                    addView(vertical().apply {
                        addView(label(value, 17f, white))
                        if (description.isNotBlank()) addView(label(description, 13f, muted))
                    }, weightParams(1f, -2))
                })
            }
        }
        dialog.setContentView(ScrollView(this).apply { addView(content) })
        showBottomDialog(dialog)
    }

    private fun showNumberPad(
        title: String,
        initial: String,
        allowDecimal: Boolean,
        onCommit: (String) -> Unit,
        onPrevious: (() -> Unit)? = null,
        onNext: (() -> Unit)? = null,
    ) {
        val dialog = bottomDialog()
        var buffer = initial.ifBlank { "0" }
        val display = label(buffer, 34f, white, bold = true).apply { gravity = Gravity.CENTER }
        fun refresh() { display.text = buffer.ifBlank { "0" } }
        fun delta(amount: Double) {
            val value = (buffer.toDoubleOrNull() ?: 0.0) + amount
            buffer = if (allowDecimal) cleanWeight(value.coerceAtLeast(0.0)) else value.toInt().coerceAtLeast(0).toString()
            refresh()
        }
        val content = vertical().apply {
            setPadding(dp(20), dp(8), dp(20), dp(24))
            addView(label("━", 22f, faint).apply { gravity = Gravity.CENTER })
            addView(label(title, 19f, white, bold = true).apply { setPadding(0, dp(4), 0, dp(8)) })
            addView(display, matchParams(64))
            addView(row().apply {
                listOf("−5" to { delta(-5.0) }, "−1" to { delta(-1.0) }, "+1" to { delta(1.0) }, "+5" to { delta(5.0) }).forEach { (text, action) ->
                    addView(label(text, 18f, amber, bold = true).apply {
                        gravity = Gravity.CENTER
                        isClickable = true
                        setOnClickListener { action() }
                    }, weightParams(1f, 54))
                }
            })
            val grid = GridLayout(this@MainActivity).apply { columnCount = 3 }
            (1..9).forEach { number ->
                grid.addView(label(number.toString(), 22f, white, bold = true).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener {
                        if (buffer == "0") buffer = ""
                        buffer += number
                        refresh()
                    }
                }, GridLayout.LayoutParams().apply { width = 0; height = dp(58); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
            }
            grid.addView(label(if (allowDecimal) "." else "", 22f, white, bold = true).apply {
                gravity = Gravity.CENTER
                isClickable = allowDecimal
                setOnClickListener { if (allowDecimal && !buffer.contains('.')) { buffer += "."; refresh() } }
            }, GridLayout.LayoutParams().apply { width = 0; height = dp(58); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
            grid.addView(label("0", 22f, white, bold = true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { if (buffer != "0") buffer += "0"; refresh() }
            }, GridLayout.LayoutParams().apply { width = 0; height = dp(58); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
            grid.addView(label("⌫", 22f, white, bold = true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { buffer = buffer.dropLast(1).ifBlank { "0" }; refresh() }
            }, GridLayout.LayoutParams().apply { width = 0; height = dp(58); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f) })
            addView(grid)
            addView(row().apply {
                addView(label("‹ 이전", 17f, blue, bold = true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { onCommit(buffer); dialog.dismiss(); onPrevious?.invoke() }
                }, weightParams(1f, 58))
                addView(label("다음 ›", 17f, blue, bold = true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { onCommit(buffer); dialog.dismiss(); onNext?.invoke() }
                }, weightParams(1f, 58))
                addView(label("✓", 28f, amber, bold = true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { dialog.dismiss(); onCommit(buffer) }
                }, fixedParams(70, 58))
            })
        }
        dialog.setContentView(content)
        showBottomDialog(dialog)
    }

    private fun continueAfterRest(workout: ActiveWorkout) {
        activeRestStartAtMs = 0L
        activeRestEndAtMs = 0L
        workout.phase = WorkoutPhase.WORKING
        val current = workout.routine.exercises.getOrNull(workout.exerciseIndex)
        if (current != null && workout.setIndex >= current.sets.lastIndex && current.sets.all { it.completed }) {
            workout.exerciseIndex += 1
            workout.setIndex = 0
            if (!persistActiveWorkout(workout)) return
            if (workout.exerciseIndex >= workout.routine.exercises.size) {
                showMyMountainDecision()
            } else {
                showActiveWorkout(forceRebuild = true)
            }
        } else {
            if (!persistActiveWorkout(workout)) return
            showActiveWorkout(forceRebuild = true)
        }
    }

    private fun nextWorkoutTarget(workout: ActiveWorkout): NextWorkoutTarget {
        for (exerciseIndex in workout.exerciseIndex..workout.routine.exercises.lastIndex) {
            val exercise = workout.routine.exercises[exerciseIndex]
            val firstSetIndex = if (exerciseIndex == workout.exerciseIndex) workout.setIndex else 0
            val pending = exercise.sets.withIndex().firstOrNull { (setIndex, set) ->
                setIndex >= firstSetIndex && !set.completed
            }
            if (pending != null) {
                return NextWorkoutTarget(
                    exerciseId = exercise.exercise.id,
                    exerciseName = exercise.exercise.name,
                    setIndex = pending.index,
                    targetReps = pending.value.targetReps,
                    weightKg = pending.value.weightKg,
                )
            }
        }
        return NextWorkoutTarget("", "운동 완료", 0, 1, null)
    }

    private fun restCommand(workout: ActiveWorkout, seconds: Int, endAtMs: Long): String {
        val next = nextWorkoutTarget(workout)
        return WearSync.richCommand(
            "REST",
            workout.sessionId,
            next.exerciseId,
            next.exerciseName,
            next.setIndex,
            next.targetReps,
            next.weightKg,
            seconds,
            endAtMs,
        )
    }

    private fun showRest(seconds: Int, synchronizedEndAtMs: Long? = null) {
        val workout = activeWorkout ?: run {
            Log.e(EVENT_TAG, "rest transition ignored: active workout missing")
            return
        }
        val wasActiveScreen = currentScreen == Screen.ACTIVE
        currentScreen = Screen.ACTIVE
        activeRestEndAtMs = synchronizedEndAtMs
            ?: (System.currentTimeMillis() + seconds.coerceAtLeast(0) * 1_000L)
        activeRestStartAtMs = activeRestEndAtMs - seconds.coerceAtLeast(0) * 1_000L
        workout.phase = WorkoutPhase.RESTING
        if (!persistActiveWorkout(workout)) return
        try {
            showActiveWorkout(forceRebuild = !wasActiveScreen)
            updateRestUi(workout)
        } catch (error: Exception) {
            reportWorkoutUiError("enter_rest", error)
            // Session/rest state was committed before rendering. A clean rebuild
            // is safer than letting an Activity-only UI exception close the app.
            runCatching { showActiveWorkout(forceRebuild = true) }
                .onFailure { reportWorkoutUiError("enter_rest_rebuild", it) }
        }
    }

    private fun reportWorkoutUiError(stage: String, error: Throwable) {
        Log.e(EVENT_TAG, "workout UI failure stage=$stage", error)
        getSharedPreferences("wear-sync", MODE_PRIVATE).edit()
            .putString("lastUiError", "$stage:${error.message}")
            .putLong("lastUiErrorAt", System.currentTimeMillis())
            .apply()
    }

    private fun updateRestUi(workout: ActiveWorkout) {
        val ui = activeWorkoutUi ?: return
        if (ui.workout !== workout || currentScreen != Screen.ACTIVE) return
        val routineExercise = workout.routine.exercises.getOrNull(workout.exerciseIndex) ?: return
        if (activeRestEndAtMs <= 0L) {
            ui.restDuration.text = "${routineExercise.restSeconds}초"
            ui.restProgress.visibility = View.INVISIBLE
            ui.restProgress.progress = 0
            return
        }
        val now = System.currentTimeMillis()
        val leftMs = (activeRestEndAtMs - now).coerceAtLeast(0L)
        val leftSeconds = ((leftMs + 999L) / 1_000L).toInt()
        val totalMs = (activeRestEndAtMs - activeRestStartAtMs).coerceAtLeast(1L)
        ui.restDuration.text = "${leftSeconds}초"
        ui.restProgress.visibility = View.VISIBLE
        ui.restProgress.progress = ((leftMs * 1000L) / totalMs).toInt().coerceIn(0, 1000)
    }


    private fun showMyMountainDecision() {
        stopTicker()
        val completedWorkout = activeWorkout
        lastWorkoutResult = completedWorkout?.let { workout ->
            WorkoutResultUi(
                sessionId = workout.sessionId,
                title = workout.routine.name,
                durationMinutes = (workoutElapsedSeconds(workout) / 60L).toInt(),
                completedSets = workout.routine.exercises.sumOf { exercise -> exercise.sets.count { it.completed } },
                analyses = workout.analyses.toMutableMap(),
            )
        }
        lastWorkoutResult?.let(::persistLastWorkoutResult)
        if (completedWorkout != null) {
            if (!persistActiveWorkout(completedWorkout)) return
            sendWear(WearSync.PATH_COMMAND, WearSync.command("COMPLETE", completedWorkout.sessionId, "", 0, 0))
            sessionStore.clearActive()
        }
        activeWorkout = null
        activeRestStartAtMs = 0L
        activeRestEndAtMs = 0L
        pausedRestRemainingMs = 0L
        val dialog = bottomDialog()
        val content = vertical().apply {
            setPadding(dp(24), dp(12), dp(24), dp(28))
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label("━", 28f, faint))
            addView(space(8))
            addView(label("웨이트 완료", 15f, green, bold = true))
            addView(space(8))
            addView(label("마이마운틴 할까?", 28f, white, bold = true))
            addView(space(10))
            addView(label("오늘 컨디션에 따라 선택하면 돼.\n안 해도 루틴은 정상 완료야.", 15f, muted).apply {
                gravity = Gravity.CENTER
            })
            addView(space(24))
            addView(row().apply {
                addView(primaryButton("아니오", surfaceRaised) {
                    dialog.dismiss()
                    showWorkoutResult(lastWorkoutResult)
                }, weightParams(1f, 60))
                addView(space(12))
                addView(primaryButton("예", amberDark) {
                    dialog.dismiss()
                    showMyMountain()
                }, weightParams(1f, 60))
            })
        }
        dialog.setContentView(content)
        showBottomDialog(dialog)
    }

    private fun showMyMountain() {
        currentScreen = Screen.MY_MOUNTAIN
        val page = vertical().apply {
            setPadding(dp(20), dp(16), dp(20), dp(24))
            addView(detailTopBar("마이마운틴"))
            addView(space(28))
            addView(label("오늘 설정", 28f, white, bold = true))
            addView(space(8))
            addView(label("경사·속도·시간은 매번 바꿀 수 있어.", 15f, muted))
            addView(space(24))
            addView(row().apply {
                addView(inputCard("경사", numberInput("13", "경사", true)), weightParams(1f, 118))
                addView(space(10))
                addView(inputCard("속도", numberInput("4.0", "km/h", true)), weightParams(1f, 118))
                addView(space(10))
                addView(inputCard("시간", numberInput("30", "분", true)), weightParams(1f, 118))
            })
            addView(space(28))
            addView(primaryButton("마이마운틴 시작", amberDark) {
                Toast.makeText(this@MainActivity, "워치 연결 후 심박수와 함께 기록돼", Toast.LENGTH_LONG).show()
            }, matchParams(64))
            addView(space(12))
            addView(primaryButton("오늘은 종료", surfaceRaised) { showRecords() }, matchParams(56))
        }
        showStandalone(ScrollView(this).apply { addView(page) })
    }

    private fun calendarCard(month: YearMonth): View {
        val card = vertical().apply {
            background = rounded(surface, 22)
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        card.addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("‹", 30f, muted).apply {
                gravity = Gravity.CENTER
                setOnClickListener {
                    recordMonth = recordMonth.minusMonths(1)
                    showRecords()
                }
            }, fixedParams(42, 42))
            addView(label("${month.year}년 ${month.monthValue}월", 21f, white, bold = true).apply {
                gravity = Gravity.CENTER
            }, weightParams(1f, 42))
            addView(label("›", 30f, muted).apply {
                gravity = Gravity.CENTER
                setOnClickListener {
                    recordMonth = recordMonth.plusMonths(1)
                    showRecords()
                }
            }, fixedParams(42, 42))
        })
        card.addView(space(14))
        val grid = GridLayout(this).apply { columnCount = 7 }
        listOf("일", "월", "화", "수", "목", "금", "토").forEach {
            grid.addView(label(it, 12f, muted, bold = true).apply { gravity = Gravity.CENTER }, gridParams())
        }
        val firstOffset = month.atDay(1).dayOfWeek.value % 7
        repeat(firstOffset) { grid.addView(Space(this), gridParams()) }
        val recordedDays = WorkoutData.records
            .filter { it.date.startsWith(month.toString()) }
            .map { LocalDate.parse(it.date).dayOfMonth }
            .toSet()
        for (day in 1..month.lengthOfMonth()) {
            val selected = day in recordedDays
            grid.addView(vertical().apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(6))
                addView(label(day.toString(), 15f, if (selected) white else muted, bold = selected).apply {
                    gravity = Gravity.CENTER
                    if (selected) background = rounded(amberDark, 18)
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                })
                addView(label(if (selected) "•" else "", 13f, amber).apply { gravity = Gravity.CENTER })
            }, gridParams())
        }
        card.addView(grid)
        return card
    }

    private fun recordCard(record: WorkoutRecord): View = horizontalCard().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        addView(vertical().apply {
            addView(label(record.title, 20f, white, bold = true))
            addView(label(record.date.replace("-", "."), 13f, muted))
        }, weightParams(1f, -2))
        addView(vertical().apply {
            gravity = Gravity.END
            addView(label("${record.minutes}분 · ${record.sets}세트", 14f, blue, bold = true).apply { gravity = Gravity.END })
            addView(label("%,dkg".format(record.volumeKg), 13f, muted).apply { gravity = Gravity.END })
        })
    }

    private fun metricGrid(metrics: List<Pair<String, String>>): View {
        val grid = GridLayout(this).apply { columnCount = 2 }
        metrics.forEach { (value, title) ->
            grid.addView(vertical().apply {
                background = rounded(surface, 18)
                setPadding(dp(18), dp(18), dp(18), dp(18))
                addView(label(value, 24f, white, bold = true))
                addView(label(title, 14f, muted))
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(92)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        return grid
    }

    private fun sectionCard(title: String, values: List<Pair<String, String>>): View = vertical().apply {
        background = rounded(surface, 20)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        addView(label(title, 19f, white, bold = true))
        addView(space(12))
        values.forEach { (name, value) ->
            addView(row().apply {
                setPadding(0, dp(8), 0, dp(8))
                addView(label(name, 15f, white), weightParams(1f, -2))
                addView(label(value, 15f, blue, bold = true))
            })
        }
    }

    private fun libraryExerciseRow(exercise: ExerciseDefinition, onClick: () -> Unit): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(5), dp(4), dp(5))
        isClickable = true
        setOnClickListener { onClick() }
        addView(label(if (exercise.favorite) "♥" else "♡", 18f, if (exercise.favorite) Color.rgb(224, 102, 135) else faint), fixedParams(30, 56))
        addView(exerciseAnimationView(exercise, 56, compact = true), fixedParams(56, 56))
        addView(space(10))
        addView(vertical().apply {
            addView(label(exercise.name, 15f, white, bold = true))
            addView(space(2))
            addView(label(exercise.muscles, 12f, muted))
        }, weightParams(1f, -2))
        addView(label("•••", 15f, muted))
    }

    private fun exerciseRow(
        exercise: ExerciseDefinition,
        subtitle: String,
        onInfo: (() -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        onMenu: (() -> Unit)? = null,
        showHandle: Boolean = false,
    ): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        onClick?.let { callback ->
            isClickable = true
            setOnClickListener { callback.invoke() }
        }
        addView(exerciseAnimationView(exercise, 58, compact = true), fixedParams(58, 58))
        addView(space(10))
        addView(vertical().apply {
            addView(label(exercise.name, 15f, white, bold = true))
            addView(space(2))
            addView(label("${exercise.muscles} · $subtitle", 12f, muted))
        }, weightParams(1f, -2))
        if (onInfo != null) {
            addView(label("ⓘ", 17f, muted).apply {
                isClickable = true
                gravity = Gravity.CENTER
                setOnClickListener { onInfo.invoke() }
            }, fixedParams(38, 48))
        }
        addView(label(if (showHandle) "☷" else "•••", 15f, if (showHandle) amber else muted).apply {
            if (onMenu != null) {
                isClickable = true
                setPadding(dp(6), dp(12), 0, dp(12))
                setOnClickListener { onMenu.invoke() }
            }
        }, fixedParams(40, 48))
    }

    private fun showExerciseRecord(
        routine: WorkoutRoutine,
        index: Int,
        active: ActiveWorkout?,
        onReturn: () -> Unit,
    ) {
        val routineExercise = routine.exercises.getOrNull(index) ?: return
        stopTicker()
        currentScreen = Screen.EXERCISE_RECORD
        returnFromExerciseRecord = onReturn

        val targetTotal = routineExercise.sets.sumOf { it.targetReps }
        val performed = active?.performedRepsByExercise?.get(routineExercise.exercise.id) ?: 0
        val animation = exerciseAnimationView(routineExercise.exercise, 320)
        val page = vertical().apply {
            setPadding(dp(20), dp(16), dp(20), dp(34))
            addView(row().apply {
                addView(label("‹", 44f, white).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener { onReturn() }
                }, fixedParams(54, 58))
                addView(weightSpace())
                addView(label("●●●●●●", 11f, muted), fixedParams(120, 58))
                addView(label("•••", 18f, muted, bold = true), fixedParams(54, 58))
            })
            addView(space(8))
            addView(animation)
            addView(space(24))
            addView(row().apply {
                addView(label(routineExercise.exercise.name, 25f, white, bold = true), weightParams(1f, -2))
                addView(label("ⓘ", 24f, muted).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        showExerciseInfo(routineExercise.exercise) {
                            showExerciseRecord(routine, index, active, onReturn)
                        }
                    }
                }, fixedParams(54, 58))
            })
            addView(space(6))
            addView(label(routineExercise.exercise.muscles, 14f, muted))
            addView(space(12))
            addView(row().apply {
                addView(label("횟수 ${targetTotal}회", 18f, blue, bold = true))
                addView(space(22))
                addView(label("${performed}회", 18f, muted, bold = true))
            })
            addView(space(22))
            addView(label("›  메모", 17f, white, bold = true))
            addView(space(12))
            addView(label("›  기록", 17f, white, bold = true))
            addView(space(16))
            addView(row().apply {
                addView(label("세트", 14f, muted, bold = true), weightParams(1f, 42))
                addView(label("회", 14f, muted, bold = true), weightParams(1f, 42))
                addView(label("완료", 14f, muted, bold = true), weightParams(1f, 42))
                addView(label("•••", 16f, muted), fixedParams(42, 42))
            })
            routineExercise.sets.forEachIndexed { setIndex, set ->
                val completed = active != null && setIndex < active.completedSets && active.routine === routine && active.exerciseIndex > index
                addView(row().apply {
                    addView(label("${setIndex + 1}", 16f, white), weightParams(1f, 58))
                    addView(label("${set.targetReps}", 16f, white, bold = true), weightParams(1f, 58))
                    addView(label(if (completed) "✓" else "", 18f, if (completed) green else muted), weightParams(1f, 58))
                    addView(label("•••", 16f, muted), fixedParams(42, 58))
                })
            }
            addView(space(18))
            addView(row().apply {
                addView(label("＋ 세트추가", 16f, amber, bold = true), weightParams(1f, 54))
                addView(label("− 세트삭제", 16f, blue, bold = true), weightParams(1f, 54))
            })
            addView(space(28))
            addView(label("현재 기록", 18f, white, bold = true))
            addView(space(12))
            addView(metricGrid(listOf("${performed}회" to "REP MAX", "${performed}회" to "REPS TOTAL")))
            addView(space(28))
            addView(label("최근 기록", 18f, white, bold = true))
            addView(space(12))
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(row().apply {
                    listOf("2026. 7. 26.", "2026. 7. 23.", "2026. 7. 20.").forEach { date ->
                        addView(vertical().apply {
                            setPadding(dp(14), dp(12), dp(14), dp(12))
                            background = rounded(surface, 14)
                            addView(label("•  $date", 14f, amber, bold = true))
                            addView(space(10))
                            addView(label(routineExercise.sets.mapIndexed { i, set -> "${i + 1}  -  ${set.targetReps}회" }.take(3).joinToString("\n"), 15f, white))
                        }, fixedParams(210, 150))
                        addView(space(10))
                    }
                })
            })
            addView(space(32))
            addView(label("유튜브", 16f, white, bold = true))
            addView(space(8))
            addView(anatomyPlaceholder(118))
            addView(space(26))
            addView(label("휴식 시간", 14f, amber, bold = true))
            addView(label("${routineExercise.restSeconds}초", 32f, white, bold = true))
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(page)
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val progress = (scrollY / dp(420).toFloat()).coerceIn(0f, 1f)
                val height = dp(320) - ((dp(320) - dp(96)) * progress).toInt()
                val params = animation.layoutParams
                if (params.height != height) {
                    params.height = height
                    animation.layoutParams = params
                }
            }
        }
        showStandalone(scroll)
    }

    private fun showExercisePreview(routine: WorkoutRoutine, index: Int) {
        if (index !in routine.exercises.indices) return
        showActiveWorkout(ActiveWorkout(routine = routine, exerciseIndex = index))
    }

    private fun anatomyPlaceholder(heightDp: Int): View = FrameLayout(this).apply {
        minimumHeight = dp(heightDp)
        background = rounded(Color.rgb(23, 26, 26), 20)
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }

    private fun exerciseAnimationView(
        exercise: ExerciseDefinition,
        heightDp: Int,
        compact: Boolean = false,
    ): View {
        val resName = "exercise_${exercise.id}"
        val animationRes = resources.getIdentifier(resName, "drawable", packageName).takeIf { it != 0 }
            ?: return anatomyPlaceholder(heightDp)
        return runCatching {
            val drawable = animationConstantStates[animationRes]?.newDrawable(resources)?.mutate()
                ?: ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(resources, animationRes),
                ).also { drawable ->
                    drawable.constantState?.let { animationConstantStates[animationRes] = it }
                }
            val radiusDp = if (compact) 12 else 20
            FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(heightDp),
                )
                background = rounded(Color.WHITE, radiusDp)
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                contentDescription = "${exercise.name} 운동 애니메이션"
                addView(ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(drawable)
                }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))

                (drawable as? AnimatedImageDrawable)?.apply {
                    repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    start()
                }
            }
        }.getOrElse {
            anatomyPlaceholder(heightDp)
        }
    }

    private fun detailTopBar(title: String): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label("‹", 48f, white).apply {
            gravity = Gravity.CENTER
            setOnClickListener { onBackPressed() }
        }, fixedParams(54, 58))
        addView(label(title, 25f, white, bold = true), weightParams(1f, -2))
        addView(label("•••", 18f, muted, bold = true).apply { gravity = Gravity.CENTER }, fixedParams(54, 58))
    }

    private fun topBar(title: String, right: String): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title, 26f, white, bold = true), weightParams(1f, 52))
        addView(label(right, 23f, muted, bold = true).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END })
    }

    private fun showExerciseMenu(
        routine: WorkoutRoutine,
        index: Int,
        active: ActiveWorkout?,
        onReturn: () -> Unit,
    ) {
        val exercise = routine.exercises.getOrNull(index)?.exercise ?: return
        val dialog = bottomDialog()
        val content = vertical().apply {
            setPadding(dp(22), dp(8), dp(22), dp(28))
            addView(vertical().apply {
                addView(label("━", 22f, faint).apply { gravity = Gravity.CENTER })
                addView(label(exercise.name, 18f, white, bold = true).apply {
                    setPadding(0, dp(4), 0, dp(18))
                })
            })
            addView(sheetOption("🏃", "운동 애니메이션") {
                dialog.dismiss()
                showExerciseInfo(exercise, onReturn)
            })
            addView(sheetOption("ⓘ", "운동 정보") {
                dialog.dismiss()
                showExerciseInfo(exercise, onReturn)
            })
            addView(sheetOption("⛓", "슈퍼세트 설정") {})
            addView(sheetOption("▣", "운동 제거") {
                dialog.dismiss()
                confirmRemoveExercise(routine, index, active, onReturn)
            })
            addView(sheetOption("⇄", "운동 대체") {
                dialog.dismiss()
                showExerciseAlternatives(routine, index, onReturn)
            })
        }
        dialog.setContentView(content)
        showBottomDialog(dialog)
    }

    private fun showExerciseInfo(exercise: ExerciseDefinition, onReturn: () -> Unit) {
        stopTicker()
        currentScreen = Screen.EXERCISE_INFO
        returnFromExerciseInfo = onReturn
        var tab = 0
        val page = vertical().apply { setPadding(dp(20), dp(12), dp(20), dp(24)) }
        page.addView(row().apply {
            addView(label("‹", 40f, white).apply {
                gravity = Gravity.CENTER
                setOnClickListener { onReturn() }
            }, fixedParams(54, 58))
            addView(label(exercise.name, 18f, white, bold = true), weightParams(1f, 52))
        })
        page.addView(space(6))
        page.addView(exerciseAnimationView(exercise, 220))
        page.addView(space(6))
        page.addView(label(exercise.name, 20f, white, bold = true))
        page.addView(space(2))
        page.addView(label(exercise.muscles, 13f, muted))
        page.addView(space(8))

        val tabs = row().apply { gravity = Gravity.CENTER }
        val content = vertical()
        fun infoTab(title: String, index: Int): TextView = label(title, 14f, white, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener {
                tab = index
                refreshExerciseInfoTabs(tabs, content, exercise, tab)
            }
        }
        tabs.addView(infoTab("설명", 0), weightParams(1f, 44))
        tabs.addView(infoTab("나의 기록", 1), weightParams(1f, 44))
        tabs.addView(infoTab("통계", 2), weightParams(1f, 44))
        page.addView(tabs)
        page.addView(space(12))
        page.addView(content)
        refreshExerciseInfoTabs(tabs, content, exercise, tab)
        showStandalone(ScrollView(this).apply { addView(page) })
    }

    private fun refreshExerciseInfoTabs(
        tabs: ViewGroup,
        content: ViewGroup,
        exercise: ExerciseDefinition,
        selected: Int,
    ) {
        for (i in 0 until tabs.childCount) {
            val item = tabs.getChildAt(i) as? TextView ?: continue
            item.setTextColor(if (i == selected) amber else white)
            item.background = if (i == selected) rounded(Color.TRANSPARENT, 0, amber, 0) else null
        }
        content.removeAllViews()
        when (selected) {
            0 -> {
                val detail = if (exercise.id == "pull_up") {
                    "● 팁\n\n풀업은 등, 어깨, 팔 근육을 동시에 강화하는 운동입니다. 그립의 넓이와 손바닥 방향에 따라 자극되는 근육의 비중이 달라집니다. 처음부터 반복 수를 늘리기보다 매 반복의 시작과 끝 자세를 일정하게 유지하세요.\n\n● 설명\n\n- 준비\n\n1. 바를 어깨너비보다 약간 넓게 오버핸드 그립으로 잡으세요.\n2. 팔을 완전히 뻗은 상태에서 어깨를 귀에서 멀어지게 내리고, 복부와 둔근에 가볍게 힘을 주세요.\n3. 다리를 앞뒤로 흔들지 말고 발목을 겹쳐 몸통을 하나의 단단한 덩어리처럼 유지하세요.\n\n- 움직임\n\n1. 가슴을 바 쪽으로 끌어올리면서 팔꿈치를 아래·뒤 방향으로 당기세요.\n2. 턱만 내미는 대신 가슴이 올라가도록 움직이고, 어깨가 으쓱하지 않게 유지하세요.\n3. 위에서 잠깐 멈춘 뒤 팔꿈치를 천천히 펴며 시작 자세로 돌아가세요. 내려가는 구간까지 통제해야 1회가 완성됩니다.\n\n- 호흡법\n\n몸을 끌어올릴 때 숨을 내쉬고, 내려오면서 숨을 들이마시세요. 반복 중 숨을 오래 참지 말고 일정한 호흡을 유지하세요.\n\n● 주의사항\n\n1. 어깨·팔꿈치·손목에 통증이 느껴지면 즉시 중단하세요.\n2. 반동으로 몸을 차거나 허리를 과하게 젖히지 마세요.\n3. 가동범위가 무너지면 반복 수를 줄이거나 어시스티드 머신·밴드로 난도를 낮추세요."
                } else {
                    "● 팁\n\n${exercise.name}은 ${exercise.muscles} 근육을 사용하는 운동입니다. 무게를 올리는 것보다 몸통을 고정하고 매 반복의 시작·끝 자세를 일정하게 유지하는 것이 우선입니다. 목표 근육에 힘이 들어오는지 느끼면서 호흡과 속도를 일정하게 맞추세요.\n\n● 설명\n\n- 준비\n\n1. 기구와 그립을 편하게 잡고 발·골반·몸통을 안정시키세요.\n2. 어깨를 으쓱하지 말고 관절이 자연스럽게 움직이는 위치를 찾으세요.\n3. 가벼운 중량으로 1세트를 진행해 통증과 가동범위를 확인하세요.\n\n- 움직임\n\n1. 목표 근육으로 힘을 보내며 1~2초에 걸쳐 들어 올리거나 당기세요.\n2. 수축 지점에서 반동 없이 잠깐 멈추고, 무게를 놓치지 않으며 천천히 돌아오세요.\n3. 몸통이 흔들리거나 관절이 먼저 움직이면 중량·반복 수를 낮추고 궤적을 다시 맞추세요.\n4. 마지막 반복까지 같은 자세를 유지할 수 있을 때만 다음 세트의 중량을 올리세요.\n\n- 호흡법\n\n힘을 쓰는 구간에 숨을 내쉬고, 시작 위치로 돌아오며 들이마시세요. 무거운 중량에서도 숨을 오래 참지 말고 복부 긴장과 호흡을 함께 유지하세요.\n\n● 주의사항\n\n1. 날카로운 통증, 저림 또는 관절 불편감이 느껴지면 즉시 중단하세요.\n2. 빠른 반동, 과도한 중량, 허리 꺾기로 반복 수를 억지로 채우지 마세요.\n3. 자세가 무너지기 시작하면 세트를 끝내고 다음 세트는 중량이나 범위를 조절하세요."
                }
                content.addView(infoSection("", detail))
            }
            1 -> {
                val matching = routines.filter { it.exercises.any { item -> item.exercise.id == exercise.id } }
                val plannedSets = matching.sumOf { routine -> routine.exercises.filter { it.exercise.id == exercise.id }.sumOf { it.sets.size } }
                content.addView(infoSection("● 나의 기록", "이 운동이 포함된 루틴 ${matching.size}개\n계획된 총 세트 ${plannedSets}세트\n\n최근 루틴\n${matching.firstOrNull()?.name ?: "아직 기록 없음"}"))
                content.addView(space(22))
                content.addView(infoSection("● 최근 목표", matching.firstOrNull()?.exercises?.firstOrNull { it.exercise.id == exercise.id }?.sets?.firstOrNull()?.let { set ->
                    "${set.weightKg?.let { cleanWeight(it) + "kg" } ?: "체중"} · ${set.targetReps}회"
                } ?: "기록이 쌓이면 표시됩니다."))
            }
            else -> {
                val matching = routines.flatMap { it.exercises }.filter { it.exercise.id == exercise.id }
                val totalSets = matching.sumOf { it.sets.size }
                val averageRest = matching.map { it.restSeconds }.average().takeIf { !it.isNaN() }?.toInt() ?: 0
                content.addView(metricGrid(listOf(
                    "${matching.size}회" to "루틴 포함",
                    "${totalSets}세트" to "계획 세트",
                    "${averageRest}초" to "평균 휴식",
                    exercise.equipment to "기구",
                )))
                content.addView(space(20))
                content.addView(infoSection("● 분석 메모", "워치 센서 기록이 연결되면 템포·진폭·반복 일관성을 이 운동 기준으로 분석합니다."))
            }
        }
    }

    private fun infoSection(title: String, body: String): View = vertical().apply {
        if (title.isNotBlank()) addView(label(title, 15f, white, bold = true))
        if (title.isNotBlank()) addView(space(8))
        addView(label(body, 13f, white).apply { setLineSpacing(dp(1).toFloat(), 1.05f) })
    }

    private fun moveRoutineExercise(
        routine: WorkoutRoutine,
        from: Int,
        to: Int,
        active: ActiveWorkout?,
        announce: Boolean = true,
    ) {
        if (from !in routine.exercises.indices || to !in routine.exercises.indices) return
        val item = routine.exercises.removeAt(from)
        routine.exercises.add(to, item)
        active?.let {
            if (it.routine === routine) {
                it.exerciseIndex = adjustedIndexAfterMove(it.exerciseIndex, from, to)
            }
        }
        if (announce) Toast.makeText(this, "운동 순서가 반영됐어", Toast.LENGTH_SHORT).show()
    }

    private fun confirmRemoveExercise(routine: WorkoutRoutine, index: Int, active: ActiveWorkout?, onReturn: () -> Unit) {
        val exercise = routine.exercises.getOrNull(index)?.exercise ?: return
        AlertDialog.Builder(this)
            .setTitle("${exercise.name} 제거")
            .setMessage("이 루틴에서 운동을 제거할까?")
            .setNegativeButton("취소", null)
            .setPositiveButton("제거") { _, _ ->
                routine.exercises.removeAt(index)
                active?.let {
                    if (it.routine === routine) {
                        if (it.exerciseIndex > index) it.exerciseIndex -= 1
                        else if (it.exerciseIndex >= routine.exercises.size) it.exerciseIndex = max(0, routine.exercises.lastIndex)
                        it.setIndex = 0
                    }
                }
                Toast.makeText(this, "운동이 제거됐어", Toast.LENGTH_SHORT).show()
                onReturn()
            }
            .show()
    }

    private fun showExerciseAlternatives(routine: WorkoutRoutine, index: Int, onReturn: () -> Unit) {
        val current = routine.exercises.getOrNull(index)?.exercise ?: return
        val alternatives = WorkoutData.catalog
            .filter { it.id != current.id }
            .map { exercise -> exercise to exerciseSimilarity(current, exercise) }
            .sortedByDescending { it.second }
            .take(5)
        val labels = alternatives.map { (exercise, score) -> "${exercise.name}  ·  유사도 ${score}%\n${exercise.muscles} · ${exercise.equipment}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("운동 대체\n${current.name}")
            .setItems(labels) { _, which ->
                routine.exercises[index] = routine.exercises[index].copy(exercise = alternatives[which].first)
                Toast.makeText(this, "${alternatives[which].first.name}(으)로 바꿨어", Toast.LENGTH_SHORT).show()
                onReturn()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun exerciseSimilarity(source: ExerciseDefinition, target: ExerciseDefinition): Int {
        var score = 0
        if (source.bodyCategory == target.bodyCategory) score += 50
        if (source.equipment == target.equipment) score += 20
        val sourceMuscles = source.muscles.split(", ").toSet()
        val targetMuscles = target.muscles.split(", ").toSet()
        score += sourceMuscles.intersect(targetMuscles).size * 15
        return minOf(99, score + if (target.favorite) 4 else 0)
    }

    private fun showAddSheet() {
        val dialog = bottomDialog()
        val content = vertical().apply {
            setPadding(dp(22), dp(8), dp(22), dp(28))
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label("━", 22f, faint))
            addView(label("추가", 18f, white, bold = true).apply {
                gravity = Gravity.START
                setPadding(0, dp(4), 0, dp(18))
            }, matchParams(-2))
            addView(sheetOption("＋", "나만의 루틴 추가") {
                dialog.dismiss()
                showNewRoutineDialog()
            })
            addView(sheetOption("⇥", "기록에서 불러오기") {
                dialog.dismiss()
                showRecords()
            })
            addView(sheetOption("▣", "운동 계획 추가") {
                dialog.dismiss()
                showNewRoutineDialog()
            })
        }
        dialog.setContentView(content)
        showBottomDialog(dialog)
    }

    private fun sheetOption(icon: String, title: String, onClick: () -> Unit): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(3), 0, dp(3))
        isClickable = true
        setOnClickListener { onClick() }
        addView(label(icon, 18f, white).apply { gravity = Gravity.CENTER }, fixedParams(46, 36))
        addView(label(title, 13f, white), weightParams(1f, 36))
    }

    private fun showNewRoutineDialog() {
        val input = EditText(this).apply {
            hint = "루틴 이름"
            setTextColor(white)
            setHintTextColor(muted)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("나만의 루틴 추가")
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("추가") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "새 루틴" }
                val routine = WorkoutRoutine(
                    id = "custom_${System.currentTimeMillis()}",
                    name = name,
                    muscles = "운동을 추가해",
                    lastPerformed = "기록 없음",
                    exercises = mutableListOf(),
                )
                routines += routine
                selectedRoutine = routine
                showRoutine(routine)
            }
            .show()
    }

    private fun showCustomExerciseDialog(onSelect: (ExerciseDefinition) -> Unit, onReturn: () -> Unit) {
        val input = EditText(this).apply {
            hint = "운동 이름"
            setTextColor(white)
            setHintTextColor(muted)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("커스텀 운동 추가")
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("추가") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val custom = ExerciseDefinition(
                        id = "custom_${System.currentTimeMillis()}",
                        name = name,
                        muscles = "사용자 정의",
                        bodyCategory = "전체",
                        equipment = "커스텀",
                    )
                    WorkoutData.catalog += custom
                    onSelect(custom)
                    onReturn()
                }
            }
            .show()
    }

    private fun showRoutineMenu(routine: WorkoutRoutine) {
        AlertDialog.Builder(this)
            .setTitle(routine.name)
            .setItems(arrayOf("상세 보기", "운동 추가")) { _, which ->
                if (which == 0) showRoutine(routine) else {
                    openLibrary(
                        onSelect = { routine.exercises += RoutineExercise(it, mutableListOf(PlannedSet(null, 10))) },
                        onReturn = { showRoutine(routine) },
                    )
                }
            }
            .show()
    }

    private fun confirmStopWorkout() {
        AlertDialog.Builder(this)
            .setTitle("운동 중단")
            .setMessage("운동을 중단하면 기록이 저장되지 않습니다. 중단하시겠습니까?")
            .setNegativeButton("계속 운동", null)
            .setPositiveButton("종료") { _, _ ->
                val stoppedWorkout = activeWorkout
                if (stoppedWorkout != null) {
                    if (!persistActiveWorkout(stoppedWorkout)) return@setPositiveButton
                    sendWear(WearSync.PATH_COMMAND, WearSync.command("STOP", stoppedWorkout.sessionId, "", 0, 0))
                    sessionStore.clearActive()
                }
                activeWorkout = null
                activeRestStartAtMs = 0L
                activeRestEndAtMs = 0L
                pausedRestRemainingMs = 0L
                showHome()
            }
            .show()
    }

    private fun showScaffold(body: View, actions: View?, selected: RootTab) {
        root.removeAllViews()
        val column = vertical()
        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        actions?.let { column.addView(it) }
        column.addView(bottomNav(selected), matchParams(74))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showDetail(body: View, actions: View, animateFromRight: Boolean = false) {
        val frame = FrameLayout(this)
        frame.addView(body, FrameLayout.LayoutParams(-1, -1))
        frame.addView(actions, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        if (animateFromRight && root.childCount > 0) {
            if (detailTransitionRunning) {
                root.removeAllViews()
                root.addView(frame, FrameLayout.LayoutParams(-1, -1))
                detailTransitionRunning = false
                return
            }
            detailTransitionRunning = true
            val previous = root.getChildAt(root.childCount - 1)
            val widthPx = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            previous.animate().cancel()
            root.addView(frame, FrameLayout.LayoutParams(-1, -1))
            frame.translationX = widthPx.toFloat()
            frame.post {
                if (frame.parent !== root) return@post
                previous.animate()
                    .translationX(-widthPx.toFloat())
                    .setDuration(230L)
                    .setInterpolator(DecelerateInterpolator(1.7f))
                    .start()
                frame.animate()
                    .translationX(0f)
                    .setDuration(230L)
                    .setInterpolator(DecelerateInterpolator(1.7f))
                    .withEndAction {
                        if (previous.parent === root) root.removeView(previous)
                        frame.translationX = 0f
                        frame.alpha = 1f
                        detailTransitionRunning = false
                    }
                    .start()
            }
            return
        }
        detailTransitionRunning = false
        root.removeAllViews()
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showStandalone(body: View, bottomOverlay: View? = null) {
        root.removeAllViews()
        if (bottomOverlay == null) {
            root.addView(body, FrameLayout.LayoutParams(-1, -1))
            return
        }
        if (body is ScrollView) {
            body.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, dp(62))
            body.clipToPadding = false
        }
        val frame = FrameLayout(this)
        frame.addView(body, FrameLayout.LayoutParams(-1, -1))
        frame.addView(bottomOverlay, FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(8))
        })
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        updateRestUi(activeWorkout ?: return)
    }

    private fun bottomNav(selected: RootTab): View = row().apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = GradientDrawable().apply {
            setColor(Color.rgb(14, 16, 16))
            setStroke(dp(1), Color.rgb(35, 38, 38))
        }
        listOf(
            Triple(RootTab.HOME, "⌂", "홈"),
            Triple(RootTab.RECORDS, "▣", "기록"),
            Triple(RootTab.STATS, "▥", "통계"),
            Triple(RootTab.CALIBRATION, "◉", "보정"),
            Triple(RootTab.MENU, "☰", "메뉴"),
        ).forEach { (tab, icon, title) ->
            addView(vertical().apply {
                gravity = Gravity.CENTER
                setOnClickListener {
                    when (tab) {
                        RootTab.HOME -> showHome()
                        RootTab.RECORDS -> showRecords()
                        RootTab.STATS -> showStats()
                        RootTab.CALIBRATION -> showCalibration()
                        RootTab.MENU -> showMenu()
                    }
                }
                addView(label(icon, 24f, if (selected == tab) amber else muted, bold = true).apply { gravity = Gravity.CENTER })
                addView(label(title, 12f, if (selected == tab) amber else muted, bold = selected == tab).apply { gravity = Gravity.CENTER })
            }, weightParams(1f, 66))
        }
    }

    private fun progressBar(index: Int, total: Int): View = row().apply {
        repeat(total) { item ->
            addView(View(this@MainActivity).apply {
                background = rounded(if (item <= index) amberDark else surfaceRaised, 3)
            }, weightParams(1f, 5).apply {
                marginEnd = if (item == total - 1) 0 else dp(5)
            })
        }
    }

    private fun inputCard(title: String, input: EditText): View = vertical().apply {
        background = rounded(surface, 18)
        setPadding(dp(16), dp(14), dp(16), dp(10))
        addView(label(title, 13f, muted, bold = true))
        addView(input, matchParams(74))
    }

    private fun numberInput(value: String, hint: String, numeric: Boolean): EditText = EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextColor(white)
        setHintTextColor(muted)
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 0)
        background = null
        setSingleLine()
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): View = label(
        text,
        15f,
        if (selected) amber else muted,
        bold = true,
    ).apply {
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = rounded(if (selected) Color.rgb(50, 42, 18) else Color.TRANSPARENT, 20)
        setOnClickListener { onClick() }
    }

    private fun primaryButton(
        text: String,
        backgroundColor: Int,
        textColor: Int = white,
        onClick: () -> Unit,
    ) = Button(this).apply {
        this.text = text
        setTextColor(textColor)
        textSize = 18f
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        backgroundTintList = null
        background = rounded(backgroundColor, 18)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun horizontalCard() = row().apply { background = rounded(surface, 22) }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

    private fun space(sizeDp: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun weightSpace() = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    private fun bottomDialog() = BottomSheetDialog(this).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    private fun showBottomDialog(dialog: Dialog) {
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setDimAmount(0.78f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            decorView.background = rounded(surface, 28)
        }
    }

    private class BottomSheetDialog(context: Context) : Dialog(context) {
        private var downY = 0f
        private var dragging = false
        private val triggerDistance = 12f * context.resources.displayMetrics.density
        private val dismissDistance = 100f * context.resources.displayMetrics.density

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val decor = window?.decorView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.rawY - downY > triggerDistance) {
                        dragging = true
                        decor?.translationY = (event.rawY - downY).coerceAtLeast(0f)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val distance = event.rawY - downY
                    if (dragging) {
                        if (event.actionMasked == MotionEvent.ACTION_UP && distance > dismissDistance) {
                            dismiss()
                        } else {
                            decor?.animate()?.translationY(0f)?.setDuration(160)?.start()
                        }
                        dragging = false
                        return true
                    }
                    dragging = false
                }
            }
            return super.dispatchTouchEvent(event)
        }
    }

    private fun Int.toDrawable() = android.graphics.drawable.ColorDrawable(this)

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
        }

    private fun fixedParams(widthDp: Int, heightDp: Int) =
        LinearLayout.LayoutParams(if (widthDp < 0) widthDp else dp(widthDp), if (heightDp < 0) heightDp else dp(heightDp))

    private fun matchParams(heightDp: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (heightDp < 0) heightDp else dp(heightDp))

    private fun weightParams(weight: Float, heightDp: Int) =
        LinearLayout.LayoutParams(0, if (heightDp <= 0) heightDp else dp(heightDp), weight)

    private fun gridParams() = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(54)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun cleanWeight(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

    private fun timeText(totalSeconds: Long): String =
        "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)

    private fun stopTicker() {
        ticker?.let(handler::removeCallbacks)
        ticker = null
    }

    private fun sendWear(path: String, payload: String) {
        PhoneWorkoutService.send(this, path, payload)
    }

    private fun persistActiveWorkout(workout: ActiveWorkout): Boolean {
        val snapshot = WorkoutSnapshot(
            sessionId = workout.sessionId,
            routineId = workout.routine.id,
            exerciseIndex = workout.exerciseIndex,
            setIndex = workout.setIndex,
            plannedSets = workout.routine.exercises.flatMapIndexed { exerciseIndex, exercise ->
                exercise.sets.mapIndexed { setIndex, set ->
                    PlannedSetSnapshot(
                        exerciseIndex = exerciseIndex,
                        exerciseId = exercise.exercise.id,
                        setIndex = setIndex,
                        targetReps = set.targetReps,
                        weightKg = set.weightKg,
                        completed = set.completed,
                        setType = set.setType.storageValue,
                    )
                }
            },
            performedReps = workout.performedRepsByExercise.toMap(),
            phase = workout.phase,
            restEndAtMs = activeRestEndAtMs,
            pausedRestRemainingMs = pausedRestRemainingMs,
            paused = workout.paused,
            startedAtMs = workout.startedAtMs,
            pausedSinceMs = workout.pausedSinceMs,
            totalPausedMs = workout.totalPausedMs,
            lastSequence = workout.lastSequence,
            lastCompletedSetKey = workout.lastCompletedSetKey,
            captureStates = workout.captureStates.toMap(),
        )
        val saved = runCatching { sessionStore.saveActive(snapshot) }.getOrDefault(false)
        Log.i(
            EVENT_TAG,
            KCoachEventTrace.format(
                marker = if (saved) KCoachEventMarker.SNAPSHOT_SAVED else KCoachEventMarker.EVENT_FAILED,
                sessionId = workout.sessionId,
                exerciseId = workout.routine.exercises.getOrNull(workout.exerciseIndex)?.exercise?.id.orEmpty(),
                setIndex = workout.setIndex,
                detail = if (saved) "" else "snapshot_save_failed",
            ),
        )
        if (!saved) {
            activeLiveStatus?.apply {
                text = "세션 저장 실패 · 워치 명령을 보내지 않았어"
                setTextColor(amber)
            }
        }
        return saved
    }

    private fun restoreActiveWorkout(): ActiveWorkout? {
        val snapshot = sessionStore.loadActive().getOrElse { error ->
            Log.e(EVENT_TAG, "active snapshot load failed", error)
            return null
        } ?: return null
        val routine = routines.firstOrNull { it.id == snapshot.routineId } ?: run {
            Log.e(EVENT_TAG, "active snapshot routine missing id=${snapshot.routineId}")
            return null
        }
        snapshot.plannedSets.forEach { savedSet ->
            val exercise = routine.exercises.getOrNull(savedSet.exerciseIndex) ?: return@forEach
            if (exercise.exercise.id != savedSet.exerciseId) return@forEach
            exercise.sets.getOrNull(savedSet.setIndex)?.apply {
                targetReps = savedSet.targetReps
                weightKg = savedSet.weightKg
                completed = savedSet.completed
                setType = SetType.fromStorage(savedSet.setType)
            }
        }
        activeRestEndAtMs = snapshot.restEndAtMs
        pausedRestRemainingMs = snapshot.pausedRestRemainingMs
        if (snapshot.phase == WorkoutPhase.RESTING && snapshot.restEndAtMs > 0L) {
            val restSeconds = routine.exercises.getOrNull(snapshot.exerciseIndex)?.restSeconds ?: 0
            activeRestStartAtMs = snapshot.restEndAtMs - restSeconds * 1_000L
        }
        return ActiveWorkout(
            routine = routine,
            startedAtMs = snapshot.startedAtMs,
            sessionId = snapshot.sessionId,
            exerciseIndex = snapshot.exerciseIndex.coerceAtMost(routine.exercises.lastIndex.coerceAtLeast(0)),
            setIndex = snapshot.setIndex,
            performedRepsByExercise = snapshot.performedReps.toMutableMap(),
            paused = snapshot.paused,
            pausedSinceMs = snapshot.pausedSinceMs,
            totalPausedMs = snapshot.totalPausedMs,
            phase = snapshot.phase,
            lastSequence = snapshot.lastSequence,
            lastCompletedSetKey = snapshot.lastCompletedSetKey,
            captureStates = snapshot.captureStates.toMutableMap(),
        )
    }

    private fun applySnapshotToWorkout(workout: ActiveWorkout, snapshot: WorkoutSnapshot) {
        snapshot.plannedSets.forEach { savedSet ->
            val exercise = workout.routine.exercises.getOrNull(savedSet.exerciseIndex) ?: return@forEach
            if (exercise.exercise.id != savedSet.exerciseId) return@forEach
            exercise.sets.getOrNull(savedSet.setIndex)?.apply {
                targetReps = savedSet.targetReps
                weightKg = savedSet.weightKg
                completed = savedSet.completed
                setType = SetType.fromStorage(savedSet.setType)
            }
        }
        workout.exerciseIndex = snapshot.exerciseIndex
            .coerceAtMost(workout.routine.exercises.lastIndex.coerceAtLeast(0))
        workout.setIndex = snapshot.setIndex
        workout.performedRepsByExercise.clear()
        workout.performedRepsByExercise.putAll(snapshot.performedReps)
        workout.paused = snapshot.paused
        workout.pausedSinceMs = snapshot.pausedSinceMs
        workout.totalPausedMs = snapshot.totalPausedMs
        workout.phase = snapshot.phase
        workout.lastSequence = snapshot.lastSequence
        workout.lastCompletedSetKey = snapshot.lastCompletedSetKey
        workout.captureStates.clear()
        workout.captureStates.putAll(snapshot.captureStates)
        activeRestEndAtMs = snapshot.restEndAtMs
        pausedRestRemainingMs = snapshot.pausedRestRemainingMs
    }

    companion object {
        private const val EVENT_TAG = "KCoachEvent"
        private const val WATCH_PANEL_CONNECTION = "watch-panel-connection"
        private const val WATCH_PANEL_TELEMETRY = "watch-panel-telemetry"
        private const val CALIBRATION_SAVE_TIMEOUT_MS = 20_000L
    }
}
