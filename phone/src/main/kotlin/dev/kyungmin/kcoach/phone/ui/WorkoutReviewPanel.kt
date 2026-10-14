package dev.kyungmin.kcoach.phone.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.kyungmin.kcoach.phone.*
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun WorkoutReviewPanel(sessionId: String) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val auth = remember { ChatGptAuth.get(context) }
    val controller = remember { WorkoutReviewController.get(context) }
    val connection = auth.state.value
    val models = auth.modelSettings.value
    val accountId = connection.activeProfileId.takeIf { connection.connected }
    LaunchedEffect(accountId, connection.connectionRevision, sessionId) {
        controller.bind(accountId, sessionId)
        if (accountId != null && !models.loaded) auth.loadModels()
    }
    DisposableEffect(lifecycle, accountId, sessionId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.bind(accountId, sessionId)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val saved = controller.state.value
    val ui = saved.takeIf { it.accountId == accountId && it.sessionId == sessionId } ?: WorkoutReviewUi()
    WorkoutReviewContent(ui, canStart = ui.loaded && ui.context != null && connection.connected && connection.planAllowed &&
        models.selectionValid && !connection.busy && !ui.busy,
        modelLabel = models.selectedModel?.displayName.orEmpty(), request = controller::start, cancel = controller::cancel,
        settings = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
        profile = { context.startActivity(Intent(context, AiCoachActivity::class.java).putExtra(AiCoachActivity.EXTRA_PROFILE, true)) })
}

@Composable
internal fun WorkoutReviewContent(ui: WorkoutReviewUi, canStart: Boolean, modelLabel: String,
    request: () -> Unit, cancel: () -> Unit, settings: () -> Unit, profile: () -> Unit) {
    var expanded by remember(ui.sessionId, ui.answerJson) { mutableStateOf<String?>(null) }
    var evidence by remember(ui.answerJson) { mutableStateOf(false) }
    val result = ui.answerJson?.let { JSONObject(it) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AI 운동 리뷰", color = KColors.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        if (modelLabel.isNotBlank()) Text("선택 모델 · $modelLabel", color = KColors.Muted, fontSize = 12.sp)
        Text(ui.message.ifBlank { "운동 기록은 저장됐어요" }, color = KColors.Muted, fontSize = 13.sp)
        if (ui.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = KColors.Accent)
            TextButton(onClick = cancel) { Text("리뷰 요청 취소", color = KColors.Accent) }
        } else {
            TextButton(onClick = request, enabled = canStart) { Text(if (ui.stale) "변경된 기록으로 리뷰 요청" else "운동 리뷰 요청", color = if (canStart) KColors.Accent else KColors.Muted) }
            if (ui.context == null) TextButton(onClick = profile) { Text("운동 프로필·기록 연결", color = KColors.Accent) }
            TextButton(onClick = settings) { Text("계정·모델 설정", color = KColors.Muted) }
        }
        if (result != null) {
            Text(result.getString("summary"), color = KColors.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(result.getString("overview"), color = KColors.Soft, fontSize = 14.sp)
            if (ui.model.isNotBlank()) Text("리뷰에 사용한 모델 · ${ui.model}", color = KColors.Muted, fontSize = 12.sp)
            result.getJSONArray("findings").objects().forEach { Text(it.getString("text"), color = KColors.Soft, fontSize = 14.sp) }
            result.getJSONArray("exercise_analyses").objects().forEach { row ->
                val id = row.getString("exercise_id")
                HorizontalDivider(color = KColors.Hairline)
                TextButton(onClick = { expanded = if (expanded == id) null else id }, contentPadding = PaddingValues(0.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(ui.context?.exerciseNames?.get(id).orEmpty(), color = KColors.White, fontSize = 16.sp)
                        Text(row.getString("headline"), color = KColors.Soft, fontSize = 14.sp)
                    }
                }
                if (expanded == id) {
                    if (row.getString("status") != "analyzed") Text(
                        if (row.getString("status") == "partial") "확인된 자료 일부를 바탕으로 정리했어요" else "분석에 필요한 자료가 부족해요",
                        color = KColors.Muted, fontSize = 13.sp)
                    row.getJSONArray("observations").objects().forEach { observation ->
                        Text((if (observation.getString("kind") == "observed") "관측 · " else "해석 · ") + observation.getString("text"), color = KColors.Soft, fontSize = 14.sp)
                    }
                    (row.getJSONArray("metric_refs").strings() + row.getJSONArray("observations").objects()
                        .flatMap { it.getJSONArray("metric_refs").strings() }).distinct().forEach { ref ->
                        ui.context?.metrics?.get(ref)?.let { MetricEvidence(it) }
                    }
                    row.getJSONArray("highlighted_set_ids").strings().forEach { setId ->
                        ui.context?.metrics?.values?.singleOrNull { it.setId == setId }?.let { metric ->
                            Text("확인할 구간 · 세트 ${metric.values.getInt("set_index") + 1}", color = KColors.Accent, fontSize = 13.sp)
                            MetricEvidence(metric)
                        }
                    }
                    row.getJSONArray("limitations").strings().forEach { Text(it, color = KColors.Muted, fontSize = 13.sp) }
                }
            }
            val targets = result.getJSONArray("next_targets")
            if (targets.length() > 0) Text("다음 운동 제안", color = KColors.White, fontSize = 16.sp)
            targets.objects().forEach { row ->
                Text(ui.context?.exerciseNames?.get(row.getString("exercise_id")).orEmpty(), color = KColors.White, fontSize = 14.sp)
                Text(row.getString("target"), color = KColors.Accent, fontSize = 15.sp)
                Text(row.getString("reason"), color = KColors.Soft, fontSize = 13.sp)
            }
            (result.getJSONArray("limitations").strings() + result.getJSONObject("coverage").getJSONArray("limitations").strings())
                .distinct().forEach { Text(it, color = KColors.Muted, fontSize = 13.sp) }
            TextButton(onClick = { evidence = !evidence }) { Text(if (evidence) "분석 근거 접기" else "분석 근거 보기", color = KColors.Accent) }
            if (evidence) {
                result.getJSONObject("coverage").getJSONArray("evidence_ids").strings().forEach { Text(it, color = KColors.Muted, fontSize = 11.sp) }
                ui.context?.metrics?.values?.forEach { MetricEvidence(it) }
            }
            Text("다음 목표는 제안이에요. 운동 기록이나 보정 설정을 자동으로 바꾸지 않아요.", color = KColors.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MetricEvidence(metric: ReviewMetric) {
    val labels = mapOf("performed_reps" to "기록한 횟수", "weight_kg" to "중량 kg", "normal_sets" to "일반 세트",
        "normal_reps" to "일반 세트 반복 합계", "normal_volume_kg_reps" to "일반 세트 볼륨 kg·회",
        "previous_reps" to "지난 반복 합계", "current_reps" to "이번 반복 합계", "volume_change_kg_reps" to "볼륨 변화 kg·회",
        "average_cycle_seconds" to "평균 왕복 시간 초", "average_departure_seconds" to "시작→전환 시간 초",
        "average_return_seconds" to "전환→복귀 시간 초", "average_pause_seconds" to "정지 시간 초",
        "average_concentric_seconds" to "확인한 올림 시간 초", "average_eccentric_seconds" to "확인한 내림 시간 초",
        "late_cycle_change_percent" to "후반 반복 시간 변화 %", "watch_estimate" to "워치 추정 횟수", "phone_estimate" to "폰 추정 횟수")
    labels.filterKeys { metric.values.has(it) }.forEach { (key, label) ->
        val value = metric.values.opt(key)
        val text = when (value) {
            null, JSONObject.NULL -> "미확인"
            is Double -> "%.2f".format(java.util.Locale.US, value)
            else -> value.toString()
        }
        Text("$label · $text", color = KColors.Muted, fontSize = 12.sp)
    }
    metric.values.optJSONArray("rep_samples")?.objects()?.forEach { rep ->
        Text("반복 ${rep.getInt("rep_index") + 1} · 왕복 %.2f초 · 시작→전환 %.2f초 · 전환→복귀 %.2f초".format(java.util.Locale.US,
            rep.getDouble("cycle_seconds"), rep.getDouble("departure_seconds"), rep.getDouble("return_seconds")), color = KColors.Muted, fontSize = 12.sp)
    }
    if (metric.values.optInt("rep_samples_omitted") > 0)
        Text("초반·후반 반복을 표시했어요. 전체 분석은 저장돼 있어요.", color = KColors.Muted, fontSize = 12.sp)
}
private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
private fun JSONArray.strings() = (0 until length()).map(::getString)
