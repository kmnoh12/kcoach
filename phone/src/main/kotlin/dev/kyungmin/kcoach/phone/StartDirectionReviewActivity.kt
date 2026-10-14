package dev.kyungmin.kcoach.phone

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.activity.ComponentActivity
import dev.kyungmin.kcoach.core.*
import java.util.UUID
import java.util.concurrent.Executors

/** Human review and independent replay; a saved candidate does not activate a watch gate. */
class StartDirectionReviewActivity : ComponentActivity() {
    private val store by lazy { StartDirectionReviewStore(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private var generation = 0L

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); showList() }
    private fun root(title: String) {
        generation++
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding); setBackgroundColor(Color.rgb(9, 12, 11))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        content.addView(text(title, 24f))
    }
    private fun text(value: String, size: Float = 14f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.LTGRAY); setPadding(0, 8, 0, 8)
    }
    private fun button(title: String, action: () -> Unit) = Button(this).apply { text = title; setOnClickListener { action() } }
    private fun <T> load(task: () -> T, render: (Result<T>) -> Unit) {
        val expected = generation
        worker.execute {
            val result = runCatching(task)
            runOnUiThread { if (!isFinishing && !isDestroyed && generation == expected) render(result) }
        }
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun label(value: StartDirectionLabel) = when (value) {
        StartDirectionLabel.CORRECT_DIRECTION -> "지정한 시작 방향"
        StartDirectionLabel.OPPOSITE_DIRECTION -> "반대 손목 방향"
        StartDirectionLabel.EVERYDAY_MOVEMENT -> "일상 움직임"
    }
    private fun showList() {
        root("준비 손목 방향")
        content.addView(text("착용·수행 변형을 확인한 자료만 표시해요. 손목 방향 확인이며 팔꿈치·몸 전체의 자세 판정은 아니에요. 후보 저장만으로 운동 시작 검사를 켜지 않아요."))
        content.addView(button("확인한 자료로 방향 후보 만들기") { chooseTraining() })
        load({ store.captureIds().map { it to store.captureInfo(it) } to store.candidates().getOrThrow() }) { result ->
            result.onSuccess { (sources, candidates) ->
                if (sources.isEmpty()) content.addView(text("시작 변형·본체 장착 방향을 확인하고 촬영한 워치 원본이 아직 없어요."))
                sources.forEach { (id, info) -> content.addView(button(info.getOrNull()?.let {
                    "${it.name} · ${it.key.context.side} · ${id.takeLast(8)}"
                } ?: "${id.takeLast(8)} · 원본 확인 필요") { showCapture(id) }) }
                candidates.forEach { candidate -> content.addView(button("방향 후보 ${candidate.id.takeLast(8)} · v${candidate.version}") {
                    showCandidate(candidate.id, candidate.version)
                }) }
            }.onFailure { content.addView(text("방향 자료를 열지 못했어요. 원본과 저장 공간을 확인해 주세요.")) }
        }
    }
    private fun showCapture(id: String) {
        root("방향 구간 확인")
        load({ store.captureInfo(id).getOrThrow() to store.reviewRevision(id).getOrThrow() }) { result ->
            result.onSuccess { (info, revision) ->
                content.addView(text("${info.name}\n${info.key.context.side} · ${info.key.context.placement} · ${info.key.context.deviceTop}\n${info.key.context.variant}"))
                content.addView(text("직접 본 동작의 구간만 확인해 주세요. 센서 파형이나 횟수만 보고 올바른 방향이라고 판단하면 안 돼요. 초는 이 원본의 시작부터이며 최소 2초의 연속 센서가 필요해요."))
                val start = EditText(this).apply { hint = "시작 초"; setText("0"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
                val end = EditText(this).apply { hint = "끝 초"; inputType = start.inputType }
                content.addView(start); content.addView(end)
                val kinds = Spinner(this).apply { adapter = ArrayAdapter(this@StartDirectionReviewActivity,
                    android.R.layout.simple_spinner_dropdown_item, StartDirectionLabel.entries.map(::label)) }
                content.addView(kinds)
                val witness = CheckBox(this).apply { text = "이 구간의 동작과 손목 방향을 직접 보고 확인했어요"; setTextColor(Color.WHITE) }
                content.addView(witness)
                content.addView(button("직접 확인한 1구간으로 저장") {
                    val from = start.text.toString().toDoubleOrNull()
                    val until = end.text.toString().toDoubleOrNull()
                    val maximum = (info.lastNs - info.firstNs) / 1e9
                    if (!witness.isChecked || from == null || until == null || !from.isFinite() || !until.isFinite() ||
                        from < 0 || until > maximum || until - from < 2) {
                        toast("직접 확인한 연속 2초 이상 구간을 원본 범위 안에서 입력해 주세요.")
                    } else {
                        val mark = StartDirectionMark(info.firstNs + (from * 1e9).toLong(), info.firstNs + (until * 1e9).toLong(),
                            StartDirectionLabel.entries[kinds.selectedItemPosition])
                        fun save() = load({ store.saveInPersonReview(id, listOf(mark), revision).getOrThrow() }) { saved ->
                            saved.onSuccess { toast("직접 관찰한 방향 구간을 저장했어요."); showCapture(id) }
                                .onFailure { toast("구간 센서가 끊겼거나 확인 기록이 바뀌었어요. 다시 열어 확인해 주세요.") }
                        }
                        if (revision == 0L) save() else AlertDialog.Builder(this).setTitle("기존 방향 구간 교체")
                            .setMessage("이 원본의 기존 방향 라벨을 새 1구간으로 교체해요. 기존 라벨을 사용한 후보는 다시 만들어야 해요.")
                            .setNegativeButton("취소", null).setPositiveButton("교체 저장") { _, _ -> save() }.show()
                    }
                })
            }.onFailure { content.addView(text("원본·착용 조건을 확인하지 못했어요. 누락된 조건을 과거 자료에 추정해 붙이지 않아요.")) }
            content.addView(button("방향 자료 목록") { showList() })
        }
    }
    private fun selectSources(title: String, ids: List<String>, accept: (List<String>) -> Unit) {
        if (ids.isEmpty()) { toast("사람이 확인한 방향 자료가 먼저 필요해요."); return }
        val chosen = BooleanArray(ids.size)
        AlertDialog.Builder(this).setTitle(title).setMultiChoiceItems(ids.toTypedArray(), chosen) { _, i, checked -> chosen[i] = checked }
            .setNegativeButton("취소", null).setPositiveButton("선택") { _, _ -> accept(ids.filterIndexed { i, _ -> chosen[i] }) }.show()
    }
    private fun reviewedSources(task: (List<String>) -> Unit) {
        load({ store.captureIds().filter { store.loadReview(it).isSuccess } }) { result ->
            result.onSuccess(task).onFailure { toast("확인 자료를 읽지 못했어요.") }
        }
    }
    private fun chooseTraining() = reviewedSources { available ->
        selectSources("학습: 같은 조건의 지정 방향·반대 방향", available) { selected ->
            val margin = EditText(this).apply { hint = "실험용 허용 여유각 (0~45° 미만)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
            val dialog = AlertDialog.Builder(this).setTitle("방향 후보 만들기")
                .setMessage("선택한 지정 방향의 관측 범위에 더할 여유각을 입력해요. 검증된 기본 각도는 없으며 별도 자료로 통과 여부를 확인해야 해요.")
                .setView(margin).setNegativeButton("취소", null).setPositiveButton("후보 생성", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val degrees = margin.text.toString().toDoubleOrNull()
                if (degrees == null || !degrees.isFinite() || degrees <= 0 || degrees >= 45) margin.error = "0보다 크고 45보다 작은 각도"
                else { dialog.dismiss(); load({ store.buildCandidate("direction-${UUID.randomUUID()}", 1, selected, Math.toRadians(degrees)).getOrThrow() }) { result ->
                    result.onSuccess { showCandidate(it.id, it.version) }.onFailure {
                        toast("같은 착용·기기·변형의 지정 방향과 반대 방향이 연속 2초 관측돼야 해요. 일상 자료는 독립 검증에서 선택해 주세요.")
                    }
                } }
            } }; dialog.show()
        }
    }
    private fun showCandidate(id: String, version: Long) {
        root("방향 후보 · 독립 검증")
        load({ store.loadCandidate(id, version).getOrThrow() to store.loadValidatedCandidate(id, version).isSuccess }) { result ->
            result.onSuccess { (candidate, valid) ->
                content.addView(text("${candidate.key.context.exerciseId} · v$version\n학습 원본 ${candidate.sources.map { it.captureId }.distinct().size}개\n${if (valid) "저장된 독립 재생 검사 통과" else "독립 검증 대기 또는 실패"}"))
                content.addView(text("학습에 쓰지 않은 별도 원본의 지정 방향·반대 방향·일상 움직임을 모두 선택해 주세요. 같은 원본을 다시 쓰면 통과하지 않아요. 실제 워치 적용·정확도 향상은 별도 확인이 필요해요."))
                load({ store.selected(candidate.key.context).getOrNull() }) { selected -> selected.getOrNull()?.let { profile ->
                    if (profile.id == id && profile.version == version) content.addView(text(
                        if (PhoneCommandOutbox(this).directionStoredOnWatch(profile)) "최근 세트에서 이 기준을 워치에 저장한 응답을 확인했어요. 현재 손목 방향 확인 결과는 운동 화면에서 별도로 표시해요."
                        else "다음 새 세트에 전달하도록 선택돼 있어요. 워치 저장 응답은 아직 확인되지 않았어요."))
                } }
                if (valid) content.addView(button("다음 새 세트부터 이 방향 기준 사용") {
                    load({ store.activate(id, version).getOrThrow() }) { applied -> applied.onSuccess {
                        toast("다음 새 세트에 전달하도록 저장했어요. 현재 세트는 바뀌지 않아요.")
                    }.onFailure { toast("검증 자료가 바뀌었어요. 독립 검증을 다시 확인해 주세요.") } }
                })
                content.addView(button("이 착용 조건의 방향 검사 끄기") {
                    load({ store.disable(candidate.key.context).getOrThrow() }) { disabled -> disabled.onSuccess {
                        toast("다음 새 세트부터 방향 검사를 끄도록 저장했어요.")
                    }.onFailure { toast("설정을 저장하지 못했어요.") } }
                })
                content.addView(button("별도 원본으로 검증") {
                    reviewedSources { available -> selectSources("검증: 학습하지 않은 세 종류 자료", available.filter {
                        source -> candidate.sources.none { it.captureId == source }
                    }) { selected -> load({ store.validateCandidate(id, version, selected).getOrThrow() }) { report ->
                        report.onSuccess { outcome ->
                            toast(if (outcome.passed) "지정 방향만 준비 완료 · 독립 재생 검사 통과" else "검증 실패: ${outcome.failures.joinToString()}")
                            showCandidate(id, version)
                        }.onFailure { toast("착용 조건·원본 품질·확인 기록이 검증 조건과 맞지 않아요.") }
                    } } }
                })
            }.onFailure { content.addView(text("학습 원본이나 라벨이 바뀌었어요. 확인 자료에서 새 후보를 만들어 주세요.")) }
            content.addView(button("방향 자료 목록") { showList() })
        }
    }
    override fun onDestroy() { generation++; worker.shutdown(); super.onDestroy() }
}
