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
    private var nonRepIntervals = mutableListOf<NonRepInterval>()
    private var nonRepStart: Long? = null
    private var position = 0
    private var startMark: Long? = null
    private var turnMark: Long? = null
    private var splitIndex: Int? = null
    private var pauseStart: Pair<RepCycle, Long>? = null
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
        previewRequest.incrementAndGet()
        image?.setImageBitmap(null); imageBitmap?.recycle(); imageBitmap = null; image = null
        markerButtons = emptyList()
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 32); setBackgroundColor(Color.rgb(9, 12, 11)) }
        setContentView(ScrollView(this).apply { addView(content) })
    }
    private fun text(value: String, size: Float = 14f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.LTGRAY); setPadding(0, 6, 0, 6) }
    private fun button(title: String, action: () -> Unit) = Button(this).apply { text = title; setOnClickListener { action() } }
    private fun showList() {
        root(); content.addView(text("저장한 촬영", 24f))
        content.addView(button("개인 패턴·검증 현황") { startActivity(Intent(this, PatternCandidatesActivity::class.java)) })
        content.addView(button("준비 손목 방향 · 자료 확인") { startActivity(Intent(this, StartDirectionReviewActivity::class.java)) })
        val clips = captures.list()
        if (clips.isEmpty()) content.addView(text("아직 촬영 자료가 없어요."))
        clips.forEach { clip -> content.addView(button("${clip.exerciseName} · ${
            if (captures.videoFile(clip.id).isFile && clip.videoComplete) "영상 확인"
            else if (CalibrationVideoRetention(this).logsOnly(clip.id)) "로그만 보관" else "중단·영상 없음"
        }") { showCapture(clip.id) }) }
    }

    private fun showCapture(id: String) {
        val selected = captures.load(id).getOrElse { showList(); return }
        capture = selected
        review = reviews.load(id).getOrNull()
        frames = captures.frames(id).getOrDefault(emptyList()).filter { it.videoFrameWritten }
        cycles = reviews.cameraCycles(id).getOrDefault(emptyList()).toMutableList()
        nonRepIntervals = review?.nonRepIntervals.orEmpty().toMutableList()
        nonRepStart = null
        startMark = null; turnMark = null; splitIndex = null; position = 0
        pauseStart = null
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
        if (selected.status == CalibrationCaptureStatus.REVIEW_PENDING && selected.videoComplete && captures.videoFile(id).isFile) addVideoRetention(id)
        if (!captures.videoFile(id).isFile && selected.videoComplete) {
            content.addView(text(if (CalibrationVideoRetention(this).logsOnly(id))
                "로그만 보관 중이에요. 센서·관절·기존 확인 라벨은 남아 있으며 실제 영상으로 동작을 다시 확인하거나 새 영상 라벨을 만들 수는 없어요."
                else "원본 영상을 찾지 못했어요. 기존 로그와 라벨은 보관하지만 영상 재확인은 할 수 없어요."))
            content.addView(text(cameraObservedMetricsText(review?.cameraMetrics)))
            cycleRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(cycleRows)
            cycles.forEachIndexed { index, cycle ->
                cycleRows?.addView(text("확인한 ${index + 1}회 · ${"%.2f".format(cycle.durationMs / 1000.0)}초"))
                cycleRows?.addView(text(if (cycle.pauses.isEmpty()) "정지 구간 미확인 · 0초로 간주하지 않아요."
                    else "표시한 정지 합계 ${"%.2f".format(cycle.pauseMs / 1000.0)}초 · 영상 없이 새 정지 표시는 만들 수 없어요."))
            }
            nonRepIntervals.forEach { interval -> cycleRows?.addView(text("횟수 제외 · ${interval.reason.label} · ${"%.2f".format((interval.endNs - interval.startNs) / 1e9)}초")) }
            content.addView(button("개인 패턴·검증 현황") { startActivity(Intent(this, PatternCandidatesActivity::class.java)) })
            content.addView(button("촬영 목록") { showList() }); return
        }
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
        content.addView(button("영상 반복 구간 자동 제안") {
            val proposed = CameraRepProposals.propose(frames, selected.exerciseId, selected.watchOnLeft)
            if (proposed.isEmpty()) { toast("보이는 기준 위치로 완성된 반복을 제안하지 못했어요. 수동으로 확인해 주세요.") }
            else android.app.AlertDialog.Builder(this).setMessage("영상 후보 ${proposed.size}회예요. 실제 시작·전환·복귀 프레임을 확인하고 틀린 구간을 수정해 주세요. 현재 구간 목록을 바꿀까요?")
                .setNegativeButton("유지", null).setPositiveButton("후보로 채우기") { _, _ ->
                    cycles = proposed.toMutableList()
                    getSharedPreferences("camera-rep-proposals", MODE_PRIVATE).edit().putString(id, org.json.JSONArray(proposed.map { c -> org.json.JSONArray(listOf(c.startNs, c.turnaroundNs, c.endNs)) }).toString()).commit()
                    renderCycles(); toast("카메라 제안 · 아직 사람 확인 정답이 아니에요")
                }.show()
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
        if (selected.startWristContext?.completeForDirectionValidation == true) addDirectionReview(id, alignment)
        val simultaneous = CheckBox(this).apply {
            text = "양팔을 동시에 움직인 촬영이에요 (좌우 비교용)"
            setTextColor(Color.WHITE); isChecked = review?.cameraMetrics?.simultaneousArmsReviewed == true
        }
        content.addView(simultaneous)
        observedMetricsText = text(cameraObservedMetricsText(review?.cameraMetrics))
        content.addView(observedMetricsText)
        markerButtons = listOf(button("시작 위치 지정") { startMark = currentNs(); toast("첫 위치를 지정했어요.") },
            button("전환 위치 지정") { turnMark = currentNs(); toast("전환 위치를 지정했어요.") },
            button("복귀 지정 · 1회 추가") { addBoundary() },
            button("횟수 제외 구간 · 시작 지정") { nonRepStart = currentNs(); toast("제외할 동작의 끝으로 이동해 주세요.") },
            button("횟수 제외 구간 · 끝과 이유 지정") { addNonRepInterval() })
        markerButtons.forEach { content.addView(it) }
        content.addView(text("반복 사이 준비·멈춤·끝에 남은 반 반복·내려놓기·가림은 제외 구간으로 표시해 주세요. 완전 반복 안의 멈춤은 아래 반복 행에서 따로 표시해요."))
        content.addView(button("표시하던 구간 취소") { splitIndex = null; startMark = null; turnMark = null; nonRepStart = null; pauseStart = null; updateMarkerTitles() })
        cycleRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(cycleRows); renderCycles()
        content.addView(button("확인한 구간 저장") {
            if (pauseStart != null) { toast("표시하던 정지의 끝을 지정하거나 구간 표시를 취소해 주세요."); return@button }
            reviews.saveCycles(id, cycles, alignment.isChecked, review?.revision ?: 0, simultaneous.isChecked, nonRepIntervals).onSuccess {
                review = it
                observedMetricsText?.text = cameraObservedMetricsText(it.cameraMetrics)
                toast("완전 반복 ${it.references.size}개 · 제외 구간 ${it.nonRepIntervals.size}개 저장 · 보정 자료 조건 통과 ${it.references.count { ref -> ref.eligibleForLearning }}개")
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

    private fun addVideoRetention(id: String) {
        content.addView(button("영상도 보관") {
            worker.execute {
                val result = CalibrationVideoRetention(this).keepVideo(id)
                runOnUiThread { if (!isFinishing && !isDestroyed && capture?.id == id)
                    toast(if (result.isSuccess) "영상과 로그를 함께 보관해요." else "영상 보관 상태를 저장하지 못했어요.") }
            }
        })
        content.addView(button("확인 후 로그만 보관") {
            val expectedReview = review?.revision ?: 0L
            AlertDialog.Builder(this).setTitle("이 촬영의 영상만 삭제")
                .setMessage("실제 영상을 다시 볼 수 없어요. 센서·관절·이미 확인한 라벨·운동 기록은 남아요. 동작 확인을 마친 촬영만 선택해 주세요.")
                .setNegativeButton("취소", null).setPositiveButton("영상 삭제 · 로그 보관") { _, _ ->
                    previewRequest.incrementAndGet()
                    worker.execute {
                        val result = runCatching {
                            val expectedDirection = StartDirectionReviewStore(this).reviewRevision(id).getOrThrow()
                            CalibrationVideoRetention(this).discardVideo(id, expectedReview, expectedDirection).getOrThrow()
                        }
                        runOnUiThread {
                            if (isFinishing || isDestroyed || capture?.id != id) return@runOnUiThread
                            result.onSuccess { showCapture(id); toast("영상만 삭제하고 확인 로그를 보관했어요.") }
                                .onFailure { toast("삭제하지 못했어요. 동작 확인 저장·기록 변경·영상 상태를 확인해 주세요.") }
                        }
                    }
                }.show()
        })
    }

    private fun addDirectionReview(id: String, alignment: CheckBox) {
        var intervalStart: Long? = null
        val marks = mutableListOf<StartDirectionMark>()
        var expectedRevision: Long? = null
        val summary = text("손목 방향 구간을 영상으로 확인해 주세요. 전체 몸 자세를 판정하는 기능은 아니에요.")
        content.addView(summary)
        worker.execute {
            val revision = StartDirectionReviewStore(this).reviewRevision(id).getOrNull()
            runOnUiThread { if (!isFinishing && !isDestroyed && capture?.id == id) expectedRevision = revision }
        }
        content.addView(button("손목 방향 구간 시작 지정") {
            val ns = currentNs()
            if (ns == null) toast("영상 프레임이 표시된 뒤 지정해 주세요.")
            else { intervalStart = ns; toast("방향 구간의 시작을 지정했어요.") }
        })
        content.addView(button("손목 방향 구간 끝 · 종류 지정") {
            val start = intervalStart; val end = currentNs()
            if (start == null || end == null || end - start < 2_200_000_000L) {
                toast("시계 오차 여유를 포함해 2.2초 이상의 구간을 선택해 주세요.")
            } else {
                AlertDialog.Builder(this).setTitle("영상에서 확인한 손목 방향")
                    .setItems(arrayOf("지정한 시작 방향", "반대 손목 방향", "일상 움직임 · 운동 준비 아님")) { _, index ->
                        marks.add(StartDirectionMark(start, end, StartDirectionLabel.entries[index]))
                        intervalStart = null
                        summary.text = "선택한 방향 구간 ${marks.size}개 · 아직 저장 전"
                    }.setNegativeButton("취소", null).show()
            }
        })
        content.addView(button("영상으로 확인한 손목 방향 저장") {
            val revision = expectedRevision
            if (!alignment.isChecked || marks.isEmpty() || revision == null) {
                toast("동작 시점 정렬과 방향 구간을 먼저 확인해 주세요.")
            } else {
                val frozen = marks.toList()
                worker.execute {
                    val store = StartDirectionReviewStore(this)
                    val result = store.saveVideoReview(id, frozen, true, revision)
                    runOnUiThread {
                        if (isFinishing || isDestroyed || capture?.id != id) return@runOnUiThread
                        result.onSuccess { expectedRevision = it.revision; summary.text = "영상 확인 방향 ${it.intervals.size}개 저장 · 후보·독립 검증 전" }
                            .onFailure { toast("저장하지 못했어요. 구간 겹침·2초 연속 센서·영상 종료·시간 정렬을 확인해 주세요.") }
                    }
                }
            }
        })
        content.addView(button("방향 후보·독립 검증") { startActivity(Intent(this, StartDirectionReviewActivity::class.java)) })
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
    private fun addNonRepInterval() {
        val start = nonRepStart ?: return toast("제외 구간의 시작을 먼저 지정해 주세요.")
        val end = currentNs() ?: return
        if (end <= start || cycles.any { it.startNs < end && start < it.endNs } ||
            nonRepIntervals.any { it.startNs < end && start < it.endNs }) {
            toast("시작보다 뒤의 프레임을 선택하고, 완전 반복·다른 제외 구간과 겹치지 않게 해 주세요.")
            return
        }
        AlertDialog.Builder(this).setTitle("횟수에서 제외한 이유")
            .setItems(NonRepReason.entries.map { it.label }.toTypedArray()) { _, index ->
                nonRepIntervals.add(NonRepInterval(start, end, NonRepReason.entries[index]))
                nonRepIntervals.sortBy { it.startNs }; nonRepStart = null; renderCycles()
            }.show()
    }
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
                require(nonRepIntervals.none { it.startNs < cycle.endNs && cycle.startNs < it.endNs })
                cycles.add(cycle); cycles.sortBy { it.startNs }
            } else {
                val old = cycles[index]
                val replacements = old.split(first, turn, end)
                cycles.removeAt(index); cycles.addAll(index, replacements)
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
            rows.addView(text(if (cycle.pauses.isEmpty()) "정지 구간 미확인 · 0초로 간주하지 않아요."
                else "표시한 정지 합계 ${"%.2f".format(cycle.pauseMs / 1000.0)}초 · 이 반복은 현재 개인 패턴 학습에서 제외해요."))
            rows.addView(LinearLayout(this).apply {
                addView(button("정지 시작 지정") {
                    val at = currentNs()
                    if (at == null || at !in cycle.startNs until cycle.endNs) toast("이 반복 안의 정지 시작 프레임을 선택해 주세요.")
                    else { pauseStart = cycle to at; toast("정지의 끝 프레임을 선택해 주세요.") }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("정지 끝 · 추가") {
                    runCatching {
                        val mark = requireNotNull(pauseStart); require(mark.first == cycle)
                        val pause = RepPause(mark.second, currentNs() ?: error("No frame"))
                        cycles[index] = cycle.copy(pauses = (cycle.pauses + pause).sortedBy { it.startNs })
                        pauseStart = null; renderCycles()
                    }.onFailure { toast("같은 반복의 시작·끝 프레임을 순서대로 선택하고 기존 정지와 겹치지 않게 해 주세요.") }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            cycle.pauses.forEachIndexed { pauseIndex, pause ->
                rows.addView(text("표시한 정지 ${pauseIndex + 1} · ${"%.2f".format((pause.startNs - origin) / 1e9)} → ${"%.2f".format((pause.endNs - origin) / 1e9)}초"))
                rows.addView(LinearLayout(this).apply {
                    addView(button("정지 경계 수정") {
                        AlertDialog.Builder(this@CalibrationReviewActivity).setItems(arrayOf("현재 프레임을 정지 시작으로", "현재 프레임을 정지 끝으로")) { _, field ->
                            runCatching {
                                val at = currentNs() ?: error("No frame")
                                val edit = if (field == 0) pause.copy(startNs = at) else pause.copy(endNs = at)
                                val updated = cycle.pauses.toMutableList().apply { this[pauseIndex] = edit }.sortedBy { it.startNs }
                                cycles[index] = cycle.copy(pauses = updated); pauseStart = null; renderCycles()
                            }.onFailure { toast("반복 안의 실제 프레임으로, 정지 구간끼리 겹치지 않게 수정해 주세요.") }
                        }.show()
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(button("정지 표시 지우기") {
                        cycles[index] = cycle.copy(pauses = cycle.pauses.filterIndexed { at, _ -> at != pauseIndex })
                        pauseStart = null; renderCycles()
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                })
            }
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
                addView(button("제외") { cycles.removeAt(index); splitIndex = null; pauseStart = null; renderCycles(); updateMarkerTitles() }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            rows.addView(LinearLayout(this).apply {
                addView(button("두 반복으로 분할") {
                    splitIndex = index; startMark = null; turnMark = null; pauseStart = null; updateMarkerTitles()
                    toast("첫 전환·나눌 복귀·둘째 전환을 차례로 지정해 주세요. 기존 정지 표시는 보존돼요.")
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (index < cycles.lastIndex) addView(button("다음과 합치기") {
                    runCatching {
                        val combined = cycle.merge(cycles[index + 1], currentNs() ?: error("No frame"))
                        cycles[index] = combined; cycles.removeAt(index + 1); pauseStart = null; renderCycles()
                    }.onFailure { toast("합칠 반복 안의 전환 프레임을 선택해 주세요. 기존 정지 표시는 보존돼요.") }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        nonRepIntervals.forEachIndexed { index, interval ->
            rows.addView(text("횟수 제외 · ${interval.reason.label} · ${"%.2f".format((interval.startNs - origin) / 1e9)} → ${"%.2f".format((interval.endNs - origin) / 1e9)}초"))
            rows.addView(button("제외 표시 지우기") { nonRepIntervals.removeAt(index); renderCycles() })
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
