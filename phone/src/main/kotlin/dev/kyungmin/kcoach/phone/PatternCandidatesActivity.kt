package dev.kyungmin.kcoach.phone

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import dev.kyungmin.kcoach.core.*
import java.util.concurrent.Executors
import java.util.Locale

/** Candidate creation is distinct from proof of improvement and from applied watch settings. */
class PatternCandidatesActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val store by lazy { PatternCandidateStore(this) }
    private val controller by lazy { PatternAdoptionController(this) }
    private val bridge by lazy { PhoneWearBridge(this) }
    private lateinit var content: LinearLayout
    private var viewRevision = 0L
    private var receiverRegistered = false
    private var candidateListVisible = false
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (candidateListVisible) showCandidates() }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }
    private fun root(title: String) {
        viewRevision++
        candidateListVisible = false
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 32); setBackgroundColor(Color.rgb(9, 12, 11)) }
        setContentView(ScrollView(this).apply { addView(content) }); content.addView(text(title, 24f))
    }
    private fun text(value: String, size: Float = 14f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.LTGRAY); setPadding(0, 10, 0, 10) }
    private fun button(title: String, action: () -> Unit) = Button(this).apply { text = title; setOnClickListener { action() } }

    private fun showCandidates() {
        root("내 측정 엔진")
        candidateListVisible = true
        content.addView(text("후보를 만든 뒤 새 세트를 촬영하고 실제 횟수·반복 구간을 확인해 주세요. 학습했던 세트를 다시 맞히는 것으로 정확도가 높아졌다고 표시하지 않아요."))
        val status = text("저장한 후보 확인 중…"); content.addView(status)
        val revision = viewRevision
        worker.execute {
            val records = store.list()
            val reports = records.getOrNull().orEmpty().associate { it.profile.id to store.savedValidationReports(it.profile.id).getOrDefault(emptyList()) }
            val states = records.getOrNull().orEmpty().associate { it.profile.id to controller.state(it.profile.id) }
            runOnUiThread {
                if (isFinishing || isDestroyed || revision != viewRevision) return@runOnUiThread
                records.onSuccess { list ->
                    status.text = if (list.isEmpty()) "아직 후보가 없어요. 촬영한 동작을 확인한 뒤 보정 후보를 만들어 주세요." else "${list.size}개 후보 · 적용 상태는 워치의 실제 사용 확인 기준"
                    var group: String? = null
                    list.sortedWith(compareBy<PatternCandidate> { it.profile.compatibility.recipe.name + it.profile.compatibility.side + it.profile.compatibility.placement }.thenByDescending { it.profile.version }).forEach { candidate ->
                        val key = "${candidate.profile.compatibility.recipe.label} · ${candidate.profile.compatibility.side} · ${candidate.profile.compatibility.placement}"
                        if (group != key) { group = key; content.addView(text(key, 22f)) }
                        val profile = candidate.profile
                        val description = "${profile.compatibility.recipe.label} · 버전 ${profile.version}\n확인 반복 ${profile.trainingRepCount}회 · ${profile.sources.size}세트 · ${profile.sources.map { it.dayId }.distinct().size}일"
                        content.addView(text(description, 18f))
                        content.addView(text("확인 자료 날짜 · ${profile.sources.minOf { it.dayId }} ~ ${profile.sources.maxOf { it.dayId }}"))
                        reports[profile.id].orEmpty().forEach { report ->
                            val native = report.optString("scope") == "OBSERVED_WATCH_OUTPUTS"
                            content.addView(text("저장한 ${if (native) "워치 실측" else "폰 독립"} 검증 · ${java.time.Instant.ofEpochMilli(report.getLong("evaluatedAtMs"))}\n${report.optInt("sets")}세트 · ${report.optInt("reps")}회 · ${report.optInt("days")}일"))
                            content.addView(text("당시의 횟수 오차 ${report.opt("mae") ?: report.opt("rawMae") ?: "미측정"}회 · 적용 전 원본과 라벨을 다시 검사해요"))
                        }
                        val state = states[profile.id]?.getOrNull()
                        if (state?.previousObserved != null) content.addView(text("이전 사용 확인 · 버전 ${state.previousObserved!!.command.runtime.version}"))
                        content.addView(text(state?.let(::describeState) ?: "적용 상태를 확인하지 못했어요. 저장 자료는 유지돼요."))
                        content.addView(button("새 검증 세트 촬영") {
                            val capture = CalibrationCaptureStore(this).load(profile.sources.first().captureId).getOrNull()
                            val exerciseId = capture?.exerciseId ?: profile.exerciseConditions.sorted().first()
                            startActivity(Intent(this, CalibrationCameraActivity::class.java)
                                .putExtra("exerciseId", exerciseId).putExtra("exerciseName", capture?.exerciseName ?: profile.compatibility.recipe.label)
                                .putExtra("candidateId", profile.id)
                                .putExtra("watchOnLeft", profile.compatibility.side == "LEFT").putExtra("watchOrientation", profile.compatibility.placement))
                        })
                        content.addView(button("새 자료로 이전 방식과 비교") { evaluate(candidate) })
                        content.addView(button("워치 실측 기록 검증") { evaluateNative(candidate) })
                        content.addView(button("확인한 원본을 비교 자료로 고정") { regression(candidate, true) })
                        content.addView(button("고정 자료로 이전 보정값과 비교") { regression(candidate, false) })
                        if (state?.observedActive?.command?.runtime?.id != profile.id)
                            content.addView(button("검증 후 워치에 적용 요청") { transition(candidate, rollback = false) })
                        if (state?.observedActive?.command?.runtime?.id == profile.id && state.previousObserved != null)
                            content.addView(button("이전에 사용한 보정값으로 되돌리기") { transition(candidate, rollback = true) })
                        if (state?.observedActive?.command?.runtime?.id == profile.id)
                            content.addView(button("개인 보정 끄고 기본 측정으로 되돌리기") { transition(candidate, rollback = false, disable = true) })
                    }
                }.onFailure { status.text = "저장 자료를 읽지 못했어요. 자료는 보관돼 있으며 보정 설정을 적용하지 않았어요." }
            }
        }
        content.addView(button("촬영·동작 확인") { startActivity(Intent(this, CalibrationReviewActivity::class.java)) })
    }

    private fun describeState(state: PatternAdoptionState): String {
        val current = state.observedActive?.command?.runtime
        val selected = current?.id == state.candidate.profile.id
        val base = if (selected) "현재 사용 확인 · 버전 ${current!!.version}" else if (current == null)
            "보정 후보 · 개인 보정의 실제 사용 확인 없음" else "보정 후보 · 현재 사용 확인 버전 ${current.version}"
        val request = state.transition ?: return base
        return base + "\n" + when (request.ack?.status) {
            null -> "버전 ${request.command.runtime.version} 변경 요청 저장 · 워치 수신 대기"
            PatternProfileStatus.STAGED -> "버전 ${request.command.runtime.version} 워치 수신 완료 · 다음 세트 실제 사용 확인 대기"
            PatternProfileStatus.APPLIED -> "버전 ${request.command.runtime.version} 실제 센서 처리 확인 완료"
            PatternProfileStatus.DISABLED -> "기본 측정으로 되돌린 뒤 실제 센서 처리 확인 완료"
            PatternProfileStatus.CONFLICT -> "워치 설정이 달라졌어요. 연결 후 다시 검증해 주세요."
            PatternProfileStatus.WRONG_DEVICE -> "선택한 보정값과 워치 기기가 달라 적용되지 않았어요."
            else -> "변경 요청이 적용되지 않았어요. 현재 사용 확인 상태를 유지해요."
        }
    }


    private fun transition(candidate: PatternCandidate, rollback: Boolean, disable: Boolean = false) {
        root(if (disable) "기본 측정으로 되돌리기" else if (rollback) "이전 보정값으로 되돌리기" else "개인 패턴 적용")
        val summary = text(if (rollback || disable) "실제 사용한 설정과 원본 확인 중…" else "새 자료·워치 실측·고정 원본을 다시 검증 중…")
        content.addView(summary)
        val revision = viewRevision
        worker.execute {
            val result = if (disable) controller.disable(candidate.profile.id) else if (rollback) controller.rollback(candidate.profile.id) else controller.activate(candidate.profile.id)
            result.onSuccess { bridge.retryPendingCommand() }
            runOnUiThread {
                if (isFinishing || isDestroyed || revision != viewRevision) return@runOnUiThread
                summary.text = result.fold({ "변경 요청을 저장했어요.\n진행 중인 세트는 같은 설정을 유지하고, 다음 세트에서 워치가 실제 사용한 뒤 적용 완료로 표시해요." },
                    { "변경 요청을 보내지 않았어요.\n검증 자료 부족·확인한 원본/라벨 변경·진행 중인 다른 요청을 확인해 주세요. 기존 사용 상태는 유지돼요." })
                content.addView(button("후보·적용 상태 확인") { showCandidates() })
            }
        }
    }

    private fun regression(candidate: PatternCandidate, freeze: Boolean) {
        root("${candidate.profile.compatibility.recipe.label} · 과거 자료 비교")
        val summary = text("확인한 원본과 라벨 점검 중…"); content.addView(summary)
        val revision = viewRevision
        worker.execute {
            val result = runCatching {
                if (freeze) {
                    val saved = store.freezeRegressionDataset(candidate.profile.id).getOrThrow()
                    "확인 자료 ${saved.entries.size}세트를 고정했어요.\n새 후보를 평가할 때 같은 원본과 라벨로 비교해요."
                } else {
                    val reports = store.compareRegression(candidate.profile.id).getOrThrow()
                    if (reports.all { it.passed }) "과거 확인 자료에서 횟수·경계가 나빠지지 않았어요.\n새 촬영 자료와 실제 워치 검증은 별도로 필요해요."
                    else "이전 설정 유지\n" + reports.flatMap { it.failures }.distinct().map(::explainFailure).joinToString("\n")
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || revision != viewRevision) return@runOnUiThread
                summary.text = result.getOrElse { "고정한 원본·확인 라벨·운동 조건이 바뀌었거나 자료를 읽지 못했어요. 설정을 적용하지 않았어요." }
                content.addView(text("이 비교는 과거 자료의 성능 저하를 확인하는 용도예요. 학습에 사용한 자료가 들어갈 수 있어 새 정확도 검증으로 세지 않아요."))
                content.addView(button("후보 목록") { showCandidates() })
            }
        }
    }

    private fun evaluate(candidate: PatternCandidate) {
        root("${candidate.profile.compatibility.recipe.label} · 새 자료 검증")
        val summary = text("새 촬영 자료와 이전 방식 비교 중…"); content.addView(summary)
        val revision = viewRevision
        worker.execute {
            val result = store.validate(candidate.profile.id)
            runOnUiThread {
                if (isFinishing || isDestroyed || revision != viewRevision) return@runOnUiThread
                result.onSuccess { report ->
                    fun number(value: Double?) = value?.let { String.format(Locale.KOREA, "%.2f", it) } ?: "미측정"
                    summary.text = if (report.countGatePassed) "폰의 횟수·경계 비교 기준 통과\n워치 실시간 동작과 적용 검증은 아직 남아 있어요."
                        else "검증 대기 · 기존 설정 유지"
                    content.addView(text("새 검증 자료 ${report.totalSets}세트 · 확인 ${report.totalReps}회 · 유효 ${report.distinctDays}일\n횟수 미확인 ${report.unknownReferenceSets}세트\n추정 가능 ${report.predictedSets}/${report.totalSets}세트\n정확히 일치 ${report.exactSets}/${report.totalSets} · ±1회 ${report.withinOneSets}/${report.totalSets}"))
                    content.addView(text("세트당 평균 횟수 오차\n이전 ${number(report.baselineRawMae)}회 · 피크 방식 ${number(report.paperRawMae)}회\n개인 패턴 ${number(report.rawMae)}회 · 실시간 후보 재생 ${number(report.causalRawMae)}회\n측정되지 않은 세트는 위의 추정 가능 비율과 함께 확인해 주세요."))
                    content.addView(text("실시간 후보 재생 일치 ${report.causalExactSets}/${report.totalSets}세트\n이 재생은 실제 워치의 처리 속도와 화면 반응을 검증한 결과가 아니에요."))
                    content.addView(text("영상 기준 평균 경계 오차\n시작 ${number(report.startBoundaryMaeMs)}ms · 전환 ${number(report.turnaroundMaeMs)}ms · 복귀 ${number(report.endBoundaryMaeMs)}ms"))
                    if (!report.countGatePassed) content.addView(text("남은 조건\n" + report.failures.map(::explainFailure).joinToString("\n")))
                    if (report.unscoredCaptures.isNotEmpty()) content.addView(text("미완료 촬영 ${report.unscoredCaptures.size}세트도 전체 세트 수에 포함했어요. 원본 수신과 영상의 실제 횟수를 확인해 주세요."))
                    content.addView(text("비교 결과는 저장됐어요. 운동 기록의 수행 횟수나 워치 설정을 자동으로 바꾸지 않아요."))
                }.onFailure { summary.text = "학습에 쓴 원본이나 확인한 구간이 바뀌었거나 자료를 읽지 못했어요. 원본과 확인 상태를 점검하고 새 후보를 만들어 주세요." }
                content.addView(button("후보 목록") { showCandidates() })
            }
        }
    }

    private fun explainFailure(reason: String): String = when (reason) {
        "insufficient_sets" -> "새 검증 자료 20세트 이상 필요"
        "insufficient_days" -> "새 검증 자료 3일 이상 필요"
        "insufficient_reps" -> "확인한 수행 횟수 150회 이상 필요"
        "training_overlap" -> "학습 자료가 검증에 포함돼 제외됨"
        "coverage_below_90_percent" -> "추정 가능한 세트 비율 90% 미만"
        "exact_below_90_percent" -> "정확히 일치하는 세트 비율 90% 미만"
        "within_one_below_95_percent" -> "±1회 이내 세트 비율 95% 미만"
        "mae_above_0_3" -> "평균 횟수 오차 0.3회 기준 미달"
        "no_strict_improvement" -> "이전 방식보다 개선됐다는 근거 부족"
        "condition_regression" -> "일부 기구·운동 조건에서 이전보다 오차 증가"
        "boundaries_not_validated" -> "시작·전환·복귀의 영상 기준 150ms 오차 검증 필요"
        "validation_inputs_unavailable" -> "원본이나 실제 횟수 확인이 끝나지 않은 촬영 존재"
        "reference_not_reviewed" -> "실제 수행 횟수를 확인하지 않은 촬영 존재"
        "feedback_not_measured" -> "워치 화면 프레임 제출 시각 기록 필요"
        "latency_not_validated" -> "반응 속도 p95 500ms 검증 필요"
        "insufficient_feedback_samples" -> "반응 속도를 비교할 유효 반복 부족"
        "premature_feedback" -> "영상의 복귀 시점보다 이른 출력 확인 필요"
        "baseline_not_recorded" -> "이전 워치 설정의 실제 횟수 기록 필요"
        "regression_dataset_missing" -> "과거 비교 자료를 먼저 고정해야 함"
        "regression_inputs_unavailable" -> "고정한 원본의 입력 품질 또는 이전 판정 확인 필요"
        "regression_count_error", "regression_condition_error" -> "과거 확인 자료에서 전체 또는 일부 운동의 횟수 오차 증가"
        "regression_boundary_error", "regression_boundaries_unavailable" -> "과거 확인 자료에서 반복 경계가 나빠지거나 산출되지 않음"
        else -> "검증 자료 조건 확인 필요"
    }

    private fun evaluateNative(candidate: PatternCandidate) {
        root("${candidate.profile.compatibility.recipe.label} · 워치 실측 검증")
        val summary = text("원본에 남은 워치의 실제 출력 확인 중…")
        content.addView(summary)
        val revision = viewRevision
        worker.execute {
            val result = store.validateNative(candidate.profile.id)
            runOnUiThread {
                if (isFinishing || isDestroyed || revision != viewRevision) return@runOnUiThread
                result.onSuccess { report ->
                    fun number(value: Double?) = value?.let { String.format(Locale.KOREA, "%.2f", it) } ?: "미측정"
                    summary.text = if (report.approved) "실측 기준 통과 · 적용 확인 대기" else "검증 대기 · 이전 설정 유지"
                    content.addView(text("${report.totalSets}세트 · 확인 ${report.totalReps}회 · 유효 ${report.validDays}일\n유효 출력 ${report.predictedSets}/${report.totalSets}세트\n정확 일치 ${report.exactSets}/${report.totalSets} · ±1회 ${report.withinOneSets}/${report.totalSets}"))
                    content.addView(text("평균 횟수 오차 ${number(report.mae)}회\n화면 프레임 제출 반응 p95 ${number(report.latencyP95Ms)}ms\n시작·전환·복귀 중 가장 큰 평균 오차 ${number(report.boundaryMaeMs)}ms"))
                    if (!report.approved) content.addView(text(report.failures.map(::explainFailure).joinToString("\n")))
                    val excluded = report.rows.count { it.exclusionReason != null }
                    if (excluded > 0) content.addView(text("사용할 수 없는 워치 기록 ${excluded}세트도 전체 분모에 포함했어요. 중단·다른 설정·에뮬레이션·시계 불일치 기록은 채택 증거로 쓰지 않아요."))
                    content.addView(text("이 검사는 폰에서 다시 돌린 결과와 별개예요. 검증 결과만 보관하며 현재 워치 설정을 바꾸지 않아요."))
                }.onFailure { summary.text = "원본·설정·영상의 확인 상태가 바뀌었거나 자료를 읽지 못했어요. 저장한 자료는 보관하며 설정을 적용하지 않았어요." }
                content.addView(button("후보 목록") { showCandidates() })
            }
        }
    }
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, profileReceiver, IntentFilter(PhoneWearListenerService.ACTION_PATTERN_PROFILE), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        bridge.retryPendingCommand()
        showCandidates()
    }
    override fun onStop() {
        if (receiverRegistered) { unregisterReceiver(profileReceiver); receiverRegistered = false }
        super.onStop()
    }
    override fun onDestroy() { bridge.close(); worker.shutdown(); super.onDestroy() }
}
