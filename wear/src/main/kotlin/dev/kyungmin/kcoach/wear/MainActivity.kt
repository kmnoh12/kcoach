package dev.kyungmin.kcoach.wear

import android.Manifest
import android.app.Activity
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import dev.kyungmin.kcoach.core.EmulatorBridgeClient
import dev.kyungmin.kcoach.core.RestCountdownCalculator
import dev.kyungmin.kcoach.core.WearSync

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val emulatorBridge = EmulatorBridgeClient { _, _ -> }
    private lateinit var updateManager: WearUpdateManager
    private var updateInProgress = false
    private var updateReadyToInstall = false
    private var updateButton: TextView? = null
    private val isEmulator: Boolean
        get() = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gwear", ignoreCase = true)

    private var paired = false
    private var running = false
    private var resting = false
    private var paused = false
    private var sessionId = ""
    private var exerciseId = ""
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

    private val runtimeStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == WearWorkoutService.ACTION_STATE) applyRuntimeState(intent)
        }
    }

    private lateinit var exerciseText: TextView
    private lateinit var setText: TextView
    private lateinit var repText: TextView
    private lateinit var heartText: TextView
    private lateinit var connectionText: TextView
    private lateinit var phaseText: TextView
    private lateinit var repCaption: TextView
    private lateinit var restText: TextView
    private lateinit var restRing: RestProgressRing
    private val restUiTicker = object : Runnable {
        override fun run() {
            if (!resting) return
            updateUi()
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateManager = WearUpdateManager(this, BuildConfig.UPDATE_MANIFEST_URL)
        setContentView(buildUi())
        emulatorBridge.start()
        startService(Intent(this, WearCommandService::class.java))
        startService(Intent(this, WearWorkoutService::class.java))
        registerReceiver(runtimeStateReceiver, IntentFilter(WearWorkoutService.ACTION_STATE), android.content.Context.RECEIVER_NOT_EXPORTED)
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
        updateScreenPolicy()
        updateUi()
        handler.removeCallbacks(restUiTicker)
        if (resting) handler.post(restUiTicker)
    }

    override fun onResume() {
        super.onResume()
        updateScreenPolicy()
        updateManager.resumePendingInstall()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    }

    override fun onStop() {
        // The host relay and timers are intentionally kept alive across the
        // screen-off/launcher transition. A real session must not stop merely
        // because the round display is no longer foregrounded.
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(restUiTicker)
        runCatching { unregisterReceiver(runtimeStateReceiver) }
        emulatorBridge.stop()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(label("KCOACH WEAR", 11f, Color.rgb(0, 229, 255)).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        connectionText = label("○ 스마트폰 연결 대기", 11f, Color.rgb(255, 177, 0)).apply {
            gravity = Gravity.CENTER
            setPadding(16, 4, 16, 4)
            background = rounded(Color.rgb(30, 32, 36), 16)
        }
        root.addView(connectionText, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = 4
            bottomMargin = 6
        })
        phaseText = label("운동 대기 중!", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 6)
        }
        root.addView(phaseText)
        exerciseText = label(exerciseName, 17f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
        }
        root.addView(exerciseText)
        setText = label("폰에서 운동을 시작하면 현재 세트가 표시돼요", 11f, Color.rgb(150, 160, 170)).apply {
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 6)
        }
        root.addView(setText)

        val repCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(12, 8, 12, 8)
            background = rounded(Color.rgb(18, 22, 26), 20, Color.rgb(40, 48, 56), 1)
        }
        repCaption = label("현재 횟수 / 이번 세트 목표", 9f, Color.rgb(150, 160, 170)).apply {
            gravity = Gravity.CENTER
        }
        repCard.addView(repCaption)
        repText = label("-- / --회", 32f, Color.rgb(150, 160, 170), true).apply {
            gravity = Gravity.CENTER
        }
        repCard.addView(repText)
        root.addView(repCard, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = 6
            bottomMargin = 5
        })

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 8, 0, 16)
        }
        heartText = label("❤️ -- bpm", 12f, Color.rgb(255, 82, 82), true).apply {
            gravity = Gravity.CENTER
        }
        details.addView(heartText)
        restText = label("", 13f, Color.rgb(255, 177, 0), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 0)
        }
        details.addView(restText)
        details.addView(label("화면을 내려 심박수와 센서 상태 확인", 10f, Color.rgb(110, 115, 120)).apply {
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 0)
        })
        updateButton = label("업데이트 확인", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(18, 10, 18, 10)
            background = rounded(Color.rgb(35, 58, 72), 18)
            isClickable = true
            setOnClickListener {
                if (updateReadyToInstall) {
                    updateReadyToInstall = false
                    text = "업데이트 확인"
                    updateScreenPolicy()
                    updateManager.requestInstall()?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
                } else {
                    checkForUpdate()
                }
            }
        }
        details.addView(updateButton, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = 18
            bottomMargin = 8
        })
        details.addView(label("실측 모드", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(18, 10, 18, 10)
            background = rounded(Color.rgb(20, 70, 80), 18)
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, ProbeActivity::class.java)) }
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 8 })
        root.addView(ScrollView(this).apply {
            isFillViewport = false
            addView(details)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        restRing = RestProgressRing().apply {
            visibility = View.GONE
            isClickable = false
        }
        frame.addView(restRing, FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    private fun checkForUpdate() {
        val button = updateButton ?: return
        updateInProgress = true
        updateScreenPolicy()
        button.isEnabled = false
        button.text = "업데이트 확인 중…"
        updateManager.checkAndDownload { state ->
            handler.post {
                updateInProgress = state.busy
                button.isEnabled = !state.busy
                updateReadyToInstall = state.readyToInstall
                button.text = if (state.readyToInstall) "업데이트 설치" else "업데이트 확인"
                updateScreenPolicy()
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val missing = arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.ACTIVITY_RECOGNITION)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 42)
    }

    private fun updateScreenPolicy() {
        if (running || resting || paused || updateInProgress || updateReadyToInstall) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateUi() {
        if (!::repText.isInitialized) return
        connectionText.text = if (paired) "● 스마트폰과 연결 완료" else "○ 스마트폰 연결 대기"
        connectionText.setTextColor(if (paired) Color.rgb(0, 230, 118) else Color.rgb(255, 177, 0))
        phaseText.text = when {
            runtimePhase == "CAPTURE_RECOVERY_REQUIRED" -> "센서 기록 복구 필요"
            running && runtimePhase == "POSITIONING" -> "준비 자세 잡기"
            running && runtimePhase in setOf("PREPARING", "BASELINE") -> "첫 자세 고정"
            running && runtimePhase == "CONTROLLED_REPS" -> "진동 후 동작 시작"
            running -> "운동 진행 중"
            resting -> "다음 운동"
            paused -> "일시정지"
            paired -> "운동 대기 중!"
            else -> "스마트폰 연결 대기"
        }
        val hasActiveExercise = running || resting || paused
        exerciseText.visibility = if (hasActiveExercise) View.VISIBLE else View.GONE
        exerciseText.text = if (hasActiveExercise) exerciseName else ""
        val weightText = weightKg?.let { " · ${if (it % 1.0 == 0.0) it.toInt() else "%.1f".format(it)}kg" } ?: ""
        setText.text = when {
            runtimePhase == "CAPTURE_RECOVERY_REQUIRED" -> "센서 기록이 일부 없거나 손상됐어요"
            running && runtimePhase == "POSITIONING" ->
                "팔을 준비 위치로 옮겨 주세요"
            running && runtimePhase in setOf("PREPARING", "BASELINE") ->
                "흔들림 없이 2초 유지해 주세요"
            running && runtimePhase == "CONTROLLED_REPS" ->
                "자연스러운 범위·속도로 5회$weightText"
            running -> "세트 ${setIndex + 1} · 목표 ${targetReps}회$weightText"
            resting -> "세트 ${setIndex + 1} 준비 · ${restSeconds}초 휴식"
            paused -> "운동 일시정지 · 폰 명령 대기"
            paired -> "폰에서 운동을 시작하면 현재 세트가 표시돼요"
            else -> ""
        }
        val countdown = RestCountdownCalculator.calculate(
            nowMs = System.currentTimeMillis(),
            endAtMs = restEndAtMs,
            totalSeconds = restSeconds,
        )
        repCaption.text = if (resting) "남은 휴식 / 전체 휴식" else "현재 횟수 / 이번 세트 목표"
        repText.text = when {
            running && runtimePhase in setOf("POSITIONING", "PREPARING", "BASELINE") -> "준비"
            resting -> "${countdown.remainingSeconds} / ${countdown.totalSeconds}초"
            hasActiveExercise -> "$reps / ${targetReps}회"
            else -> "-- / --회"
        }
        repText.setTextColor(if (reps >= targetReps && running) Color.rgb(0, 229, 255) else if (hasActiveExercise) Color.rgb(0, 230, 118) else Color.rgb(150, 160, 170))
        heartText.text = if (heartRate > 0) "❤️ $heartRate bpm" else "❤️ -- bpm"
        if (resting) {
            val now = System.currentTimeMillis()
            restText.text = "휴식 후 다음 세트를 자동으로 준비해요"
            restRing.setWindow(restEndAtMs - restSeconds.coerceAtLeast(1) * 1_000L, restEndAtMs)
            restRing.visibility = if (restEndAtMs > now) View.VISIBLE else View.GONE
        } else {
            restRing.visibility = View.GONE
            if (!restText.text.toString().startsWith("다음 세트")) restText.text = ""
        }
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
            cornerRadius = radiusDp.toFloat()
            if (strokeColor != null) setStroke(strokeDp, strokeColor)
        }
}
