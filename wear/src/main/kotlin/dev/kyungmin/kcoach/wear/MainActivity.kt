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
    private lateinit var pager: WatchPager
    private lateinit var editPage: EditPageView
    private lateinit var auxPage: AuxPageView
    private lateinit var pullGlow: PullGlowView
    private var restEditSegment = 0
    private var lastWorkingReps = 0
    private var lastWorkingWeight: Double? = null
    private var wasResting = false
    private var restFlashText = ""
    private var restFlashUntilMs = 0L
    private var countingPausedForEdit = false
    private var rotaryRemainder = 0f
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

    private lateinit var restRing: RestProgressRing
    private val manualOutbox by lazy { ManualSetOutbox(this) }
    private val workoutControls by lazy { WatchWorkoutOutbox(this) }
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
        face.restGo.setOnClickListener { requestWorkoutControl() }
        face.jog.onStep = { delta -> adjustRest(delta) }
        face.hint.setOnClickListener { if (runtimePhase == "START_DIRECTION_WAITING") bypassStartDirection() else if (face.noticeAction != null) face.noticeAction?.invoke() }
        editPage = EditPageView(this).apply {
            onDelta = { field, delta -> editValue(field, delta) }
            onKeypad = { field -> keypadFor(field) }
            onFinish = { finishCurrentSet() }
            onSegment = { restEditSegment = it; updateUi() }
        }
        auxPage = AuxPageView(this)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(face, FrameLayout.LayoutParams(-1, -1)) }
        restRing = RestProgressRing().apply { visibility = View.GONE; isClickable = false }
        frame.addView(restRing, FrameLayout.LayoutParams(-1, -1))
        pullGlow = PullGlowView(this).apply { visibility = View.GONE; isClickable = false }
        frame.addView(pullGlow, FrameLayout.LayoutParams(-1, -1))
        shadowText = label("", 11f, WatchWorkoutFace.MUTED).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        frame.addView(shadowText, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(30) })
        pager = WatchPager(this, frame, editPage, auxPage).apply {
            pullTarget = face
            onPullArmed = { armed -> pullGlow.armed = armed; face.restGo.alpha = if (armed) 0f else 1f }
            onPullCommit = { requestWorkoutControl() }
            onPage = { page -> onPageChanged(page) }
        }
        return pager
    }

    /** Entering the editor stops counting so lifting the wrist to look at the watch is not counted as a rep. */
    private fun onPageChanged(page: Int) {
        val editing = page == -1 && running && !resting && !paused
        if (editing != countingPausedForEdit) {
            countingPausedForEdit = editing
            sendWorkoutCommand(if (editing) "COUNTING_PAUSE" else "COUNTING_RESUME")
        }
        updateUi()
    }

    private fun sendWorkoutCommand(command: String, configure: Intent.() -> Unit = {}) {
        if (sessionId.isBlank()) return
        startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, command)
            .putExtra("session", sessionId).putExtra("setId", setId).putExtra("attemptId", attemptId).apply(configure))
    }

    private fun bypassStartDirection() {
        startService(Intent(this, WearWorkoutService::class.java).putExtra(WearWorkoutService.EXTRA_COMMAND, "START_DIRECTION_BYPASS")
            .putExtra("session", sessionId).putExtra("setId", setId).putExtra("attemptId", attemptId))
    }

    /** Rest time in ten second steps, from the bar, its ends, or the bezel. */
    private fun adjustRest(delta: Int) {
        if (!resting || paused) return
        val now = System.currentTimeMillis()
        val newEnd = (restEndAtMs + delta * 1_000L).coerceAtLeast(now)
        val applied = ((newEnd - restEndAtMs) / 1_000L).toInt()
        if (applied == 0) return
        restEndAtMs = newEnd
        restSeconds = (restSeconds + applied).coerceIn(1, 1_800)
        restFlashText = if (applied > 0) "+${applied}초" else "${applied}초".replace("-", "−")
        restFlashUntilMs = now + 900
        sendWorkoutCommand("REST_ADJUST") { putExtra("deltaSeconds", applied) }
        updateUi()
    }

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_SCROLL && event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER)) {
            // Clockwise reports a negative scroll; one detent is one ten-second step on the rest screen, nothing elsewhere.
            rotaryRemainder += -event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL)
            val steps = rotaryRemainder.toInt()
            if (steps != 0) {
                rotaryRemainder -= steps
                if (resting && pager.page == 0) {
                    window.decorView.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) 18 /* ROTARY_SCROLL_TICK */ else android.view.HapticFeedbackConstants.CLOCK_TICK)
                    adjustRest(steps * 10)
                }
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun editValue(field: EditPageView.Field, delta: Int) {
        if (resting) {
            val which = if (restEditSegment == 0) "PREVIOUS" else "NEXT"
            if (field == EditPageView.Field.REPS) {
                val current = if (restEditSegment == 0) lastWorkingReps else targetReps
                val next = (current + delta).coerceIn(0, 9_999)
                if (restEditSegment == 0) lastWorkingReps = next else targetReps = next
                sendWorkoutCommand("EDIT_SET") { putExtra("which", which); putExtra("reps", next); putExtra("weightKg", Double.NaN) }
            } else {
                val current = (if (restEditSegment == 0) lastWorkingWeight else weightKg) ?: 0.0
                val next = (current + delta).coerceIn(0.0, 999.0)
                if (restEditSegment == 0) lastWorkingWeight = next else weightKg = next
                sendWorkoutCommand("EDIT_SET") { putExtra("which", which); putExtra("reps", -1); putExtra("weightKg", next) }
            }
        } else if (field == EditPageView.Field.REPS) {
            val next = ((displayedCount() ?: reps) + delta).coerceIn(0, 9_999)
            getSharedPreferences("manual-count-drafts", MODE_PRIVATE).edit().putString(draftKey(), next.toString()).apply()
        } else {
            val next = ((weightKg ?: 0.0) + delta).coerceIn(0.0, 999.0)
            weightKg = next
            sendWorkoutCommand("EDIT_SET") { putExtra("which", "CURRENT"); putExtra("reps", -1); putExtra("weightKg", next) }
        }
        updateUi()
    }

    /** Tapping a value opens the watch's own number keypad for anyone the plus and minus steps do not suit. */
    private fun keypadFor(field: EditPageView.Field) {
        if (field == EditPageView.Field.REPS && !resting) { showManualCount(editOnly = true); return }
        val reps = field == EditPageView.Field.REPS
        val initial = when {
            reps && resting -> (if (restEditSegment == 0) lastWorkingReps else targetReps).toString()
            reps -> (displayedCount() ?: this.reps).toString()
            else -> (if (resting && restEditSegment == 0) lastWorkingWeight else weightKg)?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty()
        }
        val input = EditText(this).apply {
            inputType = if (reps) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            filters = arrayOf(InputFilter.LengthFilter(5)); setText(initial); setSelection(initial.length)
        }
        AlertDialog.Builder(this).setTitle(if (reps) "횟수" else "무게 (kg)").setView(input).setNegativeButton("취소", null)
            .setPositiveButton("적용") { _, _ ->
                val value = input.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val delta = if (reps) (value.toInt() - (initial.toIntOrNull() ?: 0)) else 0
                if (reps) editValue(field, delta)
                else {
                    val current = (if (resting && restEditSegment == 0) lastWorkingWeight else weightKg) ?: 0.0
                    // The step helper works in whole kilograms; the keypad can set any value, so apply the difference.
                    val difference = value - current
                    if (resting) {
                        val which = if (restEditSegment == 0) "PREVIOUS" else "NEXT"
                        if (restEditSegment == 0) lastWorkingWeight = value else weightKg = value
                        sendWorkoutCommand("EDIT_SET") { putExtra("which", which); putExtra("reps", -1); putExtra("weightKg", value) }
                    } else {
                        weightKg = value
                        sendWorkoutCommand("EDIT_SET") { putExtra("which", "CURRENT"); putExtra("reps", -1); putExtra("weightKg", value) }
                    }
                    if (difference != 0.0) updateUi()
                }
            }.show()
    }

    /** A red glow rises from the bottom once the pull passes the threshold; the label turns red and grows. */
    private inner class PullGlowView(context: android.content.Context) : View(context) {
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 106, 92); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        var armed = false
            set(value) {
                if (field == value) return
                field = value
                if (value) { alpha = 0f; visibility = View.VISIBLE; animate().alpha(1f).setDuration(120).start() }
                else animate().alpha(0f).setDuration(120).withEndAction { if (!armed) visibility = View.GONE }.start()
            }
        override fun onDraw(canvas: Canvas) {
            val side = minOf(width, height).toFloat(); val k = side / 432f
            val top = height - 190 * k
            glow.shader = android.graphics.LinearGradient(0f, height.toFloat(), 0f, top,
                intArrayOf(Color.argb(158, 229, 72, 59), Color.argb(56, 229, 72, 59), Color.argb(0, 229, 72, 59)), floatArrayOf(0f, .58f, 1f), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), glow)
            label.textSize = 15f * resources.displayMetrics.scaledDensity
            canvas.drawText("다음 세트 시작", width / 2f, height - 62 * k, label)
        }
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
        val now = System.currentTimeMillis()
        val clockText = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        face.clock.text = clockText
        val active = running || resting || paused || sessionId.isNotBlank() && setId.isNotBlank()
        val complete = !active && homeStore.showsCompletion()
        if (complete && displayedCompletionSession != homeStore.completion()?.sessionId) {
            displayedCompletionSession = homeStore.completion()?.sessionId.orEmpty()
            resultGeneration += 1; phoneResultPending = false; phoneResultStatus = ""
        }
        val count = displayedCount()
        val measuring = active && !resting
        if (measuring) { lastWorkingReps = count ?: reps; lastWorkingWeight = weightKg }
        if (resting && !wasResting) restEditSegment = 0
        wasResting = resting
        face.primary(if (resting) 2 else if (active) 0 else 1)
        face.homeLayout(!active && !complete)
        face.value.maxLines = if (complete) 2 else 1
        face.showDivider = measuring
        face.invalidate()
        listOf(face.target, face.weight, face.unit, face.heartRate).forEach { it.visibility = if (measuring) View.VISIBLE else View.GONE }
        face.title.visibility = if (resting) View.GONE else View.VISIBLE
        face.down.visibility = View.GONE
        face.action.visibility = if (complete) View.VISIBLE else View.GONE
        face.action.text = "폰에서 보기"; face.action.isEnabled = !phoneResultPending
        face.action.alpha = if (phoneResultPending) 0.5f else 1f
        face.hint.text = ""; face.hint.setTextColor(WatchWorkoutFace.MUTED); face.noticeAction = null
        face.value.textSize = if (active) 88f else 24f
        face.value.setTextColor(WatchWorkoutFace.BONE)
        face.restCard.visibility = View.GONE; face.restGo.visibility = View.GONE; face.jog.visibility = View.GONE
        val controlsReady = resting && !paused && workoutControls.supportsControls() && setId.isNotBlank() && attemptId.isNotBlank()
        if (active) {
            face.title.text = exerciseName
            face.setSubtitle(
                when { paused -> "일시정지"; manualRequired -> "${setIndex + 1}세트 · 직접 기록"; else -> "${setIndex + 1}세트 · 자동 측정" },
                WatchWorkoutFace.MUTED, dot = if (!paused && !manualRequired && running) WatchWorkoutFace.SAGE else null)
            face.target.text = "/ ${targetReps}회"
            face.value.text = count?.toString() ?: "—"
            face.value.setTextColor(if (count != null && count > targetReps) WatchWorkoutFace.ORANGE else WatchWorkoutFace.BONE)
            face.weight.text = weightKg?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) } ?: "—"
            face.unit.text = "kg"; face.heart.show(if (resting) 0 else heartRate); face.heartRate.text = heartRate.takeIf { it > 0 }?.toString() ?: "—"
            if (manualRequired && count == null) face.hint.text = "위로 · 횟수 기록"
            if (running && runtimePhase in setOf("POSITIONING", "PREPARING", "BASELINE", "START_DIRECTION_WAITING")) {
                face.value.text = "준비"; face.value.textSize = 40f
                face.hint.text = if (runtimePhase == "START_DIRECTION_WAITING") "손목 방향을 맞춰 주세요 · 눌러서 직접 시작" else "손목을 잠시 멈춰 주세요"
            }
            if (resting) {
                val left = RestCountdownCalculator.calculate(now, restEndAtMs, restSeconds).remainingSeconds
                face.value.text = String.format("%d:%02d", left / 60, left % 60)
                face.value.textSize = 63f; face.value.setTextColor(WatchWorkoutFace.YELLOW)
                face.target.visibility = View.GONE
                if (now < restFlashUntilMs) face.setSubtitle(restFlashText, WatchWorkoutFace.YELLOW, sizeSp = 15f, bold = true)
                else face.setSubtitle("휴식 · 목표 ${restSeconds / 60}:${"%02d".format(restSeconds % 60)}", WatchWorkoutFace.YELLOW, bold = true)
                face.restCard.text = "다음 세트   ${targetReps}회" + (weightKg?.let { " × ${if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it)}kg" } ?: "")
                face.restCard.visibility = View.VISIBLE
                face.jog.visibility = View.VISIBLE
                face.restGo.text = "다음 세트 시작"
                face.restGo.visibility = if (controlsReady) View.VISIBLE else View.GONE
            }
            if (paused) face.hint.text = "폰에서 계속할 수 있어요"
        } else if (complete) {
            face.title.text = "운동 완료"; face.setSubtitle("수고했어요", WatchWorkoutFace.MUTED)
            face.value.text = if (phoneResultStatus.isBlank()) "기록을 확인해 보세요" else phoneResultStatus
            face.value.textSize = 14f; face.value.setTextColor(WatchWorkoutFace.WHITE)
            face.heart.show(0)
        } else {
            face.title.text = "이번 주 운동"; face.setSubtitle("", WatchWorkoutFace.MUTED)
            face.value.text = homeStore.weekCount()?.let { "${it}회" } ?: "—"
            face.value.textSize = 40f
            val recent = homeStore.overview()?.optJSONObject("recent")
            face.hint.text = recent?.let {
                val date = java.time.Instant.ofEpochMilli(it.getLong("endedAtMs")).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                "최근 · ${it.getString("routineName")}\n${date.monthValue}월 ${date.dayOfMonth}일"
            } ?: "최근 기록이 아직 없어요"
            // A missing overview is unknown, not an empty workout history.
            if (homeStore.overview() == null) face.hint.text = "폰에서 기록을 확인하세요"
            face.heart.show(0)
        }
        // One status line under the numbers, most urgent first; every message here used to be its own control.
        val unresolved = manualOutbox.unresolved()
        val updateWaiting = otaCoordinator.store.local()?.phase == dev.kyungmin.kcoach.updates.OtaPhase.USER_ACTION_REQUIRED
        val phaseMessage = when (runtimePhase) {
            "CAPTURE_RECOVERY_REQUIRED" -> "센서 기록 복구가 필요해요"
            "MANUAL_PENDING" -> "폰에 기록 전달 중"
            "MANUAL_CONFLICT" -> "폰과 워치의 횟수를 확인해 주세요"
            else -> ""
        }
        if (active && !resting && phaseMessage.isNotEmpty()) { face.hint.text = phaseMessage; face.hint.setTextColor(WatchWorkoutFace.ORANGE) }
        else if (active && !paired) { face.hint.text = "폰 연결이 끊겼어요 · 기록은 보관돼요"; face.hint.setTextColor(WatchWorkoutFace.ORANGE) }
        else if (updateWaiting) { face.hint.text = "폰이 요청한 업데이트 계속"; face.hint.setTextColor(WatchWorkoutFace.SAGE); face.noticeAction = { otaCoordinator.confirmInstall(this) } }
        else if (unresolved.isNotEmpty()) {
            face.hint.text = "보관 중인 횟수 ${unresolved.size}건"; face.hint.setTextColor(WatchWorkoutFace.SAGE)
            face.noticeAction = {
                AlertDialog.Builder(this).setTitle("보관 중인 횟수").setMessage(manualOutbox.unresolved().joinToString("\n") {
                    "${it.command.setIndex + 1}세트 · ${it.command.performedReps}회 · " +
                        if (it.ack?.status == ManualSetStatus.CONFLICT) "폰 기록과 달라요" else "폰에서 확인해 주세요"
                }).setPositiveButton("닫기", null).show()
            }
        }
        face.hint.isClickable = face.noticeAction != null || runtimePhase == "START_DIRECTION_WAITING"
        shadowText.visibility = if (running && shadowCommandId.isNotBlank()) View.VISIBLE else View.GONE
        shadowText.text = "보정 비교 · ${shadowCount}회"; recordShadowFrameCommit()

        // Pages: the editor above, the heart-rate page below while measuring, and the pull-to-start gesture while resting.
        val editable = active && !paused && !sessionId.startsWith("calibration-") && setId.isNotBlank() && attemptId.isNotBlank() && runtimePhase != "CAPTURE_RECOVERY_REQUIRED"
        pager.topEnabled = editable
        pager.bottomEnabled = measuring && !paused && !sessionId.startsWith("calibration-")
        pager.pullEnabled = controlsReady
        face.chevronUp.visibility = if (pager.topEnabled) View.VISIBLE else View.GONE
        face.chevronDown.visibility = if (pager.bottomEnabled || pager.pullEnabled) View.VISIBLE else View.GONE
        if (pager.page == -1 && !pager.topEnabled || pager.page == 1 && !pager.bottomEnabled) pager.show(0)
        if (active) {
            val pending = manualStatus in setOf("PENDING", "STALE", "NO_SESSION", "REJECTED")
            if (resting) {
                editPage.applyMode(EditPageView.Mode.REST)
                val previous = restEditSegment == 0
                val repsShown = if (previous) lastWorkingReps else targetReps
                val weightShown = if (previous) lastWorkingWeight else weightKg
                val left = RestCountdownCalculator.calculate(now, restEndAtMs, restSeconds).remainingSeconds
                editPage.bindRest("휴식 ${left / 60}:${"%02d".format(left % 60)}", clockText, "방금 세트", "다음 세트", restEditSegment,
                    repsShown.toString(), weightShown?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) } ?: "—")
            } else {
                editPage.applyMode(EditPageView.Mode.MEASURE)
                val finishText = when (manualStatus) {
                    "PENDING" -> "폰에 기록 전달 중"
                    "CONFLICT" -> "횟수 확인"
                    "STALE", "NO_SESSION", "REJECTED" -> "폰에서 세트를 확인하세요"
                    "APPLIED", "DUPLICATE" -> "기록 수정"
                    else -> if (count == null) "횟수 입력" else "${count}회로 세트 마치기"
                }
                editPage.bindMeasure(exerciseName, setIndex + 1, (count ?: reps).toString(),
                    weightKg?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) } ?: "—", finishText, !pending && !paused)
            }
            val started = sessionStartedAt()
            val elapsed = ((now - started) / 1_000L).coerceAtLeast(0L)
            auxPage.bind(heartRate, if (elapsed >= 3600) "%d:%02d:%02d".format(elapsed / 3600, elapsed % 3600 / 60, elapsed % 60) else "%d:%02d".format(elapsed / 60, elapsed % 60), setIndex, clockText)
        }
        if (!active) face.heart.show(0)
        if (resting && restEndAtMs > now) {
            restRing.setWindow(restEndAtMs - restSeconds.coerceAtLeast(1) * 1_000L, restEndAtMs); restRing.visibility = View.VISIBLE
        } else if (active && !resting && !paused && count != null && targetReps > 0) {
            restRing.setProgress(count.toFloat() / targetReps); restRing.visibility = View.VISIBLE
        } else restRing.visibility = View.GONE
        rollCountIfChanged(measuring)
        val presentation = if (active) "active" else if (complete) "complete" else "home"
        if (lastPresentation != presentation) {
            lastPresentation = presentation
            pager.show(0)
        }
    }

    /** When this session was first seen on the watch, kept across restarts so the workout clock does not reset. */
    private fun sessionStartedAt(): Long {
        val prefs = getSharedPreferences("session-clock", MODE_PRIVATE)
        val key = "start:$sessionId"
        val saved = prefs.getLong(key, 0L)
        if (saved > 0L) return saved
        val now = System.currentTimeMillis()
        prefs.edit().clear().putLong(key, now).apply()
        return now
    }

    private var lastPresentation = ""
    private var lastCountShown = ""

    /** The old number slides up and out while the new one rises in from below, quickly and without any bounce. */
    private fun rollCountIfChanged(measuring: Boolean) {
        val shown = face.value.text.toString()
        if (!measuring || shown.firstOrNull()?.isDigit() != true) { lastCountShown = ""; return }
        val old = lastCountShown
        lastCountShown = shown
        if (old.isEmpty() || old == shown || old.firstOrNull()?.isDigit() != true) return
        val travel = dp(60).toFloat()
        val interpolator = android.view.animation.PathInterpolator(.4f, 0f, .2f, 1f)
        face.valueGhost.apply {
            text = old; setTextColor(face.value.currentTextColor); textSize = face.value.textSize / resources.displayMetrics.scaledDensity
            animate().cancel(); translationY = 0f; alpha = 1f
            animate().translationY(-travel).alpha(0f).setDuration(180).setInterpolator(interpolator).start()
        }
        face.value.apply {
            animate().cancel(); translationY = travel; alpha = 0f
            animate().translationY(0f).alpha(1f).setDuration(180).setInterpolator(interpolator).start()
        }
    }
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
            color = WatchWorkoutFace.YELLOW
        }
        private var startAtMs = 0L
        private var endAtMs = 0L
        private var progress: Float? = null

        fun setWindow(startAtMs: Long, endAtMs: Long) {
            this.startAtMs = startAtMs
            this.endAtMs = endAtMs
            progress = null
            invalidate()
        }

        /** Measuring: the rim fills toward the target; past the target a second, darker lap builds on top. */
        fun setProgress(fraction: Float) {
            if (progress == fraction) return
            progress = fraction
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = System.currentTimeMillis()
            val total = endAtMs - startAtMs
            val remaining = (endAtMs - now).coerceAtLeast(0L)
            val measured = progress
            if (measured == null && (total <= 0L || remaining <= 0L)) return
            val fraction = if (measured != null) measured.coerceIn(0f, 1f) else (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
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
            paint.strokeWidth = 3.5f * density
            // The stroke is centered on the arc, so half of it plus a hairline gap keeps the outer
            // edge of the ring right against the rim of the display instead of floating inside it.
            val inset = paint.strokeWidth / 2f + 1f * density
            val left = (canvasWidth - roundDiameter) / 2f + inset
            val top = roundTop + inset
            val right = (canvasWidth + roundDiameter) / 2f - inset
            val bottom = roundTop + roundDiameter - inset
            // -90 degrees is 12 o'clock: the ring advances clockwise from the
            // top-center of the circular watch face.
            val oval = RectF(left, top, right, bottom)
            if (measured != null) {
                paint.color = WatchWorkoutFace.TRACK; canvas.drawArc(oval, -90f, 360f, false, paint)
                val over = measured > 1f
                paint.color = if (over) WatchWorkoutFace.ORANGE else WatchWorkoutFace.SAGE
                canvas.drawArc(oval, -90f, 360f * fraction, false, paint)
                if (over) { paint.color = WatchWorkoutFace.DEEP_AMBER; canvas.drawArc(oval, -90f, 360f * (measured - 1f).coerceIn(0f, 1f), false, paint) }
                paint.color = WatchWorkoutFace.YELLOW
                return
            }
            paint.color = WatchWorkoutFace.YELLOW
            canvas.drawArc(oval, -90f, 360f * fraction, false, paint)
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
