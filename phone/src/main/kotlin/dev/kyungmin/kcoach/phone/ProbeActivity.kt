package dev.kyungmin.kcoach.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dev.kyungmin.kcoach.core.MotionSegment
import dev.kyungmin.kcoach.core.ProbeWire
import dev.kyungmin.kcoach.core.Vec3
import dev.kyungmin.kcoach.core.WristTrajectoryTracker
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Phone side of the measurement probe: receives the watch's raw IMU stream,
 * reports rate / latency / loss, reconstructs per-movement wrist trajectories
 * and can record the raw stream to CSV for offline analysis.
 */
class ProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val channelClient by lazy { Wearable.getChannelClient(this) }
    private val lock = Any()
    private val writeLock = Any()

    @Volatile private var output: DataOutputStream? = null
    @Volatile private var connected = false
    @Volatile private var destroyed = false

    // Guarded by lock.
    private var totalSamples = 0L
    private var expectedSeq = -1
    private var lostFrames = 0
    private val accelRate = RateWindow()
    private val gyroRate = RateWindow()
    private val screenOnRate = StateRate()
    private val screenOffRate = StateRate()
    private var lastScreenOn = true
    private var gapCount = 0
    private var missingEstimate = 0L
    private val lastSampleTs = LongArray(2)
    private val pongs = ArrayDeque<Pair<Long, Long>>() // rtt, offset (watch - phone)
    private val ages = ArrayDeque<Long>()
    private var tracker = WristTrajectoryTracker()
    private val segments = ArrayDeque<MotionSegment>()
    @Volatile private var segmentCount = 0
    private var recorder: BufferedWriter? = null
    private var recordFile: File? = null
    private var recordedSamples = 0L

    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var segmentText: TextView
    private lateinit var recordButton: Button
    private lateinit var trajectoryView: TrajectoryView

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            if (channel.path == ProbeWire.CHANNEL_PATH) startReader(channel)
        }
    }

    private val uiTicker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 300L)
        }
    }

    private val pinger = Thread({
        while (!destroyed) {
            output?.let { out ->
                runCatching {
                    synchronized(writeLock) {
                        ProbeWire.write(out, ProbeWire.Ping(SystemClock.elapsedRealtimeNanos()))
                        out.flush()
                    }
                }
            }
            runCatching { Thread.sleep(1_000L) }
        }
    }, "kcoach-probe-ping")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        channelClient.registerChannelCallback(channelCallback)
        pinger.start()
        handler.post(uiTicker)
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(uiTicker)
        channelClient.unregisterChannelCallback(channelCallback)
        synchronized(lock) { stopRecording() }
        super.onDestroy()
    }

    private fun startReader(channel: ChannelClient.Channel) {
        Thread({
            try {
                val input = DataInputStream(BufferedInputStream(Tasks.await(channelClient.getInputStream(channel))))
                output = DataOutputStream(BufferedOutputStream(Tasks.await(channelClient.getOutputStream(channel))))
                connected = true
                while (!destroyed) handleFrame(ProbeWire.read(input), SystemClock.elapsedRealtimeNanos())
            } catch (_: Throwable) {
                // Channel closed or watch stopped; the watch reopens on its own.
            } finally {
                connected = false
                output = null
                runCatching { channelClient.close(channel) }
            }
        }, "kcoach-probe-reader").start()
    }

    private fun handleFrame(frame: ProbeWire.Frame, nowNanos: Long) = synchronized(lock) {
        when (frame) {
            is ProbeWire.Pong -> {
                val rtt = nowNanos - frame.phoneNanos
                pongs.addLast(rtt to frame.watchNanos - (frame.phoneNanos + rtt / 2))
                while (pongs.size > 30) pongs.removeFirst()
            }
            is ProbeWire.Samples -> {
                if (expectedSeq >= 0 && frame.seq > expectedSeq) lostFrames += frame.seq - expectedSeq
                expectedSeq = frame.seq + 1
                frame.samples.forEach { onSample(it, frame.screenOn) }
                val offset = pongs.minByOrNull { it.first }?.second
                val newest = frame.samples.lastOrNull()?.timestampNanos
                if (offset != null && newest != null) {
                    ages.addLast(nowNanos - (newest - offset))
                    while (ages.size > 200) ages.removeFirst()
                }
            }
            is ProbeWire.Ping -> Unit
        }
    }

    private fun onSample(sample: ProbeWire.Sample, screenOn: Boolean) {
        totalSamples++
        val previous = lastSampleTs[sample.sensor]
        if (previous > 0) {
            val gap = sample.timestampNanos - previous
            if (gap > GAP_NANOS) {
                gapCount++
                missingEstimate += gap / NOMINAL_PERIOD_NANOS - 1
            }
            if (sample.sensor == ProbeWire.SENSOR_ACCEL && screenOn == lastScreenOn && gap in 1..GAP_NANOS * 20) {
                (if (screenOn) screenOnRate else screenOffRate).add(gap)
            }
        }
        lastSampleTs[sample.sensor] = sample.timestampNanos
        if (sample.sensor == ProbeWire.SENSOR_ACCEL) {
            lastScreenOn = screenOn
            accelRate.add(sample.timestampNanos)
            tracker.onAccel(sample.timestampNanos, sample.x, sample.y, sample.z)
        } else {
            gyroRate.add(sample.timestampNanos)
            tracker.onGyro(sample.timestampNanos, sample.x, sample.y, sample.z).forEach { segment ->
                if (segment.pathLengthM >= MIN_SEGMENT_PATH_M) {
                    segmentCount++
                    segments.addLast(segment)
                    while (segments.size > 12) segments.removeFirst()
                }
            }
        }
        recorder?.let {
            it.write("${sample.sensor},${sample.timestampNanos},${sample.x},${sample.y},${sample.z},${if (screenOn) 1 else 0}\n")
            recordedSamples++
        }
    }

    private fun render() {
        val (stats, segmentLines, paths) = synchronized(lock) {
            val rtts = pongs.map { it.first / 1e6 }.sorted()
            val ageMs = ages.map { it / 1e6 }.sorted()
            val stats = buildString {
                append("가속도 %.0fHz · 자이로 %.0fHz\n".format(accelRate.hz(), gyroRate.hz()))
                append("화면 켜짐 %s · 꺼짐 %s\n".format(screenOnRate.label(), screenOffRate.label()))
                append("받은 샘플 $totalSamples\n")
                append("센서 끊김 ${gapCount}회 (약 ${missingEstimate}개 빠짐)\n")
                append("전송 프레임 손실 ${lostFrames}개\n")
                append("왕복 지연 %s\n".format(percentiles(rtts)))
                append("센서→폰 지연 %s".format(percentiles(ageMs)))
                if (recorder != null) append("\n● 기록 중 ${recordedSamples}개")
            }
            val lines = segments.reversed().take(6).mapIndexed { i, s ->
                "${segments.size - i}. %.2f초 · 이동 %.0fcm · 경로 %.0fcm · 최고 %.2fm/s".format(
                    s.durationSec, s.displacementM * 100, s.pathLengthM * 100, s.peakSpeedMps,
                )
            }
            Triple(stats, lines, segments.map { it.points })
        }
        statusText.text = if (connected) "● 워치 연결됨" else "○ 워치 대기 중 · 워치에서 KCoach → 실측 모드 → 시작"
        statusText.setTextColor(if (connected) Color.rgb(0, 230, 118) else Color.rgb(255, 177, 0))
        statsText.text = stats
        segmentText.text = if (segmentLines.isEmpty()) {
            "올리고 내릴 때마다 구간이 하나씩 잡혀"
        } else {
            // One rep = up stroke + down stroke (MM-Fit: about two segments per rep).
            "구간 ${segmentCount}개 · 반복 약 ${(segmentCount + 1) / 2}회 (궤적 지우기로 0부터)\n" + segmentLines.joinToString("\n")
        }
        trajectoryView.setPaths(paths)
    }

    private fun percentiles(sortedMs: List<Double>): String =
        if (sortedMs.isEmpty()) "--"
        else "중앙 %.0fms · 95%% %.0fms".format(sortedMs[sortedMs.size / 2], sortedMs[(sortedMs.size * 95 / 100).coerceAtMost(sortedMs.size - 1)])

    private fun toggleRecording() {
        synchronized(lock) {
            if (recorder != null) {
                stopRecording()
            } else {
                val dir = File(filesDir, "probe").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                recordFile = File(dir, "probe-$stamp.csv")
                recordedSamples = 0
                recorder = recordFile!!.bufferedWriter().also { it.write("sensor,t_ns,x,y,z,screen_on\n") }
            }
        }
        recordButton.text = if (recorder != null) "기록 중지" else "기록 시작"
    }

    private fun stopRecording() {
        recorder?.let { runCatching { it.close() } }
        recorder = null
    }

    private fun shareRecording() {
        val file = synchronized(lock) { if (recorder == null) recordFile else null }
        if (file == null || !file.isFile) {
            statusText.text = if (recorder != null) "기록을 먼저 중지해" else "공유할 기록이 없어"
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "실측 기록 공유"))
    }

    private fun resetTrajectory() = synchronized(lock) {
        tracker = WristTrajectoryTracker()
        segments.clear()
        segmentCount = 0
    }

    private fun buildUi(): View {
        val dp = resources.displayMetrics.density
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt(), (32 * dp).toInt())
        }
        column.addView(TextView(this).apply {
            text = "실측 모드"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        }
        column.addView(statusText)
        statsText = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(220, 224, 228))
        }
        column.addView(statsText)
        trajectoryView = TrajectoryView(this)
        column.addView(trajectoryView, LinearLayout.LayoutParams(-1, (340 * dp).toInt()).apply {
            topMargin = (16 * dp).toInt()
        })
        column.addView(TextView(this).apply {
            text = "끌어서 돌려봐 · 주황색이 가장 최근 구간"
            textSize = 12f
            setTextColor(Color.rgb(140, 146, 150))
            gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, (10 * dp).toInt())
        })
        segmentText = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(220, 224, 228))
        }
        column.addView(segmentText)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (16 * dp).toInt(), 0, 0)
        }
        recordButton = actionButton("기록 시작") { toggleRecording() }
        row.addView(recordButton, LinearLayout.LayoutParams(0, (52 * dp).toInt(), 1f))
        row.addView(actionButton("공유") { shareRecording() }, LinearLayout.LayoutParams(0, (52 * dp).toInt(), 1f).apply {
            marginStart = (8 * dp).toInt()
        })
        row.addView(actionButton("궤적 지우기") { resetTrajectory() }, LinearLayout.LayoutParams(0, (52 * dp).toInt(), 1f).apply {
            marginStart = (8 * dp).toInt()
        })
        column.addView(row)
        return ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.rgb(10, 12, 12))
            addView(column)
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.rgb(40, 44, 48))
            cornerRadius = 14 * resources.displayMetrics.density
        }
        setOnClickListener { onClick() }
    }

    /** Sample rate over the last two seconds of sensor timestamps. */
    private class RateWindow {
        private val times = ArrayDeque<Long>()
        fun add(t: Long) {
            times.addLast(t)
            while (times.size > 2 && t - times.first > 2_000_000_000L) times.removeFirst()
        }
        fun hz(): Double = if (times.size < 2) 0.0 else (times.size - 1) * 1e9 / (times.last - times.first)
    }

    /** Average accelerometer rate while the watch screen was in one state. */
    private class StateRate {
        private var count = 0L
        private var nanos = 0L
        fun add(gap: Long) {
            count++
            nanos += gap
        }
        fun label() = if (count < 20) "--" else "%.0fHz".format(count * 1e9 / nanos)
    }

    private class TrajectoryView(context: Context) : View(context) {
        private var paths: List<List<Vec3>> = emptyList()
        private var yaw = 0.6
        private var pitch = 0.35
        private var lastX = 0f
        private var lastY = 0f
        private val density = context.resources.displayMetrics.density
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * density }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12 * density
            color = Color.rgb(140, 146, 150)
        }

        fun setPaths(next: List<List<Vec3>>) {
            paths = next
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_MOVE -> {
                    yaw += (event.x - lastX) / (120 * density)
                    pitch = (pitch + (event.y - lastY) / (120 * density)).coerceIn(-1.5, 1.5)
                    invalidate()
                }
            }
            lastX = event.x
            lastY = event.y
            return true
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(18, 20, 22))
            val extent = max(0.2, paths.flatten().maxOfOrNull { maxOf(kotlin.math.abs(it.x), kotlin.math.abs(it.y), kotlin.math.abs(it.z)) } ?: 0.0)
            val scale = minOf(width, height) * 0.42f / extent.toFloat()
            val cx = width / 2f
            val cy = height / 2f
            fun project(p: Vec3): Pair<Float, Float> {
                val x1 = p.x * cos(yaw) - p.y * sin(yaw)
                val y1 = p.x * sin(yaw) + p.y * cos(yaw)
                val z2 = -y1 * sin(pitch) + p.z * cos(pitch)
                return (cx + (x1 * scale).toFloat()) to (cy - (z2 * scale).toFloat())
            }
            val axisLength = extent * 0.8
            listOf(
                Vec3(axisLength, 0.0, 0.0) to Color.rgb(200, 80, 80),
                Vec3(0.0, axisLength, 0.0) to Color.rgb(80, 180, 100),
                Vec3(0.0, 0.0, axisLength) to Color.rgb(80, 140, 230),
            ).forEach { (end, color) ->
                val (x0, y0) = project(Vec3.ZERO)
                val (x1, y1) = project(end)
                axisPaint.color = color
                canvas.drawLine(x0, y0, x1, y1, axisPaint)
            }
            canvas.drawText("축 길이 %.0fcm · 파랑 = 위".format(axisLength * 100), 12 * density, height - 12 * density, textPaint)
            paths.forEachIndexed { index, points ->
                if (points.size < 2) return@forEachIndexed
                val latest = index == paths.lastIndex
                linePaint.color = if (latest) Color.rgb(255, 177, 0) else Color.argb(120, 120, 170, 200)
                linePaint.strokeWidth = (if (latest) 3f else 1.5f) * density
                val path = Path()
                points.forEachIndexed { i, p ->
                    val (x, y) = project(p)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, linePaint)
            }
        }
    }

    companion object {
        private const val NOMINAL_PERIOD_NANOS = 10_000_000L
        private const val GAP_NANOS = 25_000_000L
        private const val MIN_SEGMENT_PATH_M = 0.03
    }
}
