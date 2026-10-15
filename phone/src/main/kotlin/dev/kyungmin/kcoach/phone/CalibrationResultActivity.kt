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
import dev.kyungmin.kcoach.phone.ui.CaptureRowUi
import dev.kyungmin.kcoach.phone.ui.MismatchRowUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Ordinary count confirmation. This activity never calls saveCycles or verifies alignment for the user. */
class CalibrationResultActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val captures by lazy { CalibrationCaptureStore(this) }
    private val reviews by lazy { CalibrationReviewStore(this) }
    private val answers by lazy { MismatchAnswerStore(captures) }
    private var selected by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(false)
    private var loadError by mutableStateOf<String?>(null)
    private var clip by mutableStateOf<CapturePlaybackUi?>(null)
    private var clips by mutableStateOf(emptyList<CaptureRowUi>())
    private var notice by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var revision = 0L
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = if (savedInstanceState != null) savedInstanceState.getString("captureId") else intent.getStringExtra("captureId")
        setContent {
            BackHandler(selected != null) { selected = null; clip = null; refresh() }
            CalibrationResultScreen(clip, clips, busy, select = { selected = it; clip = null; notice = null; refresh() }, answer = ::saveCount,
                back = { if (selected != null) { selected = null; clip = null; refresh() } else finish() },
                loading = loading, error = loadError, retry = ::refresh,
                answerMismatch = ::answerMismatch, deleteClips = ::deleteCaptures, deleteClip = { selected?.let { deleteCaptures(setOf(it)) } }, notice = notice,
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
                val signal = CameraGraphSignal.forExercise(capture.exerciseId)
                val heights = frames.mapNotNull { frame ->
                    val value = frame.imageLandmarks.singleOrNull()
                        ?.let { signal.value(it, frame.width.toDouble(), frame.height.toDouble(), capture.watchOnLeft) } ?: return@mapNotNull null
                    (frame.videoPresentationUs / 1000).toInt() to value.toFloat()
                }
                val proposals = CameraRepProposals.propose(frames, capture.exerciseId, capture.watchOnLeft)
                val byNs = frames.associateBy { it.cameraTimestampNs }
                val intervals = proposals.mapNotNull { cycle ->
                    val start = byNs[cycle.startNs]?.videoPresentationUs; val stop = byNs[cycle.endNs]?.videoPresentationUs
                    if (start == null || stop == null) null else (start/1000).toInt() to (stop/1000).toInt()
                }
                val sensor = captures.sensor(id).getOrNull()
                val watch = sensor?.manifest?.payload?.get(WirePayloadKeys.WATCH_REPS)?.toIntOrNull()
                val watchSpans = watchSpans(id, capture, sensor, frames)
                val videoSpans = intervals.filter { it.second > it.first }.map { RepSpan(it.first, it.second) }
                val saved = answers.load(id).associate { it.candidate to it.realRep }
                val mismatches = if (watchSpans == null) emptyList() else RepMismatchFinder.find(videoSpans, watchSpans)
                    .map { MismatchRowUi(it.span.startMs, it.span.endMs, it.side == MismatchSide.VIDEO_ONLY, saved[it]) }
                val counted = review.countReference?.reps
                val message = when {
                    counted == null -> "영상을 보고 실제로 한 횟수를 알려 주세요."
                    watch == null -> "${counted}회로 저장했어요. 워치 기록을 받는 중이에요."
                    watch != counted -> "${counted}회로 저장했어요. 워치는 ${watch}회로 세서 서로 달라요. 아래에서 어긋난 구간을 확인해 주세요."
                    else -> "${counted}회로 저장했어요. 워치도 ${watch}회로 세서 일치해요."
                }
                review.revision to CapturePlaybackUi(id, capture.exerciseName,
                    captures.videoFile(id).takeIf { capture.videoComplete && it.isFile }?.absolutePath,
                    end, heights, intervals, review.countReference?.reps, watch, message,
                    captures.performance(id)?.let { raw -> org.json.JSONObject(raw).getJSONArray("windows").let { rows ->
                        (0 until rows.length()).joinToString("\n") { rows.getString(it) }
                    } }.orEmpty(), signal.title,
                    mismatches = mismatches, watchSpansKnown = watchSpans != null)
            }
            val list = runCatching { if (id == null) captures.list().map { row(it) } else null }
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
    /** Watch repetitions found offline in the raw log, on the video clock; null when the clocks cannot be aligned. */
    private fun watchSpans(id: String, capture: CalibrationCapture, sensor: ReceivedCapture?, frames: List<CameraPoseFrame>): List<RepSpan>? {
        sensor ?: return null
        val mapping = reviews.alignment(id).getOrNull() ?: return null
        val clock = VideoClockMap(frames.map { it.cameraTimestampNs to it.videoPresentationUs })
        return runCatching {
            PaperPeakRepAnalyzer.analyze(sensor.validated.document, capture.exerciseId).cycles.mapNotNull { cycle ->
                val start = clock.videoMs(mapping.phoneTimeNs(cycle.startNs, capture.phoneClockId, mapping.start.watchClockId))
                val end = clock.videoMs(mapping.phoneTimeNs(cycle.endNs, capture.phoneClockId, mapping.start.watchClockId))
                if (start == null || end == null || end <= start) null else RepSpan(start, end)
            }
        }.getOrNull()
    }

    private fun answerMismatch(startMs: Int, endMs: Int, videoOnly: Boolean, realRep: Boolean) {
        val id = selected ?: return
        if (busy || clip?.id != id) return
        val candidate = MismatchCandidate(RepSpan(startMs, endMs), if (videoOnly) MismatchSide.VIDEO_ONLY else MismatchSide.WATCH_ONLY)
        busy = true
        worker.execute {
            val result = answers.save(id, candidate, realRep)
            main.post {
                if (isDestroyed) return@post
                busy = false
                result.onSuccess { refresh() }.onFailure { notice = "답을 저장하지 못했어요. 다시 눌러 주세요." }
            }
        }
    }

    private fun row(capture: CalibrationCapture): CaptureRowUi {
        val date = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREAN).format(Date(capture.createdAtMs))
        val state = reviews.load(capture.id).getOrNull()?.countReference?.reps?.let { "${it}회 확인" }
            ?: if (capture.status == CalibrationCaptureStatus.INTERRUPTED) "중단됨" else "횟수 확인 전"
        return CaptureRowUi(capture.id, capture.exerciseName, "$date · $state")
    }

    private fun deleteCaptures(ids: Set<String>) {
        if (busy || ids.isEmpty()) return
        busy = true; notice = null
        worker.execute {
            val outcome = CalibrationCaptureDeletion(this).delete(ids)
            main.post {
                if (isDestroyed) return@post
                busy = false
                notice = when {
                    outcome.blocked.isEmpty() -> "${outcome.deleted.size}개 촬영을 삭제했어요."
                    outcome.deleted.isEmpty() -> "삭제하지 못했어요. ${outcome.blocked.values.first()} 남겨 뒀어요."
                    else -> "${outcome.deleted.size}개 삭제했어요. ${outcome.blocked.size}개는 ${outcome.blocked.values.first()} 남겨 뒀어요."
                }
                if (selected in outcome.deleted) { selected = null; clip = null }
                refresh()
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
