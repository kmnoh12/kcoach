package dev.kyungmin.kcoach.phone

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import dev.kyungmin.kcoach.phone.ui.CalibrationActions
import dev.kyungmin.kcoach.phone.ui.CalibrationButtonUi
import dev.kyungmin.kcoach.phone.ui.CalibrationProfileUi
import dev.kyungmin.kcoach.phone.ui.LiveLine
import dev.kyungmin.kcoach.phone.ui.CalibrationRowUi
import dev.kyungmin.kcoach.phone.ui.CalibrationScreen
import dev.kyungmin.kcoach.phone.ui.CalibrationUi
import dev.kyungmin.kcoach.phone.ui.MyMountainScreen
import dev.kyungmin.kcoach.phone.ui.ResultSectionUi
import dev.kyungmin.kcoach.phone.ui.ResultUi
import dev.kyungmin.kcoach.phone.ui.WorkoutResultScreen
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.kyungmin.kcoach.phone.ui.BigChoiceSheet
import dev.kyungmin.kcoach.phone.ui.ChoiceSheet
import dev.kyungmin.kcoach.phone.ui.ConfirmDialog
import dev.kyungmin.kcoach.phone.ui.InputDialog
import dev.kyungmin.kcoach.phone.ui.ExerciseInfoScreen
import dev.kyungmin.kcoach.phone.ui.ExerciseInfoUi
import dev.kyungmin.kcoach.phone.ui.ExerciseRecordScreen
import dev.kyungmin.kcoach.phone.ui.ExerciseRecordUi
import dev.kyungmin.kcoach.phone.ui.KColors
import dev.kyungmin.kcoach.phone.ui.KIcon
import dev.kyungmin.kcoach.phone.ui.RecordSetUi
import dev.kyungmin.kcoach.phone.ui.LibraryActions
import dev.kyungmin.kcoach.phone.ui.LibraryEntry
import dev.kyungmin.kcoach.phone.ui.LibraryScreen
import dev.kyungmin.kcoach.phone.ui.Overlay
import dev.kyungmin.kcoach.phone.ui.OverlayHost
import dev.kyungmin.kcoach.phone.ui.OverlayState
import dev.kyungmin.kcoach.phone.ui.PickDialog
import dev.kyungmin.kcoach.phone.ui.SheetItem
import dev.kyungmin.kcoach.phone.ui.SheetOptions
import androidx.compose.ui.platform.ComposeView
import dev.kyungmin.kcoach.phone.ui.ActiveWorkoutScreen
import dev.kyungmin.kcoach.phone.ui.ExerciseUi
import dev.kyungmin.kcoach.core.TrainingLoad
import dev.kyungmin.kcoach.core.BodyLoad
import dev.kyungmin.kcoach.core.PersonalRecords
import dev.kyungmin.kcoach.core.RecordKind
import dev.kyungmin.kcoach.core.WorkoutLog
import dev.kyungmin.kcoach.core.WorkoutStats
import dev.kyungmin.kcoach.core.ExerciseHistory
import dev.kyungmin.kcoach.core.ExerciseLog
import dev.kyungmin.kcoach.core.LoggedSet
import dev.kyungmin.kcoach.core.NextTarget
import dev.kyungmin.kcoach.core.NextTargetAdvisor
import dev.kyungmin.kcoach.core.ExerciseSubstitutes
import dev.kyungmin.kcoach.core.ExerciseTaxonomy
import dev.kyungmin.kcoach.core.SetInsight
import dev.kyungmin.kcoach.core.SubstituteCandidate
import dev.kyungmin.kcoach.phone.ui.HistoryUi
import dev.kyungmin.kcoach.phone.ui.RecentLogUi
import dev.kyungmin.kcoach.phone.ui.HomeActions
import dev.kyungmin.kcoach.phone.ui.HomeUi
import dev.kyungmin.kcoach.phone.ui.RoutineRowUi2
import dev.kyungmin.kcoach.phone.ui.WeekDayUi
import dev.kyungmin.kcoach.phone.ui.HomeScreen
import dev.kyungmin.kcoach.phone.ui.Motion
import dev.kyungmin.kcoach.phone.ui.MenuActions
import dev.kyungmin.kcoach.phone.ui.MenuScreen
import dev.kyungmin.kcoach.phone.ui.RecordsActions
import dev.kyungmin.kcoach.phone.ui.RecordsScreen
import dev.kyungmin.kcoach.phone.ui.RootBottomNav
import dev.kyungmin.kcoach.phone.ui.RoutineDetailActions
import dev.kyungmin.kcoach.phone.ui.RoutineDetailScreen
import dev.kyungmin.kcoach.phone.ui.RoutineRowUi
import dev.kyungmin.kcoach.phone.ui.RunningUi
import dev.kyungmin.kcoach.phone.ui.SetField
import dev.kyungmin.kcoach.phone.ui.SetUi
import dev.kyungmin.kcoach.phone.ui.StatsScreen
import dev.kyungmin.kcoach.phone.ui.StatsUi
import dev.kyungmin.kcoach.phone.ui.UpdateUi
import dev.kyungmin.kcoach.phone.ui.WorkoutActions
import dev.kyungmin.kcoach.phone.ui.WorkoutUiModel
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
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
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

class MainActivity : ComponentActivity() {
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
        val lateDrift: Double? = null,
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

    private data class NextWorkoutTarget(
        val exerciseId: String,
        val exerciseName: String,
        val setIndex: Int,
        val targetReps: Int,
        val weightKg: Double?,
    )

    private val bg = Color.rgb(11, 12, 12)
    private val surface = Color.rgb(22, 26, 25)
    private val surfaceRaised = Color.rgb(31, 35, 34)
    private val white = Color.rgb(242, 244, 243)
    private val muted = Color.rgb(138, 147, 144)
    private val faint = Color.rgb(79, 86, 84)
    private val amber = Color.rgb(45, 212, 191)
    private val amberDark = Color.rgb(45, 212, 191)
    private val blue = Color.rgb(152, 163, 160)
    private val green = Color.rgb(45, 212, 191)

    private val routines = WorkoutData.defaultRoutines()
    private val freeExercises = mutableListOf<RoutineExercise>()
    private val handler = Handler(Looper.getMainLooper())

    private var wearLiveStatus: LiveLine? = null
    private var activeLiveStatus: LiveLine? = null
    private var restHeartStatus: TextView? = null
    private var activeRestStartAtMs = 0L
    private var activeRestEndAtMs = 0L
    private var pausedRestRemainingMs = 0L
    private var lastCompletedLiveKey: String? = null
    private var lastWearLiveSessionId = ""
    private var lastWearLiveTitle = ""
    private var lastWearLiveTelemetry = ""
    private var detailTransitionRunning = false
    private var workoutComposeView: ComposeView? = null
    private var workoutComposeWorkout: ActiveWorkout? = null
    private val workoutVersion = mutableIntStateOf(0)
    private var lastRemovedSet: Triple<RoutineExercise, Int, PlannedSet>? = null
    private var lastRestCueSecond = -1
    private val restTone by lazy { android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80) }
    private val wearReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                val payload = intent.getStringExtra("payload").orEmpty()
                when (intent.action) {
                PhoneWearListenerService.ACTION_PAIR -> {
                    wearLiveStatus?.apply {
                        if (tag != WATCH_PANEL_TELEMETRY) {
                            text = "Watch8 연결됨"
                            setTextColor(green)
                        }
                    }
                }
                PhoneWearListenerService.ACTION_WATCH_UI_READY -> {
                    watchUiReady = true
                    wearLiveStatus?.apply {
                        if (tag != WATCH_PANEL_TELEMETRY) {
                            text = "Watch8 연결됨 · 운동 시작 가능"
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
                        val liveText = when (phase) {
                            "BASELINE" -> "기준 자세 유지 중…"
                            "CONTROLLED_REPS" -> "자연스러운 왕복 $reps/5회"
                            "CALIBRATION_RESULT" -> "원본을 폰으로 전송 중…"
                            else -> null
                        }
                        if (liveText != null) {
                            calibrationButtonOverride.value = CalibrationButtonUi(liveText, calibrationButtonOverride.value?.enabled ?: true)
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
                            "Watch8 연결됨 · ${reps}회 · 심박 ${heart}bpm"
                        } else {
                            "Watch8 연결됨 · 심박 ${heart}bpm"
                        }
                        wearLiveStatus?.setTextColor(green)
                    }
                }
                PhoneWearListenerService.ACTION_COMPLETE -> {
                    val fields = WearSync.parse(payload)
                    val phase = fields.getOrNull(6).orEmpty()
                    wearLiveStatus?.takeIf { it.tag != WATCH_PANEL_TELEMETRY }?.apply {
                        text = if (phase == "COMPLETE") "Watch8 운동 데이터 수신 완료" else "Watch8 운동 종료"
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
                        calibrationButtonOverride.value = CalibrationButtonUi("원본 저장 완료 · 개인 프로필 적용 중…", false)
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
                            "보정 완료 · 유효 ${validReps}회 · 개인 프로필을 저장하고 실제 운동에 적용했어요",
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        Toast.makeText(this@MainActivity, "유효 반복이 ${validReps}회라 프로필을 만들지 않았어요 · 다시 보정해 주세요", Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this@MainActivity, "$calibratedName 보정 기록을 저장했어요", Toast.LENGTH_LONG).show()
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
                        lateDrift = intent.getDoubleExtra("lateSetDegradation", Double.NaN).takeUnless { it.isNaN() },
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
                        text = "세트 분석 완료 · 워치 ${watch.coerceAtLeast(0)}회 · 폰 ${phone}회 · 확정 ${confirmed}회"
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
                        calibrationButtonOverride.value = CalibrationButtonUi("보정 종료·저장", true)
                    }
                    activeWorkout?.let { workout ->
                        if (captureId.isNotBlank()) workout.captureStates[captureId] = CaptureState.ANALYSIS_FAILED
                        persistActiveWorkout(workout)
                    }
                    wearLiveStatus?.takeIf { it.tag != WATCH_PANEL_TELEMETRY }?.apply {
                        text = if (stage == "analysis") {
                            "센서 원본 저장 완료 · 분석만 실패"
                        } else {
                            "센서 원본 검증 실패 · 재전송 대기"
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
                    Toast.makeText(this@MainActivity, "워치 데이터 표시에 오류가 있었지만 운동 기록은 그대로 두었어요", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private lateinit var root: FrameLayout
    private val overlays = OverlayState()
    private val historyStore by lazy { ExerciseHistoryStore(applicationContext) }
    private var historyCache: ExerciseHistory? = null
    private val history: ExerciseHistory get() = historyCache ?: historyStore.load().also { historyCache = it }
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
    private val calibrationButtonOverride = mutableStateOf<CalibrationButtonUi?>(null)
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
            // Android 15+ draws edge-to-edge; keep every screen clear of both bars here
            // and consume the insets so nested Compose content doesn't pad twice.
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        addContentView(
            ComposeView(this).apply { setContent { OverlayHost(overlays) } },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
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

    @Deprecated("Screen stack is managed manually; Compose BackHandlers still get first say.")
    override fun onBackPressed() {
        // Let Compose close the keypad or rest panel before leaving the screen.
        if (onBackPressedDispatcher.hasEnabledCallbacks()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
            return
        }
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

    private val workoutLogStore by lazy { WorkoutLogStore(applicationContext) }

    private fun workoutRecords(): List<WorkoutRecord> =
        workoutLogStore.load().sortedByDescending { it.epochDay }.map {
            WorkoutRecord(LocalDate.ofEpochDay(it.epochDay).toString(), it.title, it.minutes, it.sets, it.volumeKg)
        }

    private fun weekRecords(today: LocalDate): List<WorkoutRecord> {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return workoutRecords().filter {
            val date = LocalDate.parse(it.date)
            !date.isBefore(monday) && !date.isAfter(monday.plusDays(6))
        }
    }

    private fun weekStrip(today: LocalDate): List<WeekDayUi> {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val byDate = weekRecords(today).groupBy { LocalDate.parse(it.date) }
        return listOf("월", "화", "수", "목", "금", "토", "일").mapIndexed { index, label ->
            val date = monday.plusDays(index.toLong())
            val load = byDate[date]?.sumOf { TrainingLoad.score(it.minutes, it.sets) }?.coerceAtMost(TrainingLoad.MAX_SCORE)
            WeekDayUi(label, load, date == today, date.isAfter(today))
        }
    }

    private fun showHome() {
        stopTicker()
        if (activeWorkout == null) previewWorkout = null
        currentTab = RootTab.HOME
        currentScreen = Screen.ROOT

        val running = activeWorkout
        val runningUi = running?.let { workout ->
            val current = workout.routine.exercises.getOrNull(workout.exerciseIndex)
            val detail = current?.let { "${it.exercise.name} · ${it.sets.completedWorkSetCount()}/${it.sets.workSetCount()}세트" }.orEmpty()
            RunningUi(workout.routine.name, detail, workout.paused) { workoutElapsedSeconds(workout) }
        }
        val rows = routines.map { RoutineRowUi2(it, TrainingLoad.daysAgo(it.lastPerformed)) }
        val homeUi = HomeUi(
            week = weekStrip(LocalDate.now()),
            weekCount = weekRecords(LocalDate.now()).size,
            rows = rows,
            longestRest = rows.filter { it.daysAgo != null }.maxByOrNull { it.daysAgo!! },
        )
        val actions = HomeActions(
            openRoutine = { routine ->
                selectedRoutine = routine
                showRoutine(routine, active = activeWorkout?.takeIf { it.routine === routine }, animateIn = true)
            },
            routineMenu = ::showRoutineMenu,
            freeWorkout = ::showFreeWorkout,
            add = ::showAddSheet,
            openRunning = { showActiveWorkout() },
            togglePause = {
                running?.let(::toggleWorkoutPause)
                showHome()
            },
            stop = ::confirmStopWorkout,
        )
        val home = ComposeView(this).apply {
            setContent { HomeScreen(homeUi, runningUi, actions) }
        }
        showScaffold(home, null, RootTab.HOME)
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

        val reload = { showRoutine(routine, reorderMode, active, onExit) }
        val rows = routine.exercises.mapIndexed { index, item ->
            RoutineRowUi(
                key = index,
                name = item.exercise.name,
                subtitle = "${item.exercise.muscles} · ${item.sets.workSetCount()}세트 · 총 ${item.sets.sumOf { it.targetReps }}회",
                thumbnail = { exerciseAnimationView(item.exercise, 58, compact = true) },
                doneSets = active?.takeIf { it.routine === routine }?.let { item.sets.completedWorkSetCount() },
                plannedSets = item.sets.workSetCount(),
            )
        }
        val actions = RoutineDetailActions(
            back = { onExit?.invoke() ?: onBackPressed() },
            open = { index ->
                if (active != null && active.routine === routine) showWorkoutExercise(active, index)
                else showExercisePreview(routine, index)
            },
            info = { index -> showExerciseInfo(routine.exercises[index].exercise, reload) },
            menu = { index -> showExerciseMenu(routine, index, active, reload) },
            move = { from, to -> moveRoutineExercise(routine, from, to, active, announce = false) },
            moveFinished = {
                active?.takeIf { it.routine === routine }?.let(::persistActiveWorkout)
                Toast.makeText(this, "순서를 바꿨어요", Toast.LENGTH_SHORT).show()
            },
            primary = { if (active != null) showMyMountainDecision() else showStartGuide(routine) },
            addExercise = {
                openLibrary(
                    onSelect = {
                        routine.exercises += RoutineExercise(
                            exercise = it,
                            sets = mutableListOf(PlannedSet(null, 10)),
                        )
                    },
                    onReturn = { showRoutine(routine) },
                )
            },
        )
        val body = ComposeView(this).apply {
            setContent {
                RoutineDetailScreen(
                    title = routine.name,
                    rows = rows,
                    primaryLabel = if (active != null) "운동 완료" else "시작",
                    primaryIcon = if (active != null) KIcon.Flag else KIcon.Play,
                    showHandle = reorderMode,
                    actions = actions,
                )
            }
        }
        showDetail(body, null, animateFromRight = animateIn)
    }

    private fun freeRoutine() = WorkoutRoutine("free", "자유운동", "선택 운동", "오늘", freeExercises)

    private fun showFreeWorkout() {
        stopTicker()
        currentScreen = Screen.FREE
        val reload = { showFreeWorkout() }
        val rows = freeExercises.mapIndexed { index, item ->
            RoutineRowUi(
                key = index,
                name = item.exercise.name,
                subtitle = "${item.exercise.muscles} · ${item.sets.workSetCount()}세트 · 총 ${item.sets.sumOf { it.targetReps }}회",
                thumbnail = { exerciseAnimationView(item.exercise, 58, compact = true) },
            )
        }
        val actions = RoutineDetailActions(
            back = { onBackPressed() },
            open = { index -> showExerciseRecord(freeRoutine(), index, null, reload) },
            info = { index -> showExerciseInfo(freeExercises[index].exercise, reload) },
            menu = { index -> showExerciseMenu(freeRoutine(), index, null, reload) },
            move = { from, to -> freeExercises.add(to, freeExercises.removeAt(from)) },
            moveFinished = { Toast.makeText(this, "순서를 바꿨어요", Toast.LENGTH_SHORT).show() },
            primary = { showStartGuide(freeRoutine()) },
            addExercise = {
                openLibrary(
                    onSelect = {
                        if (freeExercises.none { row -> row.exercise.id == it.id }) {
                            freeExercises += RoutineExercise(it, mutableListOf(PlannedSet(null, 10)))
                        }
                    },
                    onReturn = reload,
                )
            },
        )
        val body = ComposeView(this).apply {
            setContent {
                RoutineDetailScreen(
                    title = "7월 27일, 밤 운동",
                    rows = rows,
                    primaryLabel = if (freeExercises.isNotEmpty()) "시작" else null,
                    primaryIcon = KIcon.Play,
                    showHandle = false,
                    actions = actions,
                    placeholderHeight = if (freeExercises.isEmpty()) 410.dp else 250.dp,
                    addFilled = freeExercises.isEmpty(),
                )
            }
        }
        showDetail(body, null)
    }

    private fun openLibrary(onSelect: (ExerciseDefinition) -> Unit, onReturn: () -> Unit) {
        stopTicker()
        currentScreen = Screen.LIBRARY
        returnFromLibrary = onReturn
        val actions = LibraryActions(
            back = onReturn,
            custom = { showCustomExerciseDialog(onSelect, onReturn) },
            add = {
                onSelect(it)
                Toast.makeText(this, "${it.name} 추가됨", Toast.LENGTH_SHORT).show()
            },
        )
        val frequent = setOf(
            "pull_up", "lat_pull_down", "flat_bench", "incline_bench",
            "dumbbell_lateral_raise", "leg_press", "cable_pushdown",
        )
        showStandalone(ComposeView(this).apply {
            setContent {
                val entries = WorkoutData.catalog.map { item ->
                    val (order, title) = ExerciseTaxonomy.section(item.bodyCategory, item.pattern)
                    LibraryEntry(item, order, title, ExerciseTaxonomy.bodyRank(item.bodyCategory))
                }
                LibraryScreen(entries, frequent, actions) { exerciseAnimationView(it, 56, compact = true) }
            }
        })
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
        val actions = RecordsActions(
            openResult = { showWorkoutResult(lastWorkoutResult) },
            monthChanged = { recordMonth = it },
        )
        val title = lastWorkoutResult?.title
        showScaffold(ComposeView(this).apply {
            setContent { RecordsScreen(workoutRecords(), recordMonth, title, actions) }
        }, null, RootTab.RECORDS)
    }

    private fun showWorkoutResult(result: WorkoutResultUi?) {
        if (result == null) return showRecords()
        stopTicker()
        currentTab = RootTab.RECORDS
        currentScreen = Screen.ROOT
        val sections = if (result.analyses.isEmpty()) {
            listOf(ResultSectionUi("분석 대기", listOf("센서 기록" to "워치에서 받는 중", "확정 반복수" to "센서 기록을 받으면 표시돼요")))
        } else {
            result.analyses.values.sortedWith(compareBy<SetAnalysisUi> { it.exerciseName }.thenBy { it.setIndex }).map { analysis ->
                val confidenceText = "%.0f%%".format(Locale.US, analysis.confidence * 100.0)
                val metricRows = mutableListOf(
                    "측정 방식" to "${analysis.capabilityTier} · ${analysis.trackingStatus.ifBlank { analysis.trackingMode }} · ${analysis.trackingProfileId}",
                    "워치 측정값" to "${analysis.watchReps}회",
                    "폰 재분석" to if (analysis.trackingMode == "MANUAL_REQUIRED") "자동 산출 안 함" else "${analysis.phoneReps}회",
                    "확정 반복수" to if (analysis.trackingMode == "MANUAL_REQUIRED") "수동 입력 필요" else "${analysis.confirmedReps}회",
                    "분석 신뢰도" to confidenceText,
                )
                (analysis.wristMotionSpeed ?: analysis.averageSpeed)?.let { metricRows += "손목 관측 속도" to "%.2f rad/s".format(Locale.US, it) }
                (analysis.wristMotionRange ?: analysis.relativeRom)?.let { metricRows += "손목 관측 범위" to "%.0f%%".format(Locale.US, it * 100.0) }
                analysis.tempoConsistency?.let { metricRows += "템포 일관성" to "$it/100" }
                analysis.signalQuality?.let { metricRows += "신호 품질" to "%.0f%%".format(Locale.US, it * 100.0) }
                ResultSectionUi("${analysis.exerciseName} · 세트 ${analysis.setIndex + 1}", metricRows)
            }
        }
        val ui = ResultUi(
            title = result.title,
            subtitle = "${result.durationMinutes}분 · ${result.completedSets}세트 · 세션 ${result.sessionId.takeLast(8)}",
            sections = sections,
            records = if (lastWorkoutResult?.sessionId == result.sessionId) lastWorkoutRecords else emptyList(),
            footnote = "확정 반복수는 워치 표시값이 아니라 폰이 센서 기록을 다시 분석한 결과를 기준으로 해요.",
        )
        showStandalone(ComposeView(this).apply {
            setContent { WorkoutResultScreen(ui, back = { onBackPressed() }, toRecords = { showRecords() }) }
        })
    }

    private fun showStats() {
        stopTicker()
        currentTab = RootTab.STATS
        currentScreen = Screen.ROOT
        showScaffold(ComposeView(this).apply { val stats = statsUi()
            setContent { StatsScreen(stats) } }, null, RootTab.STATS)
    }

    private fun startOrStopCalibration() {
        val session = calibrationSessionId
        if (session != null) {
            if (calibrationStopRequested) return
            calibrationStopRequested = true
            calibrationButtonOverride.value = CalibrationButtonUi("보정 원본 저장 중…", false)
            sendWear(WearSync.PATH_COMMAND, WearSync.command("STOP", session, "", 0, 0))
            Toast.makeText(this, "워치 센서 기록을 저장하는 중이에요", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "연결된 Watch8을 찾지 못했어요", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                beginCalibrationSession()
            }
            .addOnFailureListener {
                Toast.makeText(this, "워치 연결 상태를 확인하지 못했어요", Toast.LENGTH_LONG).show()
            }
    }

    private fun beginCalibrationSession() {
        val exercise = WorkoutData.catalog.firstOrNull { it.id == selectedCalibrationExerciseId } ?: return
        val tracking = ExerciseTrackingProfiles.resolve(exercise.id, exercise.name)
        if (tracking.mode == TrackingMode.MANUAL_REQUIRED) {
            Toast.makeText(this, "이 운동은 손목 센서로 측정할 수 없어서 수동 기록만 지원해요", Toast.LENGTH_LONG).show()
            return
        }
        val newSession = "calibration-${exercise.id}-${System.currentTimeMillis()}"
        calibrationSessionId = newSession
        cancelCalibrationSaveTimeout()
        activeCalibrationExerciseId = exercise.id
        calibrationStopRequested = false
        calibrationButtonOverride.value = CalibrationButtonUi("보정 종료·저장", true)
        sendWear(
            WearSync.PATH_COMMAND,
            WearSync.richCommand(
                "START", newSession, exercise.id, exercise.name,
                0, 5, null, 0,
            ),
        )
        Toast.makeText(this, "시작 자세를 2초 유지한 뒤, 진동이 오면 평소 범위와 속도로 5회 해 주세요", Toast.LENGTH_LONG).show()
    }

    private fun scheduleCalibrationSaveTimeout(sessionId: String) {
        cancelCalibrationSaveTimeout()
        val timeout = Runnable {
            if (calibrationSessionId != sessionId || !calibrationStopRequested) return@Runnable
            calibrationSessionId = null
            activeCalibrationExerciseId = null
            calibrationStopRequested = false
            calibrationButtonOverride.value = CalibrationButtonUi("보정 전송 지연 · 다시 시작", true, textAmber = true)
            Toast.makeText(this, "20초 동안 워치 기록을 받지 못했어요 · 연결을 확인하고 다시 보정해 주세요", Toast.LENGTH_LONG).show()
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
        calibrationButtonOverride.value = null
        val profileUi = personalProfile(selected.id)?.let { profile ->
            val applied = PersonalCalibrationBuilder.personalizedTrackingProfile(profile, selectedProfile)
            val axis = listOf("X", "Y", "Z").getOrElse(profile.dominantAxis) { "?" }
            val created = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(profile.createdAtMs))
            val naturalRange = Math.toDegrees(profile.minimumRom / 2.0).coerceAtLeast(0.0)
            CalibrationProfileUi(
                "저장된 개인 프로필 · 실 운동 적용 중",
                listOf(
                    "저장 시각" to created,
                    "유효 반복" to "${profile.validRepCount}회",
                    "학습 회전 범위" to "편도 약 ${"%.0f".format(naturalRange)}° 이상",
                    "학습 반복 시간" to "${"%.2f".format(profile.medianRepDurationMs / 1_000.0)}초",
                    "주 회전축" to "$axis 축",
                    "실 운동 허용 시간" to "${"%.2f".format(applied.minimumRepMs / 1_000.0)}–${"%.2f".format(applied.maximumRepMs / 1_000.0)}초",
                    "적용 위치" to "워치 실시간 + 폰 재분석",
                    "가중치" to "개인 80% + 일반 20%",
                ),
                "시작 자세 2초는 센서의 기준축과 흔들림 기준을 잡는 단계예요. 손목 센서만으로 팔꿈치나 몸 전체 자세까지 판단하진 않아요.",
            )
        }
        val ui = CalibrationUi(
            selectedName = selected.name,
            status = if (manualRequired) "손목 센서 직접 관측 불가 · 수동 기록" else calibrationLabel(selected.id),
            statusAmber = manualRequired,
            instructions = instructions,
            manualRequired = manualRequired,
            profile = profileUi,
            rows = WorkoutData.catalog.map { exercise ->
                val profile = ExerciseTrackingProfiles.resolve(exercise.id, exercise.name)
                CalibrationRowUi(
                    id = exercise.id,
                    name = exercise.name,
                    muscles = exercise.muscles,
                    status = if (profile.mode == TrackingMode.MANUAL_REQUIRED) {
                        "수동 기록만"
                    } else {
                        val status = calibrationLabel(exercise.id)
                        if (exercise.id == selectedCalibrationExerciseId) "선택됨 · $status" else status
                    },
                    selected = exercise.id == selectedCalibrationExerciseId,
                    thumbnail = { exerciseAnimationView(exercise, 58, compact = true) },
                )
            },
        )
        val actions = CalibrationActions(
            toggle = ::startOrStopCalibration,
            deleteProfile = { confirmDeletePersonalProfile(selected.id, selected.name) },
            select = { id ->
                selectedCalibrationExerciseId = id
                showCalibration()
            },
        )
        showScaffold(ComposeView(this).apply {
            setContent {
                CalibrationScreen(
                    ui = ui,
                    sessionActive = { calibrationSessionId != null },
                    button = {
                        calibrationButtonOverride.value ?: CalibrationButtonUi(
                            if (manualRequired) "이 운동은 수동 기록만 지원"
                            else if (calibrationSessionId == null) "워치 연결 후 5회 보정 시작"
                            else if (calibrationStopRequested) "보정 원본 저장 중…"
                            else "보정 종료·저장",
                            !calibrationStopRequested,
                        )
                    },
                    actions = actions,
                )
            }
        }, null, RootTab.CALIBRATION)
    }

    private fun confirmDeletePersonalProfile(exerciseId: String, exerciseName: String) {
        showPopup { dismiss ->
            ConfirmDialog(
                title = "보정된 기록 삭제",
                message = "$exerciseName 보정 기록을 폰과 워치에서 삭제할까요?\n" +
                    "삭제하면 이 운동은 일반 기준으로 측정돼요.",
                negative = "취소",
                positive = "삭제",
                dismiss = dismiss,
            ) {
                val prefs = getSharedPreferences("wear-sync", MODE_PRIVATE)
                prefs.edit()
                    .remove("personalProfile:$exerciseId")
                    .remove("calibration:$exerciseId:status")
                    .remove("calibration:$exerciseId:rawSavedAt")
                    .remove("calibration:$exerciseId:completedAt")
                    .remove("calibration:$exerciseId:error")
                    .apply()
                sendWear(WearSync.PATH_PERSONAL_PROFILE_DELETE, exerciseId)
                Toast.makeText(this, "$exerciseName 보정 기록을 삭제했어요", Toast.LENGTH_LONG).show()
                showCalibration()
            }
        }
    }

    private fun showSheet(content: @Composable (dismiss: () -> Unit) -> Unit) = overlays.show(Overlay(true, content))

    private fun showPopup(content: @Composable (dismiss: () -> Unit) -> Unit) = overlays.show(Overlay(false, content))

    private fun showMenu() {
        stopTicker()
        currentTab = RootTab.MENU
        currentScreen = Screen.ROOT
        val actions = MenuActions(
            checkUpdate = { report ->
                updateManager.checkAndDownload { state ->
                    runOnUiThread { report(UpdateUi(state.message, state.readyToInstall)) }
                }
            },
            install = { updateManager.requestInstall() },
            openProbe = { startActivity(Intent(this, ProbeActivity::class.java)) },
        )
        showScaffold(ComposeView(this).apply {
            setContent { MenuScreen(BuildConfig.VERSION_NAME, actions) }
        }, null, RootTab.MENU)
    }

    private fun showStartGuide(
        routine: WorkoutRoutine,
        startExerciseIndex: Int = 0,
        startSetIndex: Int = 0,
        onStarted: ((ActiveWorkout) -> Unit)? = null,
    ) {
        fun start() {
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
            val workout = activeWorkout ?: return
            if (!persistActiveWorkout(workout)) return
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
                    activeLiveStatus?.text = "워치 연결 중 · 워치 앱을 열어 주세요"
                    wearLiveStatus?.apply {
                        text = "워치 연결 중 · 워치 앱을 열어 주세요"
                        setTextColor(amber)
                    }
                }
            }
            if (onStarted != null) onStarted(workout) else showActiveWorkout()
        }
        showSheet { dismiss ->
            BigChoiceSheet(
                eyebrow = "▶", eyebrowColor = KColors.Accent,
                title = "운동 시작",
                body = "운동을 시작할까요?\n\n워치가 연결돼 있으면 세트와 심박수가 함께 기록돼요.",
                bodyColor = KColors.White,
                cancel = "취소", confirm = "시작",
                dismiss = dismiss, onCancel = {}, onConfirm = ::start,
                filledButtons = false,
            )
        }
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
            bumpWorkoutUi()
        } else {
            activeWorkout?.let(::persistActiveWorkout)
            bumpWorkoutUi()
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
        if (preview != null) previewWorkout = preview
        val workout = activeWorkout ?: previewWorkout ?: return showHome()
        if (workout.exerciseIndex >= workout.routine.exercises.size) {
            showMyMountainDecision()
            return
        }
        val reuse = currentScreen == Screen.ACTIVE &&
            workoutComposeView?.parent === root &&
            workoutComposeWorkout === workout
        if (reuse) {
            // Same screen: re-render in place so only what changed animates.
            bumpWorkoutUi()
        } else {
            stopTicker()
            currentScreen = Screen.ACTIVE
            workoutComposeWorkout = workout
            val (liveTitle, liveTelemetry) = buildLivePanel(workout)
            val composeView = ComposeView(this).apply {
                setContent {
                    workoutVersion.intValue
                    val current = workoutComposeWorkout ?: return@setContent
                    ActiveWorkoutScreen(
                        model = buildWorkoutModel(current),
                        actions = workoutActions,
                        liveTitle = liveTitle,
                        liveTelemetry = liveTelemetry,
                        animationView = { index ->
                            exerciseAnimationView(current.routine.exercises[index].exercise, 190)
                        },
                    )
                }
            }
            workoutComposeView = composeView
            root.removeAllViews()
            root.addView(composeView, FrameLayout.LayoutParams(-1, -1))
            updateWearConnectionStatus()
        }
        val isPreview = activeWorkout == null
        val routineExercise = workout.routine.exercises[workout.exerciseIndex]
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
        if (isPreview || ticker != null) return
        ticker = object : Runnable {
            override fun run() {
                try {
                    if (activeWorkout !== workout || currentScreen != Screen.ACTIVE) {
                        ticker = null
                        return
                    }
                    if (activeRestEndAtMs > 0L) {
                        val leftMs = activeRestEndAtMs - System.currentTimeMillis()
                        restCue(leftMs)
                        if (leftMs <= 0L) {
                            ticker = null
                            restCue(0L)
                            continueAfterRest(workout)
                            return
                        }
                    }
                    handler.postDelayed(this, if (activeRestEndAtMs > 0L) 100 else 1_000)
                } catch (error: Exception) {
                    reportWorkoutUiError("active_ticker", error)
                    ticker = null
                }
            }
        }.also { handler.post(it) }
    }

    private fun bumpWorkoutUi() {
        workoutVersion.intValue++
    }

    /** 3·2·1 short cues and a longer one at zero, once each per rest. */
    private fun restCue(leftMs: Long) {
        val second = if (leftMs <= 0L) 0 else ((leftMs + 999L) / 1000L).toInt()
        if (second > 3 || second == lastRestCueSecond || activeRestEndAtMs <= 0L && second != 0) return
        lastRestCueSecond = second
        val vibrator = getSystemService(android.os.Vibrator::class.java)
        if (second == 0) {
            vibrator?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 180, 90, 260), -1))
            runCatching { restTone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 220) }
        } else {
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            runCatching { restTone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 90) }
        }
    }

    private fun buildLivePanel(workout: ActiveWorkout): Pair<LiveLine, LiveLine> {
        val cached = lastWearLiveSessionId == workout.sessionId &&
            lastWearLiveTitle.isNotBlank() && lastWearLiveTelemetry.isNotBlank()
        val tag = if (cached) WATCH_PANEL_TELEMETRY else WATCH_PANEL_CONNECTION
        val title = LiveLine(if (cached) lastWearLiveTitle else "Watch8 연결 확인 중", tag)
        val telemetry = LiveLine(if (cached) lastWearLiveTelemetry else "워치 운동 상태 수신 대기 중", tag)
        if (!cached) telemetry.setTextColor(muted)
        activeLiveStatus = telemetry
        wearLiveStatus = title
        return title to telemetry
    }

    private fun effectiveRestSeconds(routineExercise: RoutineExercise, setIndex: Int): Int =
        routineExercise.sets.getOrNull(setIndex)?.restSeconds ?: routineExercise.restSeconds

    private fun volumeUnit(workout: ActiveWorkout) = if (workout.usePounds) 2.20462 else 1.0

    /** Remembers what was really done today, so the next session can compare and build on it. */
    private var newRecords = mutableListOf<Pair<String, String>>()
    private var lastWorkoutRecords: List<Pair<String, String>> = emptyList()

    private fun recordHistory(workout: ActiveWorkout) {
        newRecords = mutableListOf()
        val today = LocalDate.now().toEpochDay()
        workout.routine.exercises.forEach { item ->
            val done = item.sets.filter { it.completed }.map { LoggedSet(it.weightKg, it.targetReps) }
            if (done.isEmpty()) return@forEach
            val log = ExerciseLog(item.exercise.id, today, done)
            PersonalRecords.detect(history, log).forEach { hit ->
                val label = if (hit.kind == RecordKind.HEAVIEST_WEIGHT) "최고 중량" else "최고 볼륨"
                val unit = volumeUnit(workout)
                val value = "%s → %s".format(cleanWeight(hit.previous * unit), cleanWeight(hit.value * unit)) + if (workout.usePounds) "lb" else "kg"
                newRecords += "${item.exercise.name} · $label" to value
            }
            historyCache = historyStore.add(log)
        }
        lastWorkoutRecords = newRecords.toList()
    }

    private fun recordWorkoutLog(workout: ActiveWorkout) {
        val done = workout.routine.exercises.flatMap { item -> item.sets.filter { it.completed } }
        if (done.isEmpty()) return
        workoutLogStore.add(
            WorkoutLog(
                epochDay = LocalDate.now().toEpochDay(),
                title = workout.routine.name,
                minutes = (workoutElapsedSeconds(workout) / 60L).toInt(),
                sets = done.size,
                volumeKg = done.sumOf { (it.weightKg ?: 0.0) * it.targetReps }.toInt(),
            ),
        )
    }

    private fun statsUi(): StatsUi {
        val today = LocalDate.now()
        val month = YearMonth.from(today)
        val logs = workoutLogStore.load()
        val inMonth = logs.filter { LocalDate.ofEpochDay(it.epochDay).let { d -> YearMonth.from(d) == month } }
        val bodyOf = WorkoutData.catalog.associate { it.id to it.bodyCategory }
        val weekSets = BodyLoad.setsByPart(history.all, bodyOf::get, today.toEpochDay())
        if (inMonth.isEmpty()) return StatsUi("${month.monthValue}월", emptyList(), emptyList(), weekSets)
        val totals = WorkoutStats.between(logs, month.atDay(1).toEpochDay(), month.atEndOfMonth().toEpochDay())
        return StatsUi(
            "${month.monthValue}월",
            listOf(
                "${totals.workouts}회" to "운동",
                "${totals.minutes}분" to "총 시간",
                "${totals.sets}세트" to "총 세트",
                "%,dkg".format(totals.volumeKg) to "총 볼륨",
            ),
            WorkoutStats.byTitle(inMonth).map { (title, sets) -> title to "${sets}세트" },
            weekSets,
        )
    }

    private fun plannedReps(item: RoutineExercise): Int =
        item.sets.filter { it.setType != SetType.WARMUP }.maxOfOrNull { it.targetReps } ?: 10

    private fun historyUi(item: RoutineExercise, workout: ActiveWorkout): HistoryUi? {
        val last = history.last(item.exercise.id) ?: return null
        val unitLabel = if (workout.usePounds) "lb" else "kg"
        val lastLine = "지난번 " + last.sets.take(4).joinToString(" · ") { set ->
            if (set.weightKg == null) "${set.reps}회" else "${formatActiveWeight(set.weightKg, workout.usePounds)}×${set.reps}"
        } + if (last.sets.size > 4) " …" else ""
        val next = NextTargetAdvisor.advise(last, plannedReps(item))
        val workSets = item.sets.filter { it.setType != SetType.WARMUP }
        val alreadyApplied = when (next) {
            is NextTarget.RaiseWeight -> workSets.isNotEmpty() && workSets.all { (it.weightKg ?: 0.0) >= next.toKg }
            is NextTarget.AddRep -> workSets.sumOf { it.targetReps } > last.totalReps
            is NextTarget.Hold -> false
        }
        if (alreadyApplied) {
            return HistoryUi(lastLine, (last.volumeKg * volumeUnit(workout)).toInt(), null, null, false)
        }
        val (text, applicable) = when (next) {
            is NextTarget.RaiseWeight -> "${formatActiveWeight(next.toKg, workout.usePounds)}${unitLabel}로 올리기" to true
            is NextTarget.AddRep -> "전체 반복 1회 더" to true
            is NextTarget.Hold -> "중량 유지" to false
        }
        return HistoryUi(lastLine, (last.volumeKg * volumeUnit(workout)).toInt(), text, next.reason, applicable)
    }

    private fun applySuggestion(item: RoutineExercise) {
        val last = history.last(item.exercise.id) ?: return
        val pending = item.sets.filter { !it.completed && it.setType != SetType.WARMUP }
        when (val next = NextTargetAdvisor.advise(last, plannedReps(item))) {
            is NextTarget.RaiseWeight -> pending.forEach { it.weightKg = next.toKg }
            is NextTarget.AddRep -> pending.lastOrNull()?.let { it.targetReps += 1 }
            is NextTarget.Hold -> Unit
        }
    }

    /** Replaces the not-yet-started sets of an exercise with what was done last time. */
    private fun loadLastSets(item: RoutineExercise) {
        val last = history.last(item.exercise.id) ?: return
        if (item.sets.any { it.completed }) return
        item.sets.clear()
        last.sets.forEach { item.sets += PlannedSet(it.weightKg, it.reps) }
    }

    private fun buildWorkoutModel(workout: ActiveWorkout): WorkoutUiModel {
        val next = if (activeWorkout === workout) nextWorkoutTarget(workout) else null
        val nextText = next?.takeIf { it.exerciseId.isNotEmpty() }?.let { target ->
            val exercise = workout.routine.exercises.firstOrNull { it.exercise.id == target.exerciseId }
            val setLabel = exercise?.sets?.workSetLabel(target.setIndex)?.let { if (it == "W") "웜업" else "${it}세트" }.orEmpty()
            val weight = target.weightKg?.let { "${formatActiveWeight(it, workout.usePounds)}${if (workout.usePounds) "lb" else "kg"} × " }.orEmpty()
            "다음: ${target.exerciseName} $setLabel · $weight${target.targetReps}회"
        }
        return WorkoutUiModel(
            routineName = workout.routine.name,
            isPreview = activeWorkout !== workout,
            paused = workout.paused,
            startedAtMs = workout.startedAtMs,
            totalPausedMs = workout.totalPausedMs,
            pausedSinceMs = workout.pausedSinceMs,
            exerciseIndex = workout.exerciseIndex,
            usePounds = workout.usePounds,
            restStartAtMs = activeRestStartAtMs,
            restEndAtMs = activeRestEndAtMs,
            pausedRestRemainingMs = pausedRestRemainingMs,
            nextSetText = nextText,
            exercises = workout.routine.exercises.mapIndexed { exerciseIndex, routineExercise ->
                val currentSet = if (exerciseIndex == workout.exerciseIndex) workout.setIndex
                else workout.setIndexByExercise[routineExercise.exercise.id]
                    ?: routineExercise.sets.indexOfFirst { !it.completed }.coerceAtLeast(0)
                ExerciseUi(
                    id = routineExercise.exercise.id,
                    name = routineExercise.exercise.name,
                    muscles = routineExercise.exercise.muscles,
                    weighted = routineExercise.exercise.equipment != "맨몸",
                    restSeconds = routineExercise.restSeconds,
                    targetReps = routineExercise.sets.sumOf { it.targetReps },
                    performedReps = workout.performedRepsByExercise[routineExercise.exercise.id] ?: 0,
                    completedWorkSets = routineExercise.sets.completedWorkSetCount(),
                    workSetCount = routineExercise.sets.workSetCount(),
                    currentSetIndex = currentSet,
                    volumeDone = (routineExercise.sets.filter { it.completed }.sumOf { (it.weightKg ?: 0.0) * it.targetReps } * volumeUnit(workout)).toInt(),
                    volumeTarget = (routineExercise.sets.sumOf { (it.weightKg ?: 0.0) * it.targetReps } * volumeUnit(workout)).toInt(),
                    history = historyUi(routineExercise, workout),
                    sets = routineExercise.sets.mapIndexed { setIndex, set ->
                        SetUi(
                            uid = System.identityHashCode(set),
                            label = routineExercise.sets.workSetLabel(setIndex),
                            weightText = formatActiveWeight(set.weightKg, workout.usePounds),
                            reps = set.targetReps,
                            completed = set.completed,
                            warmup = set.setType == SetType.WARMUP,
                            grip = set.gripType,
                            restSeconds = effectiveRestSeconds(routineExercise, setIndex),
                            customRest = set.restSeconds != null,
                            insight = workout.analyses["${routineExercise.exercise.id}:$setIndex"]
                                ?.let { SetInsight.describe(it.tempoConsistency, it.lateDrift) },
                            previous = history.last(routineExercise.exercise.id)?.sets?.getOrNull(setIndex)?.let { last ->
                                val weight = last.weightKg
                                if (weight == null) "${last.reps}회" else "${formatActiveWeight(weight, workout.usePounds)}×${last.reps}"
                            },
                        )
                    },
                )
            },
        )
    }

    private val workoutActions = object : WorkoutActions {
        private fun workout() = activeWorkout ?: previewWorkout
        private fun exercise(index: Int) = workout()?.routine?.exercises?.getOrNull(index)

        override fun onBack() {
            val workout = workout() ?: return showHome()
            if (activeWorkout == null) {
                previewWorkout = null
                showRoutine(workout.routine)
            } else {
                showHome()
            }
        }

        override fun onMenu() {
            workout()?.let(::showWorkoutMenu)
        }

        override fun onExerciseInfo(exerciseIndex: Int) {
            val exercise = exercise(exerciseIndex) ?: return
            showExerciseInfo(exercise.exercise) { showActiveWorkout() }
        }

        override fun onPageSettled(exerciseIndex: Int) {
            val workout = workout() ?: return
            if (exerciseIndex != workout.exerciseIndex) showWorkoutExercise(workout, exerciseIndex)
        }

        override fun onEditValue(exerciseIndex: Int, setIndex: Int, field: SetField, value: String, applyAll: Boolean) {
            val workout = workout() ?: return
            val routineExercise = exercise(exerciseIndex) ?: return
            val targets = if (applyAll) routineExercise.sets.filter { !it.completed } + listOfNotNull(routineExercise.sets.getOrNull(setIndex))
            else listOfNotNull(routineExercise.sets.getOrNull(setIndex))
            targets.distinct().forEach { set ->
                when (field) {
                    SetField.WEIGHT -> {
                        val parsed = value.toDoubleOrNull()
                        set.weightKg = when {
                            value.isBlank() -> 0.0
                            parsed == null -> set.weightKg
                            workout.usePounds -> parsed / 2.20462
                            else -> parsed
                        }
                    }
                    SetField.REPS -> set.targetReps = value.toIntOrNull()?.coerceAtLeast(0) ?: 0
                }
            }
            workout.selectedEditSetIndex = setIndex
            bumpWorkoutUi()
        }

        override fun onEditFinished() {
            // Persists and, while working, re-sends the current set target to the watch.
            activeWorkout?.let(::persistActiveWorkout)
            showActiveWorkout()
        }

        override fun onToggleSet(exerciseIndex: Int, setIndex: Int) {
            val workout = workout() ?: return
            val routineExercise = exercise(exerciseIndex) ?: return
            val plannedSet = routineExercise.sets.getOrNull(setIndex) ?: return
            if (!plannedSet.completed) {
                if (activeWorkout !== workout) {
                    showStartGuide(
                        routine = workout.routine,
                        startExerciseIndex = exerciseIndex,
                        startSetIndex = setIndex,
                    ) { startedWorkout ->
                        val startedExercise = startedWorkout.routine.exercises
                            .getOrNull(startedWorkout.exerciseIndex) ?: return@showStartGuide
                        val startedSet = startedExercise.sets.getOrNull(setIndex) ?: return@showStartGuide
                        completeWorkoutSet(startedWorkout, startedExercise, setIndex, startedSet)
                    }
                    return
                }
                completeWorkoutSet(workout, routineExercise, setIndex, plannedSet)
            } else {
                plannedSet.completed = false
                val current = workout.performedRepsByExercise[routineExercise.exercise.id] ?: 0
                workout.performedRepsByExercise[routineExercise.exercise.id] =
                    (current - plannedSet.targetReps).coerceAtLeast(0)
                workout.phase = WorkoutPhase.WORKING
                if (workout.lastCompletedSetKey == "${workout.sessionId}:${routineExercise.exercise.id}:$setIndex") {
                    workout.lastCompletedSetKey = null
                    lastCompletedLiveKey = null
                }
                persistActiveWorkout(workout)
                bumpWorkoutUi()
            }
        }

        override fun onSetMenu(exerciseIndex: Int, setIndex: Int) {
            val workout = workout() ?: return
            val routineExercise = exercise(exerciseIndex) ?: return
            workout.selectedEditSetIndex = setIndex
            showSetMenu(workout, routineExercise, setIndex)
        }

        override fun onApplySuggestion(exerciseIndex: Int) {
            val routineExercise = exercise(exerciseIndex) ?: return
            applySuggestion(routineExercise)
            activeWorkout?.let(::persistActiveWorkout)
            bumpWorkoutUi()
        }

        override fun onAddSet(exerciseIndex: Int) {
            val routineExercise = exercise(exerciseIndex) ?: return
            val source = routineExercise.sets.lastOrNull() ?: PlannedSet(null, 10)
            routineExercise.sets += PlannedSet(source.weightKg, source.targetReps, gripType = source.gripType, restSeconds = source.restSeconds)
            activeWorkout?.let(::persistActiveWorkout)
            bumpWorkoutUi()
        }

        override fun onRemoveSet(exerciseIndex: Int, setIndex: Int) {
            val workout = workout() ?: return
            val routineExercise = exercise(exerciseIndex) ?: return
            if (routineExercise.sets.size <= 1 || setIndex !in routineExercise.sets.indices) return
            val removed = routineExercise.sets.removeAt(setIndex)
            lastRemovedSet = Triple(routineExercise, setIndex, removed)
            workout.selectedEditSetIndex = workout.selectedEditSetIndex.coerceAtMost(routineExercise.sets.lastIndex)
            if (exerciseIndex == workout.exerciseIndex) {
                workout.setIndex = workout.setIndex.coerceAtMost(routineExercise.sets.lastIndex)
            }
            activeWorkout?.let(::persistActiveWorkout)
            bumpWorkoutUi()
        }

        override fun onUndoRemoveSet() {
            val (routineExercise, index, set) = lastRemovedSet ?: return
            lastRemovedSet = null
            routineExercise.sets.add(index.coerceAtMost(routineExercise.sets.size), set)
            activeWorkout?.let(::persistActiveWorkout)
            bumpWorkoutUi()
        }

        override fun onSetRest(exerciseIndex: Int, setIndex: Int) {
            val routineExercise = exercise(exerciseIndex) ?: return
            val set = routineExercise.sets.getOrNull(setIndex) ?: return
            showChoiceSheet(
                "이 세트 뒤 휴식",
                listOf("기본값" to "운동 기본 휴식 ${routineExercise.restSeconds}초") +
                    listOf(30, 45, 60, 90, 120, 150, 180).map { "${it}초" to "" },
            ) { value ->
                set.restSeconds = value.removeSuffix("초").toIntOrNull()
                activeWorkout?.let(::persistActiveWorkout)
                bumpWorkoutUi()
            }
        }

        override fun onAllSetsDone(exerciseIndex: Int) {
            val workout = workout() ?: return
            val routineExercise = exercise(exerciseIndex) ?: return
            routineExercise.sets.forEach { it.completed = true }
            workout.performedRepsByExercise[routineExercise.exercise.id] = routineExercise.sets.sumOf { it.targetReps }
            activeWorkout?.let(::persistActiveWorkout)
            bumpWorkoutUi()
        }

        override fun onAdjustRest(deltaSeconds: Int) {
            val workout = workout() ?: return
            val routineExercise = exercise(workout.exerciseIndex) ?: return
            adjustRestDuration(workout, routineExercise, deltaSeconds)
        }

        override fun onRestPreset(seconds: Int) {
            val workout = workout() ?: return
            val routineExercise = exercise(workout.exerciseIndex) ?: return
            adjustRestDuration(workout, routineExercise, seconds - routineExercise.restSeconds)
        }

        override fun onSkipRest() {
            val workout = activeWorkout ?: return
            if (activeRestEndAtMs <= 0L) return
            stopTicker()
            continueAfterRest(workout)
        }

        override fun onTogglePause() {
            activeWorkout?.let(::toggleWorkoutPause)
        }

        override fun onStop() {
            if (activeWorkout != null) confirmStopWorkout()
        }

        override fun onToggleUnit() {
            val workout = workout() ?: return
            workout.usePounds = !workout.usePounds
            bumpWorkoutUi()
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
        val firstLine = "$exerciseName · 세트 ${setIndex + 1}" +
            if (personalizedTracking) " · 개인보정 적용" else ""
        val secondLine = when (phase) {
            "POSITIONING" -> "팔을 준비 자세로 옮겨 주세요 · 움직이는 동안은 세지 않아요"
            "PREPARING" -> "자세 고정 · 흔들림 없이 2초 후 시작"
            "RESTING" -> "휴식 중 · ${reps}/${targetReps}회$weightText · 심박 ${heartRate}bpm"
            "SET_COMPLETE" -> "세트 완료 · ${reps}회$weightText · 심박 ${heartRate}bpm"
            else -> "${reps}/${targetReps}회$weightText · 심박 ${heartRate}bpm"
        }
        lastWearLiveSessionId = sessionId
        lastWearLiveTitle = firstLine
        lastWearLiveTelemetry = secondLine
        activeLiveStatus?.apply {
            text = secondLine
            tag = WATCH_PANEL_TELEMETRY
            setTextColor(green)
        }
        restHeartStatus?.text = "워치 심박수  ${heartRate} bpm"
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
        val restSeconds = effectiveRestSeconds(routineExercise, setIndex)
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
        val uiReused = currentScreen == Screen.ACTIVE && workoutComposeView?.parent === root
        showRest(restSeconds, restEndAtMs)
        Log.i(
            EVENT_TAG,
            KCoachEventTrace.format(
                KCoachEventMarker.ACTIVE_UI_UPDATED,
                sessionId,
                exerciseId,
                setIndex,
                detail = "uiReused=$uiReused",
            ),
        )
    }

    private fun updateWearConnectionStatus() {
        if (wearLiveStatus == null) return
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                wearLiveStatus?.takeIf { it.tag == WATCH_PANEL_CONNECTION }?.apply {
                    text = if (nodes.isNotEmpty()) {
                        "Watch8 연결됨 · 운동 시작 가능"
                    } else {
                        "Watch8 연결 안 됨"
                    }
                    setTextColor(if (nodes.isNotEmpty()) green else muted)
                }
            }
            .addOnFailureListener {
                wearLiveStatus?.takeIf { it.tag == WATCH_PANEL_CONNECTION }?.apply {
                    text = "Watch8 연결 안 됨"
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

    private fun formatActiveWeight(weightKg: Double?, usePounds: Boolean): String {
        if (weightKg == null) return "체중"
        val value = if (usePounds) weightKg * 2.20462 else weightKg
        return cleanWeight(value)
    }

    private fun completeWorkoutSet(
        workout: ActiveWorkout,
        routineExercise: RoutineExercise,
        setIndex: Int,
        plannedSet: PlannedSet,
    ) {
        val restSeconds = effectiveRestSeconds(routineExercise, setIndex)
        val restEndAtMs = System.currentTimeMillis() + restSeconds * 1_000L
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
        activeRestStartAtMs = restEndAtMs - restSeconds * 1_000L
        activeRestEndAtMs = restEndAtMs
        if (activeWorkout === workout) {
            val next = nextWorkoutTarget(workout)
            sendWear(
                WearSync.PATH_COMMAND,
                WearSync.richCommand(
                    "SET_COMPLETE", workout.sessionId, next.exerciseId, next.exerciseName,
                    next.setIndex, next.targetReps, next.weightKg, restSeconds, restEndAtMs,
                ),
            )
        }
        showRest(restSeconds, restEndAtMs)
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
        showSheet { dismiss ->
            SheetOptions(
                routineExercise.exercise.name,
                listOf(
                    SheetItem(KIcon.Sliders, if (workout.usePounds) "킬로그램으로 단위 변경" else "파운드로 단위 변경") {
                        workout.usePounds = !workout.usePounds
                        dismiss()
                        showActiveWorkout()
                    },
                    SheetItem(KIcon.Clock, "휴식 시간") {
                        showChoiceSheet("휴식 시간", listOf("30초" to "짧은 휴식", "60초" to "일반 휴식", "90초" to "근비대 기본", "120초" to "고중량 휴식", "160초" to "긴 휴식")) { value ->
                            routineExercise.restSeconds = value.removeSuffix("초").toInt()
                            showActiveWorkout()
                        }
                    },
                    SheetItem(KIcon.Swap, "운동 대체") {
                        dismiss()
                        showExerciseAlternatives(workout.routine, workout.exerciseIndex) { showActiveWorkout() }
                    },
                ) + if (history.last(routineExercise.exercise.id) != null && routineExercise.sets.none { it.completed }) listOf(
                    SheetItem(KIcon.Reload, "지난 세트 불러오기") {
                        dismiss()
                        loadLastSets(routineExercise)
                        activeWorkout?.let(::persistActiveWorkout)
                        showActiveWorkout(forceRebuild = true)
                    },
                ) else emptyList(),
            )
        }
    }

    private fun showSetMenu(workout: ActiveWorkout, routineExercise: RoutineExercise, setIndex: Int) {
        val set = routineExercise.sets.getOrNull(setIndex) ?: return
        val setLabel = routineExercise.sets.workSetLabel(setIndex).let { if (it == "W") "웜업" else "${it}세트" }
        showSheet { dismiss ->
            SheetOptions(
                "${routineExercise.exercise.name} $setLabel · ${set.targetReps}회",
                listOf(
                    SheetItem(KIcon.Sliders, "세트 타입\n${set.setType.displayName}") {
                        showChoiceSheet("세트 타입", listOf("일반" to "일반 세트", "웜업" to "근육을 준비하기 위한 워밍업", "슈퍼" to "두 운동을 쉬지 않고 연속으로", "드랍" to "휴식 없이 무게를 낮춰 이어서", "실패" to "실패까지 간 세트", "보조" to "도움을 받아 끝낸 세트")) { value ->
                            set.setType = SetType.fromStorage(value)
                            showActiveWorkout()
                        }
                    },
                    SheetItem(KIcon.Sliders, "그립 타입\n${set.gripType}") {
                        showChoiceSheet("그립 타입", listOf("노멀" to "표준 그립", "오버핸드" to "손바닥이 몸 반대쪽을 향하는 그립", "언더핸드" to "손바닥이 몸 쪽을 향하는 그립", "뉴트럴" to "양손이 서로 마주 보는 그립", "와이드" to "어깨 너비보다 넓은 손 배치", "클로즈" to "어깨 너비보다 좁은 손 배치", "얼터네이트" to "한 손은 오버핸드, 다른 한 손은 언더핸드")) { value ->
                            set.gripType = value
                            showActiveWorkout()
                        }
                    },
                    SheetItem(KIcon.Stats, "운동자각도\n${set.rpe ?: "선택 안 함"}") {
                        showChoiceSheet("RPE", listOf("선택 안 함" to "", "10" to "더는 못 해요", "9.5" to "1개 정도 더 할 수 있을 것 같아요", "9" to "확실히 1개 더 할 수 있어요", "8.5" to "아마 2개 더 할 수 있어요", "8" to "확실히 2개 더 할 수 있어요", "7" to "꽤 할 만했어요", "6" to "웜업 정도예요")) { value ->
                            set.rpe = value.takeUnless { it == "선택 안 함" }
                            showActiveWorkout()
                        }
                    },
                    SheetItem(KIcon.Minus, "세트 삭제") {
                        dismiss()
                        if (routineExercise.sets.size > 1) routineExercise.sets.removeAt(setIndex)
                        workout.selectedEditSetIndex = workout.selectedEditSetIndex.coerceAtMost(routineExercise.sets.lastIndex)
                        showActiveWorkout()
                    },
                    SheetItem(KIcon.Plus, "이전 세트 추가") {
                        dismiss()
                        routineExercise.sets.add(setIndex, PlannedSet(set.weightKg, set.targetReps))
                        showActiveWorkout()
                    },
                ),
            )
        }
    }

    private fun showChoiceSheet(title: String, items: List<Pair<String, String>>, onSelect: (String) -> Unit) {
        showSheet { dismiss ->
            ChoiceSheet(title, items) { value ->
                dismiss()
                onSelect(value)
            }
        }
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
        lastRestCueSecond = -1
        if (!persistActiveWorkout(workout)) return
        try {
            showActiveWorkout(forceRebuild = !wasActiveScreen)
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
            recordHistory(completedWorkout)
            recordWorkoutLog(completedWorkout)
            sendWear(WearSync.PATH_COMMAND, WearSync.command("COMPLETE", completedWorkout.sessionId, "", 0, 0))
            sessionStore.clearActive()
        }
        activeWorkout = null
        activeRestStartAtMs = 0L
        activeRestEndAtMs = 0L
        pausedRestRemainingMs = 0L
        showSheet { dismiss ->
            BigChoiceSheet(
                eyebrow = "웨이트 완료", eyebrowColor = KColors.Accent,
                title = "마이마운틴도 할까요?",
                body = "오늘 컨디션에 따라 정해요.\n건너뛰어도 루틴은 완료로 기록돼요.",
                bodyColor = KColors.Muted,
                cancel = "건너뛰기", confirm = "시작",
                dismiss = dismiss,
                onCancel = { showWorkoutResult(lastWorkoutResult) },
                onConfirm = { showMyMountain() },
                filledButtons = true,
            )
        }
    }

    private fun showMyMountain() {
        currentScreen = Screen.MY_MOUNTAIN
        showStandalone(ComposeView(this).apply {
            setContent {
                MyMountainScreen(
                    back = { onBackPressed() },
                    start = { Toast.makeText(this@MainActivity, "워치가 연결되면 심박수와 함께 기록돼요", Toast.LENGTH_LONG).show() },
                    finish = { showRecords() },
                )
            }
        })
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

        val record = ExerciseRecordUi(
            name = routineExercise.exercise.name,
            muscles = routineExercise.exercise.muscles,
            targetTotal = routineExercise.sets.sumOf { it.targetReps },
            performed = active?.performedRepsByExercise?.get(routineExercise.exercise.id) ?: 0,
            sets = routineExercise.sets.mapIndexed { setIndex, set ->
                RecordSetUi(
                    setIndex,
                    set.targetReps,
                    active != null && setIndex < active.completedSets && active.routine === routine && active.exerciseIndex > index,
                )
            },
            restSeconds = routineExercise.restSeconds,
            recent = history.recent(routineExercise.exercise.id, 4).map { log ->
                RecentLogUi(
                    date = LocalDate.ofEpochDay(log.epochDay).let { "${it.monthValue}월 ${it.dayOfMonth}일" },
                    lines = log.sets.mapIndexed { i, set ->
                        val weight = set.weightKg
                        "${i + 1}  ${if (weight == null) "" else cleanWeight(weight) + "kg × "}${set.reps}회"
                    }.take(5).joinToString("\n"),
                )
            },
            best = buildList {
                history.heaviestSet(routineExercise.exercise.id)?.let { (day, set) ->
                    add("최고 중량" to "${cleanWeight(set.weightKg!!)}kg × ${set.reps}회")
                }
                history.bestVolume(routineExercise.exercise.id)?.let { add("최고 볼륨" to "%,dkg".format(it.volumeKg.toInt())) }
            },
        )
        showStandalone(ComposeView(this).apply {
            setContent {
                ExerciseRecordScreen(
                    record,
                    back = onReturn,
                    openInfo = { showExerciseInfo(routineExercise.exercise) { showExerciseRecord(routine, index, active, onReturn) } },
                    animation = { height -> exerciseAnimationView(routineExercise.exercise, height) },
                )
            }
        })
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
            // Decode per view: AnimatedImageDrawables that share a constant state also
            // share one native decoder, and two running at once (pager neighbours)
            // abort the process.
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources, animationRes))
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

    private fun showExerciseMenu(
        routine: WorkoutRoutine,
        index: Int,
        active: ActiveWorkout?,
        onReturn: () -> Unit,
    ) {
        val exercise = routine.exercises.getOrNull(index)?.exercise ?: return
        showSheet { dismiss ->
            SheetOptions(
                exercise.name,
                listOf(
                    SheetItem(KIcon.Play, "운동 애니메이션") { dismiss(); showExerciseInfo(exercise, onReturn) },
                    SheetItem(KIcon.Info, "운동 정보") { dismiss(); showExerciseInfo(exercise, onReturn) },
                    SheetItem(KIcon.Link, "슈퍼세트 설정") {},
                    SheetItem(KIcon.Trash, "운동 삭제") { dismiss(); confirmRemoveExercise(routine, index, active, onReturn) },
                    SheetItem(KIcon.Swap, "운동 대체") { dismiss(); showExerciseAlternatives(routine, index, onReturn) },
                ),
            )
        }
    }

    private fun exerciseDescription(exercise: ExerciseDefinition): String =
        if (exercise.id == "pull_up") {
            "● 팁\n\n풀업은 등, 어깨, 팔 근육을 동시에 강화하는 운동이에요. 그립의 넓이와 손바닥 방향에 따라 자극되는 근육의 비중이 달라져요. 처음부터 반복 수를 늘리기보다 매 반복의 시작과 끝 자세를 일정하게 유지하세요.\n\n● 설명\n\n- 준비\n\n1. 바를 어깨너비보다 약간 넓게 오버핸드 그립으로 잡으세요.\n2. 팔을 완전히 뻗은 상태에서 어깨를 귀에서 멀어지게 내리고, 복부와 둔근에 가볍게 힘을 주세요.\n3. 다리를 앞뒤로 흔들지 말고 발목을 겹쳐 몸통을 하나의 단단한 덩어리처럼 유지하세요.\n\n- 움직임\n\n1. 가슴을 바 쪽으로 끌어올리면서 팔꿈치를 아래·뒤 방향으로 당기세요.\n2. 턱만 내미는 대신 가슴이 올라가도록 움직이고, 어깨가 으쓱하지 않게 유지하세요.\n3. 위에서 잠깐 멈춘 뒤 팔꿈치를 천천히 펴며 시작 자세로 돌아가세요. 내려가는 구간까지 통제해야 1회가 완성돼요.\n\n- 호흡법\n\n몸을 끌어올릴 때 숨을 내쉬고, 내려오면서 숨을 들이마시세요. 반복 중 숨을 오래 참지 말고 일정한 호흡을 유지하세요.\n\n● 주의사항\n\n1. 어깨·팔꿈치·손목에 통증이 느껴지면 즉시 중단하세요.\n2. 반동으로 몸을 차거나 허리를 과하게 젖히지 마세요.\n3. 가동범위가 무너지면 반복 수를 줄이거나 어시스티드 머신·밴드로 난도를 낮추세요."
        } else {
            "● 팁\n\n${exercise.name}은 ${exercise.muscles} 근육을 사용하는 운동이에요. 무게를 올리는 것보다 몸통을 고정하고 매 반복의 시작·끝 자세를 일정하게 유지하는 것이 우선이에요. 목표 근육에 힘이 들어오는지 느끼면서 호흡과 속도를 일정하게 맞추세요.\n\n● 설명\n\n- 준비\n\n1. 기구와 그립을 편하게 잡고 발·골반·몸통을 안정시키세요.\n2. 어깨를 으쓱하지 말고 관절이 자연스럽게 움직이는 위치를 찾으세요.\n3. 가벼운 무게로 1세트를 진행해 통증과 가동범위를 확인하세요.\n\n- 움직임\n\n1. 목표 근육으로 힘을 보내며 1~2초에 걸쳐 들어 올리거나 당기세요.\n2. 수축 지점에서 반동 없이 잠깐 멈추고, 무게를 놓치지 않으며 천천히 돌아오세요.\n3. 몸통이 흔들리거나 관절이 먼저 움직이면 무게와 반복 수를 낮추고 궤적을 다시 맞추세요.\n4. 마지막 반복까지 같은 자세를 유지할 수 있을 때만 다음 세트의 무게를 올리세요.\n\n- 호흡법\n\n힘을 쓰는 구간에 숨을 내쉬고, 시작 위치로 돌아오며 들이마시세요. 무거울 때도 숨을 오래 참지 말고 복부 긴장과 호흡을 함께 유지하세요.\n\n● 주의사항\n\n1. 날카로운 통증, 저림 또는 관절 불편감이 느껴지면 즉시 중단하세요.\n2. 빠른 반동, 과한 무게, 허리 꺾기로 반복 수를 억지로 채우지 마세요.\n3. 자세가 무너지기 시작하면 세트를 끝내고 다음 세트는 무게나 범위를 조절하세요."
        }

    private fun showExerciseInfo(exercise: ExerciseDefinition, onReturn: () -> Unit) {
        stopTicker()
        currentScreen = Screen.EXERCISE_INFO
        returnFromExerciseInfo = onReturn

        val matching = routines.filter { it.exercises.any { item -> item.exercise.id == exercise.id } }
        val plannedSets = matching.sumOf { routine -> routine.exercises.filter { it.exercise.id == exercise.id }.sumOf { it.sets.size } }
        val used = routines.flatMap { it.exercises }.filter { it.exercise.id == exercise.id }
        val averageRest = used.map { it.restSeconds }.average().takeIf { !it.isNaN() }?.toInt() ?: 0
        val info = ExerciseInfoUi(
            name = exercise.name,
            muscles = exercise.muscles,
            description = exerciseDescription(exercise),
            myRecord = listOf(
                "● 나의 기록" to "이 운동이 포함된 루틴 ${matching.size}개\n계획된 총 세트 ${plannedSets}세트\n\n최근 루틴\n${matching.firstOrNull()?.name ?: "아직 기록 없음"}",
                "● 최근 목표" to (matching.firstOrNull()?.exercises?.firstOrNull { it.exercise.id == exercise.id }?.sets?.firstOrNull()?.let { set ->
                    "${set.weightKg?.let { cleanWeight(it) + "kg" } ?: "체중"} · ${set.targetReps}회"
                } ?: "기록이 쌓이면 표시돼요."),
            ),
            stats = listOf(
                "${used.size}회" to "루틴 포함",
                "${used.sumOf { it.sets.size }}세트" to "계획 세트",
                "${averageRest}초" to "평균 휴식",
                exercise.equipment to "기구",
            ),
            analysisNote = "워치 센서 기록이 연결되면 템포, 진폭, 반복 일관성을 이 운동 기준으로 분석해요.",
        )
        showStandalone(ComposeView(this).apply {
            setContent {
                ExerciseInfoScreen(info, back = onReturn) { height -> exerciseAnimationView(exercise, height) }
            }
        })
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
        if (announce) Toast.makeText(this, "순서를 바꿨어요", Toast.LENGTH_SHORT).show()
    }

    private fun confirmRemoveExercise(routine: WorkoutRoutine, index: Int, active: ActiveWorkout?, onReturn: () -> Unit) {
        val exercise = routine.exercises.getOrNull(index)?.exercise ?: return
        showPopup { dismiss ->
            ConfirmDialog("${exercise.name} 삭제", "이 루틴에서 운동을 삭제할까요?", "취소", "삭제", dismiss) {
                routine.exercises.removeAt(index)
                active?.let {
                    if (it.routine === routine) {
                        if (it.exerciseIndex > index) it.exerciseIndex -= 1
                        else if (it.exerciseIndex >= routine.exercises.size) it.exerciseIndex = max(0, routine.exercises.lastIndex)
                        it.setIndex = 0
                    }
                }
                Toast.makeText(this, "운동을 삭제했어요", Toast.LENGTH_SHORT).show()
                onReturn()
            }
        }
    }

    private fun showExerciseAlternatives(routine: WorkoutRoutine, index: Int, onReturn: () -> Unit) {
        val current = routine.exercises.getOrNull(index)?.exercise ?: return
        fun ExerciseDefinition.candidate() = SubstituteCandidate(
            id, muscles.split(", "), bodyCategory, equipment, pattern, favorite,
        )
        val byId = WorkoutData.catalog.associateBy { it.id }
        val alternatives = ExerciseSubstitutes
            .rank(current.candidate(), WorkoutData.catalog.map { it.candidate() })
            .mapNotNull { byId[it.id] }
        val labels = alternatives.map { "${it.name}\n${it.muscles} · ${it.equipment}" }
        showPopup { dismiss ->
            PickDialog("운동 대체\n${current.name}", labels, dismiss) { which ->
                routine.exercises[index] = routine.exercises[index].copy(exercise = alternatives[which])
                Toast.makeText(this, "${alternatives[which].name}(으)로 바꿨어요", Toast.LENGTH_SHORT).show()
                onReturn()
            }
        }
    }

    private fun showAddSheet() {
        showSheet { dismiss ->
            SheetOptions(
                "추가",
                listOf(
                    SheetItem(KIcon.Plus, "나만의 루틴 추가") { dismiss(); showNewRoutineDialog() },
                    SheetItem(KIcon.Reload, "기록에서 불러오기") { dismiss(); showRecords() },
                    SheetItem(KIcon.Records, "운동 계획 추가") { dismiss(); showNewRoutineDialog() },
                ),
            )
        }
    }

    private fun showNewRoutineDialog() {
        showPopup { dismiss ->
            InputDialog("나만의 루틴 추가", "루틴 이름", dismiss) { typed ->
                val routine = WorkoutRoutine(
                    id = "custom_${System.currentTimeMillis()}",
                    name = typed.ifBlank { "새 루틴" },
                    muscles = "운동을 추가해 주세요",
                    lastPerformed = "기록 없음",
                    exercises = mutableListOf(),
                )
                routines += routine
                selectedRoutine = routine
                showRoutine(routine)
            }
        }
    }

    private fun showCustomExerciseDialog(onSelect: (ExerciseDefinition) -> Unit, onReturn: () -> Unit) {
        showPopup { dismiss ->
            InputDialog("커스텀 운동 추가", "운동 이름", dismiss) { name ->
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
        }
    }

    private fun showRoutineMenu(routine: WorkoutRoutine) {
        showSheet { dismiss ->
            SheetOptions(
                routine.name,
                listOf(
                    SheetItem(KIcon.Menu, "상세 보기") { dismiss(); showRoutine(routine) },
                    SheetItem(KIcon.Plus, "운동 추가") {
                        dismiss()
                        openLibrary(
                            onSelect = { routine.exercises += RoutineExercise(it, mutableListOf(PlannedSet(null, 10))) },
                            onReturn = { showRoutine(routine) },
                        )
                    },
                ),
            )
        }
    }

    private fun confirmStopWorkout() {
        showPopup { dismiss ->
            ConfirmDialog("운동을 종료할까요?", "지금까지의 기록은 저장되지 않아요.", "계속 운동", "종료", dismiss) {
                val stoppedWorkout = activeWorkout
                if (stoppedWorkout != null) {
                    if (!persistActiveWorkout(stoppedWorkout)) return@ConfirmDialog
                    sendWear(WearSync.PATH_COMMAND, WearSync.command("STOP", stoppedWorkout.sessionId, "", 0, 0))
                    sessionStore.clearActive()
                }
                activeWorkout = null
                activeRestStartAtMs = 0L
                activeRestEndAtMs = 0L
                pausedRestRemainingMs = 0L
                showHome()
            }
        }
    }

    private fun showScaffold(body: View, actions: View?, selected: RootTab) {
        val switchingTab = currentScreen == Screen.ROOT && root.childCount > 0
        root.removeAllViews()
        val column = vertical()
        column.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        actions?.let { column.addView(it) }
        column.addView(bottomNav(selected), matchParams(74))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        if (switchingTab) {
            body.alpha = 0f
            body.animate().alpha(1f).setDuration(Motion.UI_MS.toLong()).setInterpolator(uiInterpolator).start()
        }
    }

    private fun showDetail(body: View, actions: View?, animateFromRight: Boolean = false) {
        val frame = FrameLayout(this)
        frame.addView(body, FrameLayout.LayoutParams(-1, -1))
        actions?.let { frame.addView(it, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)) }
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
                    .setDuration(Motion.PANEL_MS.toLong())
                    .setInterpolator(panelInterpolator)
                    .start()
                frame.animate()
                    .translationX(0f)
                    .setDuration(Motion.PANEL_MS.toLong())
                    .setInterpolator(panelInterpolator)
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
    }

    private val panelInterpolator = PathInterpolator(0.785f, 0.135f, 0.15f, 0.86f)
    private val uiInterpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)

    private fun bottomNav(selected: RootTab): View = ComposeView(this).apply {
        setContent {
            RootBottomNav(selected.ordinal) { index ->
                when (RootTab.values()[index]) {
                    RootTab.HOME -> showHome()
                    RootTab.RECORDS -> showRecords()
                    RootTab.STATS -> showStats()
                    RootTab.CALIBRATION -> showCalibration()
                    RootTab.MENU -> showMenu()
                }
            }
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
                text = "세션 저장에 실패했어요 · 워치 명령을 보내지 않았어요"
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
