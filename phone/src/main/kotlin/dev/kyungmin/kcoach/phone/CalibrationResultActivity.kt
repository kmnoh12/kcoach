package dev.kyungmin.kcoach.phone

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import dev.kyungmin.kcoach.core.*
import dev.kyungmin.kcoach.phone.ui.CalibrationResultScreen
import dev.kyungmin.kcoach.phone.ui.CapturePlaybackUi
import java.util.concurrent.Executors

/** Ordinary count confirmation. This activity never calls saveCycles or verifies alignment for the user. */
class CalibrationResultActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val captures by lazy { CalibrationCaptureStore(this) }
    private val reviews by lazy { CalibrationReviewStore(this) }
    private var selected by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(false)
    private var loadError by mutableStateOf<String?>(null)
    private var clip by mutableStateOf<CapturePlaybackUi?>(null)
    private var clips by mutableStateOf(emptyList<Pair<String, String>>())
    private var busy by mutableStateOf(false)
    private var revision = 0L
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = if (savedInstanceState != null) savedInstanceState.getString("captureId") else intent.getStringExtra("captureId")
        setContent {
            BackHandler(selected != null) { selected = null; clip = null; refresh() }
            CalibrationResultScreen(clip, clips, busy, select = { selected = it; clip = null; refresh() }, answer = ::saveCount,
                back = { if (selected != null) { selected = null; clip = null; refresh() } else finish() },
                loading = loading, error = loadError, retry = ::refresh,
                research = { startActivity(Intent(this, CalibrationReviewActivity::class.java).apply { selected?.let { putExtra("captureId", it) } }) })
        }
    }
    override fun onResume() { super.onResume(); refresh(); main.postDelayed(poll, 2_000) }
    override fun onPause() { main.removeCallbacks(poll); super.onPause() }
    private val poll = object : Runnable {
        override fun run() { if (!busy && !loading && loadError == null && clip != null && clip?.watchCount == null) refresh(); main.postDelayed(this, 2_000) }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("captureId", selected); super.onSaveInstanceState(outState) }
    override fun onDestroy() { generation++; main.removeCallbacks(poll); worker.shutdown(); super.onDestroy() }

    private fun refresh() {
        val request = ++generation; val id = selected
        loading = clip == null; loadError = null
        worker.execute {
            val result = runCatching {
                if (id == null) return@runCatching null
                val capture = captures.load(id).getOrThrow()
                val review = reviews.load(id).getOrThrow()
                val frames = captures.motionFrames(id).getOrThrow().filter { it.videoFrameWritten }
                val end = frames.lastOrNull()?.videoPresentationUs?.div(1000)?.toInt() ?: 0
                val wrist = if (capture.watchOnLeft) 15 else 16
                val heights = frames.mapNotNull { frame ->
                    val p = frame.imageLandmarks.singleOrNull()?.getOrNull(wrist)
                    if (p == null || minOf(p.visibility ?: 0.0, p.presence ?: 0.0) < CameraCaptureGuide.MIN_CONFIDENCE ||
                        p.x !in .02.. .98 || p.y !in .02.. .98) null else (frame.videoPresentationUs/1000).toInt() to (1-p.y).toFloat()
                }
                val proposals = CameraRepProposals.propose(frames, capture.exerciseId, capture.watchOnLeft)
                val byNs = frames.associateBy { it.cameraTimestampNs }
                val intervals = proposals.mapNotNull { cycle ->
                    val start = byNs[cycle.startNs]?.videoPresentationUs; val stop = byNs[cycle.endNs]?.videoPresentationUs
                    if (start == null || stop == null) null else (start/1000).toInt() to (stop/1000).toInt()
                }
                val watch = captures.sensor(id).getOrNull()?.manifest?.payload?.get(WirePayloadKeys.WATCH_REPS)?.toIntOrNull()
                val message = when {
                    review.countReference == null -> "영상을 보고 실제 횟수를 확인해 주세요."
                    watch == null -> "횟수 저장 완료 · 워치 기록 수신 대기"
                    watch != review.countReference.reps -> "워치 횟수와 달라요. 영상을 다시 확인해 주세요."
                    else -> "횟수 저장 완료 · 반복 구간은 아직 확인 전이에요."
                }
                review.revision to CapturePlaybackUi(id, capture.exerciseName,
                    captures.videoFile(id).takeIf { capture.videoComplete && it.isFile }?.absolutePath,
                    end, heights, intervals, review.countReference?.reps, watch, message,
                    captures.performance(id)?.let { raw -> org.json.JSONObject(raw).getJSONArray("windows").let { rows ->
                        (0 until rows.length()).joinToString("\n") { rows.getString(it) }
                    } }.orEmpty())
            }
            val list = runCatching { if (id == null) captures.list().map { it.id to it.exerciseName } else null }
            main.post {
                if (isDestroyed || request != generation) return@post
                loading = false
                list.onSuccess { rows -> rows?.let { clips = it } }
                    .onFailure { loadError = "촬영 목록을 읽지 못했어요. 다시 시도해 주세요." }
                result.onSuccess { row -> if (row != null) { revision = row.first; clip = row.second } }
                    .onFailure { loadError = "촬영 자료를 읽지 못했어요. 다시 시도해 주세요." }
            }
        }
    }
    private fun saveCount(count: Int) {
        val id = selected ?: return
        if (busy || clip?.id != id) return
        busy = true; val expected = revision
        worker.execute {
            val result = reviews.saveCount(id, count, expected)
            main.post {
                if (isDestroyed) return@post
                busy = false
                result.onSuccess { refresh() }.onFailure {
                    if (selected == id) loadError = "횟수를 저장하지 못했어요. 다시 불러온 뒤 확인해 주세요."
                }
            }
        }
    }
}
