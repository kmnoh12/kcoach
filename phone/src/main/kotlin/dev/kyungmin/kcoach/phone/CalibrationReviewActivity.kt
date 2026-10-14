package dev.kyungmin.kcoach.phone

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.kyungmin.kcoach.core.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** The user labels observable video frames; counts alone never fabricate boundaries. */
class CalibrationReviewActivity : ComponentActivity() {
    private val captures by lazy { CalibrationCaptureStore(this) }
    private val reviews by lazy { CalibrationReviewStore(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val previewRequest = AtomicLong()
    private var imageBitmap: Bitmap? = null
    private var capture: CalibrationCapture? = null
    private var review: CalibrationReview? = null
    private var frames = emptyList<CameraPoseFrame>()
    private var cycles = mutableListOf<RepCycle>()
    private var position = 0
    private var startMark: Long? = null
    private var turnMark: Long? = null
    private var splitIndex: Int? = null
    private lateinit var content: LinearLayout
    private var image: ImageView? = null
    private var time: TextView? = null
    private var chart: SignalView? = null
    private var cycleRows: LinearLayout? = null
    private var markerButtons = emptyList<Button>()
    private var countButton: Button? = null
    private var displayedPosition: Int? = null
    private var observedMetricsText: TextView? = null
    private var exportCaptureId: String? = null
    private var exportVideo = false
    private val createEvidenceDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = exportCaptureId
        val includeVideo = exportVideo
        exportCaptureId = null
        if (uri != null && id != null) {
            Toast.makeText(this, "선택한 검증 자료 저장 중…", Toast.LENGTH_SHORT).show()
            worker.execute {
                val result = runCatching {
                    val output = contentResolver.openOutputStream(uri, "w") ?: error("Selected output unavailable")
                    output.use { CalibrationEvidenceExport(this).write(id, includeVideo, it).getOrThrow() }
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) Toast.makeText(this,
                        if (result.isSuccess) "검증 자료 ZIP을 저장했어요. 앱에서 외부로 전송하지 않았어요."
                        else "저장하지 못했어요. 워치 원본 수신과 촬영 종료 상태를 확인해 주세요. 실패한 파일은 검증 자료로 사용하지 마세요.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportCaptureId = savedInstanceState?.getString("exportCaptureId")
        exportVideo = savedInstanceState?.getBoolean("exportVideo") ?: false
        val requested = savedInstanceState?.getString("selectedCaptureId") ?: intent.getStringExtra("captureId")
        if (requested != null) showCapture(requested) else showList()
    }

    private fun root() {
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 32); setBackgroundColor(Color.rgb(9, 12, 11)) }
        setContentView(ScrollView(this).apply { addView(content) })
    }
    private fun text(value: String, size: Float = 14f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.LTGRAY); setPadding(0, 6, 0, 6) }
    private fun button(title: String, action: () -> Unit) = Button(this).apply { text = title; setOnClickListener { action() } }
    private fun showList() {
        root(); content.addView(text("저장한 촬영", 24f))
        content.addView(button("개인 패턴·검증 현황") { startActivity(Intent(this, PatternCandidatesActivity::class.java)) })
        val clips = captures.list()
        if (clips.isEmpty()) content.addView(text("아직 촬영 자료가 없어요."))
        clips.forEach { clip -> content.addView(button("${clip.exerciseName} · ${if (clip.videoComplete) "영상 확인" else "중단 자료"}") { showCapture(clip.id) }) }
    }

    private fun showCapture(id: String) {
        val selected = captures.load(id).getOrElse { showList(); return }
        capture = selected
        review = reviews.load(id).getOrNull()
        frames = captures.frames(id).getOrDefault(emptyList()).filter { it.videoFrameWritten }
        cycles = reviews.cameraCycles(id).getOrDefault(emptyList()).toMutableList()
        startMark = null; turnMark = null; splitIndex = null; position = 0
        root()
        content.addView(text("${selected.exerciseName}\n반복 구간 확인", 24f))
        content.addView(text(if (selected.videoComplete) "영상에서 실제 동작을 확인하고 시작·전환·복귀 위치를 표시해 주세요."
            else "중단된 자료예요. 원본은 보관하며 이번 자료로 자동 보정하지 않아요."))
        content.addView(button("선택한 검증 자료 내보내기") {
            val includeVideo = CheckBox(this).apply {
                text = "촬영 영상도 포함 (기본은 센서·관절 좌표·확인 라벨·시계 기록만)"
                isChecked = false; isEnabled = captures.videoFile(id).isFile
            }
            AlertDialog.Builder(this).setTitle("검증 자료 ZIP 저장")
                .setMessage("선택한 촬영의 운동·센서·신체 관절 좌표가 포함돼요. 계정 토큰과 다른 앱 설정은 제외해요. 저장 위치는 직접 선택하고, 앱이 자동으로 외부에 보내지 않아요.")
                .setView(includeVideo).setNegativeButton("취소", null).setPositiveButton("저장 위치 선택") { _, _ ->
                    exportCaptureId = id; exportVideo = includeVideo.isChecked
                    createEvidenceDocument.launch("kcoach-evidence-${id.takeLast(8)}.zip")
                }.show()
        }.apply { isEnabled = selected.status != CalibrationCaptureStatus.RECORDING })
        val savedCount = review?.countReference?.reps
        countButton = button("실제 총 횟수: ${savedCount?.let { "${it}회" } ?: "아직 확인 안 됨"}") {
            val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "실제 횟수"; setText(review?.countReference?.reps?.toString().orEmpty()) }
            val dialog = AlertDialog.Builder(this).setTitle("실제 총 횟수").setView(input).setNegativeButton("취소", null).setPositiveButton("저장", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val count = input.text.toString().toIntOrNull()?.takeIf { it in 0..9_999 }
                if (count == null) { input.error = "실제 횟수를 입력해 주세요"; return@setOnClickListener }
                reviews.saveCount(id, count, review?.revision ?: 0).onSuccess {
                    review = it; dialog.dismiss(); countButton?.text = "실제 총 횟수: ${it.countReference!!.reps}회"
                }
                    .onFailure { input.error = "확인한 구간 수보다 적거나 기록이 바뀌었어요. 다시 확인해 주세요." }
            } }; dialog.show()
        }
        content.addView(countButton)
        if (!selected.videoComplete || frames.isEmpty()) {
            content.addView(text("횟수만 확인한 기록에는 반복 경계나 자세 정답을 만들지 않아요."))
            content.addView(button("촬영 목록") { showList() }); return
        }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        content.addView(image, LinearLayout.LayoutParams(-1, 700))
        time = text("0.00초"); content.addView(time)
        val slider = SeekBar(this).apply {
            max = frames.lastIndex
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) selectFrame(progress) }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        content.addView(slider)
        content.addView(LinearLayout(this).apply {
            addView(button("이전 프레임") { selectFrame((position - 1).coerceAtLeast(0)); slider.progress = position }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button("다음 프레임") { selectFrame((position + 1).coerceAtMost(frames.lastIndex)); slider.progress = position }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        val mapping = reviews.alignment(id).getOrNull()
        val received = captures.sensor(id).getOrNull()
        content.addView(text(if (mapping == null) "워치 원본 또는 시간 확인이 부족해요. 자료를 받은 뒤 새로고침해 주세요."
            else "시간 교환 오차 범위 약 ${mapping.uncertaintyNs / 1_000_000}ms · 아래 두 신호에서 동작 시점을 비교해 주세요."))
        chart = SignalView().apply {
            cameraSeries = frames.mapNotNull { frame ->
                if (!frame.uniquelyVisible) return@mapNotNull null
                val wrist = frame.imageLandmarks.single()[if (selected.watchOnLeft) 15 else 16]
                if ((wrist.visibility ?: 0.0) < 0.65 || (wrist.presence ?: 0.0) < 0.65) null
                else frame.cameraTimestampNs to wrist.y
            }
            watchSeries = if (mapping != null && received != null) received.validated.document.records.mapNotNull { record ->
                runCatching { mapping.phoneTimeNs(record.sample.timestampNs, selected.phoneClockId, mapping.start.watchClockId) }.getOrNull()?.let { ns ->
                    ns to sqrt(record.sample.gyroscopeX.toDouble() * record.sample.gyroscopeX + record.sample.gyroscopeY.toDouble() * record.sample.gyroscopeY + record.sample.gyroscopeZ.toDouble() * record.sample.gyroscopeZ)
                }
            } else emptyList()
        }
        content.addView(chart, LinearLayout.LayoutParams(-1, 220))
        content.addView(text("민트: 영상 속 손목 높이 · 주황: 워치 회전 움직임\n단위가 다른 신호를 각각 보기 편하게 표시했어요. 파형 모양이 같다고 정답으로 판정하지 않아요."))
        val alignment = CheckBox(this).apply {
            text = "처음·중간·끝의 동작 시점이 두 자료에서 맞는지 확인했어요"
            setTextColor(Color.WHITE); isEnabled = mapping != null && received != null
        }
        content.addView(alignment)
        val simultaneous = CheckBox(this).apply {
            text = "양팔을 동시에 움직인 촬영이에요 (좌우 비교용)"
            setTextColor(Color.WHITE); isChecked = review?.cameraMetrics?.simultaneousArmsReviewed == true
        }
        content.addView(simultaneous)
        observedMetricsText = text(cameraObservedMetricsText(review?.cameraMetrics))
        content.addView(observedMetricsText)
        markerButtons = listOf(button("시작 위치 지정") { startMark = currentNs(); toast("첫 위치를 지정했어요.") },
            button("전환 위치 지정") { turnMark = currentNs(); toast("전환 위치를 지정했어요.") },
            button("복귀 지정 · 1회 추가") { addBoundary() })
        markerButtons.forEach { content.addView(it) }
        content.addView(button("표시하던 구간 취소") { splitIndex = null; startMark = null; turnMark = null; updateMarkerTitles() })
        cycleRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(cycleRows); renderCycles()
        content.addView(button("확인한 구간 저장") {
            reviews.saveCycles(id, cycles, alignment.isChecked, review?.revision ?: 0, simultaneous.isChecked).onSuccess {
                review = it
                observedMetricsText?.text = cameraObservedMetricsText(it.cameraMetrics)
                toast("${it.references.size}개 구간 저장 · 보정 자료 조건 통과 ${it.references.count { ref -> ref.eligibleForLearning }}개")
            }.onFailure { toast("구간·총 횟수 또는 영상 상태를 다시 확인해 주세요.") }
        })
        content.addView(button("원본 수신 상태 새로고침") { showCapture(id) })
        if (ExerciseTrackingProfiles.resolve(selected.exerciseId).mode != TrackingMode.MANUAL_REQUIRED) content.addView(button("확인한 자료로 보정 후보 만들기") {
            toast("같은 동작·착용 조건의 확인 자료를 모으고 있어요.")
            worker.execute {
                val built = PatternCandidateStore(this).buildForCapture(id)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    built.onSuccess {
                        startActivity(Intent(this, PatternCandidatesActivity::class.java).putExtra("candidateId", it.profile.id))
                    }.onFailure { toast("보정 후보를 만들지 못했어요. 워치 방향·원본 수신·동작 정렬과 확인된 반복 3회 이상을 확인해 주세요.") }
                }
            }
        }) else content.addView(text("이 운동은 손목 자동 측정 대상이 아니에요. 영상과 실제 횟수는 보관하며 워치 횟수 보정 후보를 만들지 않아요."))
        content.addView(button("촬영 목록") { showList() })
        selectFrame(0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selectedCaptureId", capture?.id)
        outState.putString("exportCaptureId", exportCaptureId)
        outState.putBoolean("exportVideo", exportVideo)
        super.onSaveInstanceState(outState)
    }

    private fun selectFrame(index: Int) {
        position = index
        displayedPosition = null
        markerButtons.forEach { it.isEnabled = false }
        val frame = frames.getOrNull(index) ?: return
        time?.text = "${"%.2f".format(frame.videoPresentationUs / 1_000_000.0)}초 · ${index + 1}/${frames.size} 프레임"
        chart?.selectedNs = frame.cameraTimestampNs; chart?.invalidate()
        val id = capture?.id ?: return
        val request = previewRequest.incrementAndGet()
        worker.execute {
            if (request != previewRequest.get()) return@execute
            val bitmap = runCatching { MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(captures.videoFile(id).absolutePath)
                retriever.getFrameAtTime(frame.videoPresentationUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } }.getOrNull()
            runOnUiThread {
                if (request != previewRequest.get() || isFinishing) bitmap?.recycle()
                else {
                    image?.setImageBitmap(bitmap); imageBitmap?.recycle(); imageBitmap = bitmap
                    displayedPosition = if (bitmap != null) index else null
                    markerButtons.forEach { it.isEnabled = bitmap != null }
                }
            }
        }
    }

    private fun currentNs() = frames.getOrNull(position)?.cameraTimestampNs?.takeIf { displayedPosition == position }
    private fun addBoundary() {
        val first = startMark ?: return toast("첫 위치를 먼저 지정해 주세요.")
        val turn = turnMark ?: return toast("전환 위치를 먼저 지정해 주세요.")
        val end = currentNs() ?: return
        runCatching {
            val phase = ExerciseMeasurementContracts.resolve(capture!!.exerciseId).departurePhase
            val index = splitIndex
            if (index == null) {
                val cycle = RepCycle(first, turn, end, phase)
                require(cycles.none { it.startNs < cycle.endNs && cycle.startNs < it.endNs })
                cycles.add(cycle); cycles.sortBy { it.startNs }
            } else {
                val old = cycles[index]
                val a = RepCycle(old.startNs, first, turn, phase)
                val b = RepCycle(turn, end, old.endNs, phase)
                cycles.removeAt(index); cycles.addAll(index, listOf(a, b))
            }
            splitIndex = null; startMark = null; turnMark = null; updateMarkerTitles(); renderCycles()
        }.onFailure { toast("시작 → 전환 → 복귀 순서와 겹치는 구간을 확인해 주세요.") }
    }

    private fun renderCycles() {
        val rows = cycleRows ?: return
        rows.removeAllViews()
        val origin = frames.firstOrNull()?.cameraTimestampNs ?: 0
        cycles.forEachIndexed { index, cycle ->
            rows.addView(text("${index + 1}회 · ${"%.2f".format((cycle.startNs - origin) / 1e9)} → ${"%.2f".format((cycle.turnaroundNs - origin) / 1e9)} → ${"%.2f".format((cycle.endNs - origin) / 1e9)}초"))
            rows.addView(LinearLayout(this).apply {
                addView(button("경계 수정") {
                    AlertDialog.Builder(this@CalibrationReviewActivity).setItems(arrayOf("현재 화면을 시작으로", "현재 화면을 전환으로", "현재 화면을 복귀로")) { _, field ->
                        runCatching {
                            val value = currentNs() ?: error("No frame")
                            val edited = when (field) { 0 -> cycle.copy(startNs = value); 1 -> cycle.copy(turnaroundNs = value); else -> cycle.copy(endNs = value) }
                            require((index == 0 || cycles[index - 1].endNs <= edited.startNs) && (index == cycles.lastIndex || cycles[index + 1].startNs >= edited.endNs))
                            cycles[index] = edited; renderCycles()
                        }.onFailure { toast("다른 구간과 겹치거나 순서가 바뀌었어요.") }
                    }.show()
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("제외") { cycles.removeAt(index); splitIndex = null; renderCycles(); updateMarkerTitles() }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            rows.addView(LinearLayout(this).apply {
                addView(button("두 반복으로 분할") { splitIndex = index; startMark = null; turnMark = null; updateMarkerTitles(); toast("영상에서 첫 전환·나눌 복귀·둘째 전환을 차례로 지정해 주세요.") }, LinearLayout.LayoutParams(0, -2, 1f))
                if (index < cycles.lastIndex) addView(button("다음과 합치기") {
                    runCatching {
                        val combined = RepCycle(cycle.startNs, currentNs() ?: error("No frame"), cycles[index + 1].endNs, cycle.departurePhase)
                        cycles[index] = combined; cycles.removeAt(index + 1); renderCycles()
                    }.onFailure { toast("합칠 반복의 전환 위치를 영상에서 선택해 주세요.") }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
    }

    private fun updateMarkerTitles() {
        val titles = if (splitIndex == null) listOf("시작 위치 지정", "전환 위치 지정", "복귀 지정 · 1회 추가")
            else listOf("첫 전환 지정", "나눌 복귀 지정", "둘째 전환 지정 · 분할")
        markerButtons.zip(titles).forEach { (button, title) -> button.text = title }
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun onDestroy() { previewRequest.incrementAndGet(); worker.shutdown(); image?.setImageBitmap(null); imageBitmap?.recycle(); super.onDestroy() }

    private inner class SignalView : View(this@CalibrationReviewActivity) {
        var cameraSeries = emptyList<Pair<Long, Double>>()
        var watchSeries = emptyList<Pair<Long, Double>>()
        var selectedNs = 0L
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val from = frames.firstOrNull()?.cameraTimestampNs ?: return
            val until = frames.lastOrNull()?.cameraTimestampNs ?: return
            if (until <= from) return
            fun draw(values: List<Pair<Long, Double>>, color: Int) {
                val visible = values.filter { it.first in from..until }
                if (visible.size < 2) return
                val min = visible.minOf { it.second }; val range = (visible.maxOf { it.second } - min).coerceAtLeast(1e-6)
                paint.color = color
                visible.zipWithNext().forEach { (a, b) ->
                    if (b.first - a.first > 150_000_000L) return@forEach
                    canvas.drawLine(((a.first - from).toDouble() / (until - from) * width).toFloat(), ((1 - (a.second - min) / range) * height).toFloat(),
                        ((b.first - from).toDouble() / (until - from) * width).toFloat(), ((1 - (b.second - min) / range) * height).toFloat(), paint)
                }
            }
            draw(cameraSeries, Color.rgb(30, 210, 190)); draw(watchSeries, Color.rgb(250, 170, 60))
            paint.color = Color.WHITE
            val x = ((selectedNs - from).toDouble() / (until - from) * width).toFloat()
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        }
    }
}
