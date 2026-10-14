package dev.kyungmin.kcoach.phone.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.phone.*
import org.json.JSONObject

@Composable
internal fun AiCalibrationPanel(candidateId: String, back: () -> Unit) {
    val context = LocalContext.current
    val auth = remember { ChatGptAuth.get(context) }
    val controller = remember { AiCalibrationController.get(context) }
    val connection = auth.state.value; val models = auth.modelSettings.value
    val account = connection.activeProfileId.takeIf { connection.connected }
    LaunchedEffect(account, connection.connectionRevision, candidateId) {
        controller.bind(account, candidateId)
        if (account != null && !models.loaded) auth.loadModels()
    }
    val saved = controller.state.value
    val ui = saved.takeIf { it.accountId == account && it.candidateId == candidateId } ?: AiCalibrationUi()
    AiCalibrationContent(ui, models.selectedModel?.displayName.orEmpty(),
        ready = ui.loaded && ui.input != null && connection.connected && connection.planAllowed && models.selectionValid && !connection.busy && !ui.busy,
        request = controller::start, cancel = controller::cancel,
        settings = { context.startActivity(Intent(context, SettingsActivity::class.java)) }, back = back)
}

@Composable
internal fun AiCalibrationContent(ui: AiCalibrationUi, model: String, ready: Boolean, request: () -> Unit,
    cancel: () -> Unit, settings: () -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GPT 보정 도움", color = KColors.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        if (model.isNotBlank()) Text("선택 모델 · $model", color = KColors.Muted, fontSize = 12.sp)
        Text(ui.message.ifBlank { "확인한 원본을 준비하는 중…" }, color = KColors.Soft, fontSize = 14.sp)
        if (ui.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = KColors.Accent, trackColor = KColors.Hairline)
            TextButton(onClick = cancel) { Text("보정 도움 요청 취소", color = KColors.Accent) }
        } else {
            TextButton(onClick = request, enabled = ready) { Text("확인한 원본으로 보정 도움 요청", color = if (ready) KColors.Accent else KColors.Muted) }
            TextButton(onClick = settings) { Text("계정·모델 설정", color = KColors.Muted) }
        }
        ui.input?.let { input ->
            Text("기존 개인 후보 · 버전 ${input.candidate.profile.version}", color = KColors.White, fontSize = 15.sp)
            Text("학습 원본 ${input.sets.size}세트에서 비교해요. 새 독립 검증 자료는 GPT 입력에 넣지 않아요.", color = KColors.Muted, fontSize = 13.sp)
            input.localReplay.countMae?.let { Text("학습 원본 재생 · 기존 후보 평균 횟수 오차 %.2f회".format(java.util.Locale.US, it), color = KColors.Muted, fontSize = 13.sp) }
        }
        ui.resultJson?.let { encoded ->
            val result = JSONObject(encoded); val answer = result.getJSONObject("answer")
            if (result.optString("actual_model").isNotBlank()) Text("보정 도움에 사용한 모델 · ${result.getString("actual_model")}", color = KColors.Muted, fontSize = 12.sp)
            Text(answer.getString("summary"), color = KColors.White, fontSize = 18.sp)
            for (key in listOf("findings", "review_suggestions")) {
                val rows = answer.getJSONArray(key)
                for (i in 0 until rows.length()) Text(rows.getJSONObject(i).getString("text"), color = KColors.Soft, fontSize = 14.sp)
            }
            val variants = result.getJSONArray("candidates")
            for (i in 0 until variants.length()) {
                val row = variants.getJSONObject(i)
                HorizontalDivider(color = KColors.Hairline)
                Text("새 후보 · 버전 ${row.getLong("version")} · 독립 검증 대기", color = KColors.Accent, fontSize = 16.sp)
                Text(answer.getJSONArray("candidate_proposals").getJSONObject(i).getString("reason"), color = KColors.Soft, fontSize = 14.sp)
                val replay = row.getJSONObject("training_replay")
                if (!replay.isNull("count_mae")) Text("같은 학습 원본 재생 · 평균 횟수 오차 %.2f회".format(java.util.Locale.US, replay.getDouble("count_mae")), color = KColors.Muted, fontSize = 13.sp)
            }
            val limitations = answer.getJSONArray("limitations")
            for (i in 0 until limitations.length()) Text(limitations.getString(i), color = KColors.Muted, fontSize = 13.sp)
            val timing = result.optJSONObject("timings_ms")
            timing?.let { Text("도움 요청 처리 · %.1f초".format(java.util.Locale.US, it.optDouble("total") / 1000), color = KColors.Muted, fontSize = 12.sp) }
        }
        Text("학습 원본 비교는 새 정확도 검증이 아니에요. 후보 목록에서 새 자료·실제 워치·고정 자료 검증을 진행한 뒤 적용할 수 있어요.", color = KColors.Muted, fontSize = 13.sp)
        TextButton(onClick = back) { Text("후보·검증·적용 상태로 돌아가기", color = KColors.Accent) }
    }
}
