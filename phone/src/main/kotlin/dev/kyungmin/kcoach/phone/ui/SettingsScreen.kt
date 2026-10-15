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
import dev.kyungmin.kcoach.phone.AiTaskPreferences
import dev.kyungmin.kcoach.phone.ChatGptConnectionUi
import dev.kyungmin.kcoach.phone.ChatGptModelSettingsUi

class SettingsActions(
    val back: () -> Unit,
    val account: () -> Unit,
    val refreshModels: () -> Unit,
    val chooseModel: (String) -> Unit,
    val chooseEffort: (String?) -> Unit,
    val profile: () -> Unit = {},
    val saveTasks: (AiTaskPreferences) -> Unit = {},
    val tempo: () -> Unit = {},
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
fun SettingsScreen(connection: ChatGptConnectionUi, modelUi: ChatGptModelSettingsUi, actions: SettingsActions, tasks: AiTaskPreferences = AiTaskPreferences()) {
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
            OutlinedButton(onClick = actions.profile, modifier = Modifier.fillMaxWidth().height(50.dp), shape = KShape.Control) {
                Text("내 운동 프로필 · 워치 착용", color = KColors.White)
            }
            Text("신체 정보와 평소 착용 상태를 폰에 저장해요. 로그인 없이 설정할 수 있어요.", color = KColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = actions.tempo, modifier = Modifier.fillMaxWidth().height(50.dp), shape = KShape.Control) {
                Text("운동별 템포 알림", color = KColors.White)
            }
            Spacer(Modifier.height(24.dp))
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
            Text("운동 상담은 메뉴에서, GPT 보정 도움은 내 측정 엔진에서 요청해요", color = KColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(20.dp))
            listOf(Triple("운동 상담", tasks.chatEnabled, { checked: Boolean -> tasks.copy(chatEnabled = checked) }),
                Triple("운동 리뷰", tasks.reviewEnabled, { checked: Boolean -> tasks.copy(reviewEnabled = checked) }),
                Triple("GPT 보정 도움", tasks.calibrationEnabled, { checked: Boolean -> tasks.copy(calibrationEnabled = checked) }),
                Triple("운동 종료 후 자동 리뷰", tasks.automaticReview, { checked: Boolean -> tasks.copy(automaticReview = checked) }),
                Triple("새 운동을 현재 프로필에 연결", tasks.linkNewWorkouts, { checked: Boolean -> tasks.copy(linkNewWorkouts = checked) }),
                Triple("검증 통과한 개인 엔진 자동 적용", tasks.autoApplyValidatedEngine, { checked: Boolean -> tasks.copy(autoApplyValidatedEngine = checked) })).forEach { (label, checked, update) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), color = KColors.Soft, fontSize = 14.sp)
                    androidx.compose.material3.Switch(checked, onCheckedChange = { actions.saveTasks(update(it)) }, enabled = connection.connected)
                }
            }
            Text("자동 리뷰는 연결한 기록에만 실행하며 선택 모델의 구독 한도를 사용해요. 새 운동 연결은 현재 프로필 소유로 저장돼요. 엔진은 독립·워치 실측 검증을 모두 통과한 뒤 다음 세트부터 요청하고 실제 사용 확인을 기다려요.", color = KColors.Muted, fontSize = 12.sp)
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
