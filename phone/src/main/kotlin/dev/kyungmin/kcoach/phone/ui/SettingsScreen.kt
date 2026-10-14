package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.phone.ChatGptConnectionUi
import dev.kyungmin.kcoach.phone.ChatGptModelSettingsUi

class SettingsActions(
    val back: () -> Unit,
    val account: () -> Unit,
    val refreshModels: () -> Unit,
    val chooseModel: (String) -> Unit,
    val chooseEffort: (String?) -> Unit,
)

fun reasoningLabel(effort: String): String = when (effort) {
    "none" -> "없음"
    "minimal" -> "최소"
    "low" -> "낮음"
    "medium" -> "보통"
    "high" -> "높음"
    "xhigh" -> "매우 높음"
    "max" -> "최대"
    "ultra" -> "울트라"
    else -> effort
}

@Composable
fun SettingsScreen(connection: ChatGptConnectionUi, modelUi: ChatGptModelSettingsUi, actions: SettingsActions) {
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val model = modelUi.selectedModel
    val canChoose = connection.connected && connection.planAllowed && !connection.busy && modelUi.loaded && !modelUi.loading
    val defaultLabel = model?.defaultReasoning?.let { "모델 기본값 · ${reasoningLabel(it)}" } ?: "모델 기본값"
    val effortLabel = modelUi.selectedEffort?.let { effort ->
        if (model?.reasoningOptions?.any { it.effort == effort } == true) reasoningLabel(effort) else "추론 강도 다시 선택"
    } ?: defaultLabel
    MaterialTheme(colorScheme = darkColorScheme(primary = KColors.Accent, onPrimary = KColors.OnAccent,
        background = KColors.Bg, surface = KColors.Surface, onSurface = KColors.White, outline = KColors.Hairline)) {
        Column(Modifier.fillMaxSize().background(KColors.Bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions.back) { KIcon(KIcon.Back, KColors.White) }
                Text("설정", color = KColors.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(28.dp))
            Text("ChatGPT 계정", color = KColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Text(if (connection.connected) connection.accountLabel else "연결된 계정이 없어요", color = KColors.White, fontSize = 18.sp)
            if (connection.connected) {
                Spacer(Modifier.height(6.dp))
                Text(if (connection.planAllowed) "구독 한도 사용 허용" else "로그인만 연결 · 한도 사용 동의 필요", color = KColors.Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = actions.account, modifier = Modifier.fillMaxWidth().height(50.dp), shape = KShape.Control) {
                Text(if (connection.connected) "ChatGPT 계정 관리" else "ChatGPT로 계속하기", color = KColors.White)
            }
            Spacer(Modifier.height(32.dp))
            Text("AI 작업", color = KColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Text("작업 모델", color = KColors.Soft, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { picker = "model" }, enabled = canChoose && modelUi.models.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = KShape.Control) {
                Text(model?.displayName ?: if (modelUi.selectedSlug.isNotBlank()) "모델 다시 선택" else "모델 선택", fontSize = 16.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text("추론 강도", color = KColors.Soft, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { picker = "effort" }, enabled = canChoose && model != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = KShape.Control) { Text(effortLabel, fontSize = 16.sp) }
            if (model != null && !model.reasoningMetadataAvailable) {
                Spacer(Modifier.height(8.dp))
                Text("사용 가능한 추론 강도를 확인하지 못했어요. 모델 기본값으로 사용할 수 있어요.", color = KColors.Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            if (modelUi.message.isNotBlank()) Text(modelUi.message, color = KColors.Muted, fontSize = 13.sp)
            else if (!connection.planAllowed) Text("계정을 연결하고 구독 한도 사용을 허용하면 지원 모델을 불러와요", color = KColors.Muted, fontSize = 13.sp)
            TextButton(onClick = actions.refreshModels, enabled = connection.connected && connection.planAllowed && !connection.busy) {
                Text(if (modelUi.loading) "모델 목록 불러오는 중…" else "모델 목록 새로고침")
            }
            Spacer(Modifier.height(18.dp))
            Text("모델과 추론 강도는 계정별로 저장돼요. 설정 변경만으로 한도가 소모되지는 않아요.", color = KColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text("GPT 운동 분석은 아직 준비 중이에요", color = KColors.Muted, fontSize = 13.sp)
        }
        if (picker == "model" && canChoose) {
            AlertDialog(onDismissRequest = { picker = null }, title = { Text("작업 모델 선택") }, text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(modelUi.models, key = { it.slug }) { item ->
                        TextButton(onClick = { actions.chooseModel(item.slug); picker = null }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text(item.displayName, color = if (item.slug == modelUi.selectedSlug) KColors.Accent else KColors.White, fontSize = 16.sp)
                                if (item.displayName != item.slug) Text(item.slug, color = KColors.Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { picker = null }) { Text("닫기") } })
        }
        if (picker == "effort" && canChoose && model != null) {
            AlertDialog(onDismissRequest = { picker = null }, title = { Text("추론 강도 선택") }, text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    item {
                        TextButton(onClick = { actions.chooseEffort(null); picker = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(defaultLabel, color = if (modelUi.selectedEffort == null) KColors.Accent else KColors.White)
                        }
                    }
                    items(model.reasoningOptions, key = { it.effort }) { option ->
                        TextButton(onClick = { actions.chooseEffort(option.effort); picker = null }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text("${reasoningLabel(option.effort)} · ${option.effort}", color = if (option.effort == modelUi.selectedEffort) KColors.Accent else KColors.White)
                                if (option.description.isNotBlank()) Text(option.description, color = KColors.Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { picker = null }) { Text("닫기") } })
        }
    }
}
