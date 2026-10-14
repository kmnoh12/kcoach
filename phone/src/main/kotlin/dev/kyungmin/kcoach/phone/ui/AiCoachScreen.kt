package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.phone.*
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class AiCoachActions(val back: () -> Unit, val settings: () -> Unit,
    val saveProfile: (AiCoachProfile) -> Unit, val linkRecords: (Set<String>) -> Unit,
    val send: (String) -> Unit, val cancel: () -> Unit)

@Composable
internal fun AiCoachScreen(ui: AiCoachUi, connection: ChatGptConnectionUi, models: ChatGptModelSettingsUi,
    actions: AiCoachActions, startProfile: Boolean = false) {
    var editing by rememberSaveable(connection.activeProfileId) { mutableStateOf(startProfile) }
    var pickingRecords by rememberSaveable(connection.activeProfileId) { mutableStateOf(false) }
    var question by rememberSaveable(connection.activeProfileId) { mutableStateOf("") }
    var attempt by rememberSaveable(connection.activeProfileId) { mutableStateOf("") }
    var attemptTurnCount by rememberSaveable(connection.activeProfileId) { mutableIntStateOf(-1) }
    var evidence by remember { mutableStateOf<AiCoachTurn?>(null) }
    val ready = connection.connected && ui.accountId == connection.activeProfileId && ui.loaded
    val canSend = ready && ui.profile != null && connection.planAllowed && models.selectionValid && !connection.busy && !ui.busy
    val list = rememberLazyListState()
    val draftProfile = remember(ui.accountId, ui.profile) { ui.profile ?: AiCoachProfile() }
    LaunchedEffect(ui.turns.size, ui.busy, ui.pendingQuestion, ready) {
        if (!ready) return@LaunchedEffect
        if (ui.busy && ui.pendingQuestion.isNotBlank() && question.trim() == ui.pendingQuestion) {
            attempt = question; attemptTurnCount = ui.turns.size; question = ""
        } else if (!ui.busy && attempt.isNotBlank()) {
            val savedAnswer = ui.turns.size > attemptTurnCount && ui.turns.lastOrNull()?.question == attempt.trim()
            if (!savedAnswer && question.isBlank()) question = attempt
            attempt = ""
        }
        if (ui.turns.isNotEmpty()) {
            list.animateScrollToItem(ui.turns.lastIndex)
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = KColors.Accent, onPrimary = KColors.OnAccent,
        background = KColors.Bg, surface = KColors.Surface, onSurface = KColors.White, outline = KColors.Hairline)) {
        if (editing && ready) {
            BackHandler { editing = false }
            ProfileEditor(draftProfile, onDismiss = { editing = false }) {
                actions.saveProfile(it); editing = false
            }
            return@MaterialTheme
        }
        Column(Modifier.fillMaxSize().background(KColors.Bg).safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions.back) { KIcon(KIcon.Back, KColors.White) }
                Text("운동 상담", color = KColors.White, fontSize = 25.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.settings) { Text("설정") }
            }
            Text(models.selectedModel?.displayName ?: "설정에서 모델을 선택해 주세요", color = KColors.Muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { editing = true }, enabled = ready && !ui.busy) {
                    Text(if (ui.profile == null) "프로필 입력" else "내 운동 프로필")
                }
                TextButton(onClick = { pickingRecords = true }, enabled = ready && ui.profile != null && !ui.busy) {
                    Text("운동 기록 연결")
                }
            }
            if (!connection.connected) Text("설정에서 ChatGPT 계정을 연결해 주세요", color = KColors.Muted, fontSize = 14.sp)
            else if (!connection.planAllowed) Text("상담을 요청하려면 설정에서 구독 한도 사용을 허용해 주세요", color = KColors.Muted, fontSize = 14.sp)
            else if (ready && ui.profile == null) Text("먼저 프로필을 저장해 주세요. 모르는 항목은 비워둘 수 있어요", color = KColors.Muted, fontSize = 14.sp)
            if (ready && ui.profile != null) Text("연결한 최근 기록 ${ui.records.count { it.linked }}회 · 입력 프로필과 확인된 기록으로 답해요",
                color = KColors.Muted, fontSize = 12.sp)
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = KColors.Hairline)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (ready) items(ui.turns) { turn ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(turn.question, color = KColors.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Text(turn.answer, color = KColors.Soft, fontSize = 16.sp, lineHeight = 24.sp)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${dateLabel(turn.createdAtMs)} · ${turn.actualModel}", color = KColors.Muted,
                                fontSize = 11.sp, modifier = Modifier.weight(1f))
                            if (turn.citations.isNotEmpty()) TextButton(onClick = { evidence = turn }) { Text("근거 ${turn.citations.size}개") }
                        }
                        HorizontalDivider(color = KColors.Hairline)
                    }
                }
                if (ready && ui.turns.isEmpty()) item {
                    Text("최근 운동에서 무엇을 바꾸면 좋을지, 기록이 어떻게 달라졌는지 물어봐 주세요.",
                        color = KColors.Muted, fontSize = 16.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 20.dp))
                }
                if (ready && ui.busy && ui.pendingQuestion.isNotBlank()) item {
                    Text(ui.pendingQuestion, color = KColors.White, fontSize = 16.sp)
                }
            }
            if (ready && ui.message.isNotBlank()) Text(ui.message, color = KColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            if (ready && ui.busy && ui.pendingQuestion.isNotBlank()) TextButton(onClick = actions.cancel) { Text("상담 요청 취소") }
            OutlinedTextField(value = question, onValueChange = { if (it.length <= 4_000) question = it },
                label = { Text("운동 질문") }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4,
                enabled = !ui.busy)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("전송하면 프로필·연결한 기록·최근 대화를 ChatGPT로 보내요", color = KColors.Muted,
                    fontSize = 11.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
                TextButton(onClick = { actions.send(question) }, enabled = canSend && question.isNotBlank()) { Text("전송") }
            }
        }
        if (pickingRecords && ready) RecordDialog(ui.records, onDismiss = { pickingRecords = false }) {
            actions.linkRecords(it); pickingRecords = false
        }
        evidence?.let { turn -> AlertDialog(onDismissRequest = { evidence = null }, title = { Text("답변에 사용한 근거") },
            text = { Text(evidenceText(turn), modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { evidence = null }) { Text("닫기") } }) }
    }
}

@Composable
private fun ProfileEditor(profile: AiCoachProfile, onDismiss: () -> Unit, save: (AiCoachProfile) -> Unit) {
    var height by rememberSaveable { mutableStateOf(profile.heightCm?.toString().orEmpty()) }
    var weight by rememberSaveable { mutableStateOf(profile.weightKg?.toString().orEmpty()) }
    var measured by rememberSaveable { mutableStateOf(profile.weightMeasuredOn.ifBlank { LocalDate.now().toString() }) }
    var fat by rememberSaveable { mutableStateOf(profile.bodyFatPercent?.toString().orEmpty()) }
    var goal by rememberSaveable { mutableStateOf(profile.goal) }
    var experience by rememberSaveable { mutableStateOf(profile.experience) }
    var constraints by rememberSaveable { mutableStateOf(profile.constraints) }
    var equipment by rememberSaveable { mutableStateOf(profile.equipment) }
    var error by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(KColors.Bg).safeDrawingPadding().imePadding().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { KIcon(KIcon.Back, KColors.White) }
            Text("내 운동 프로필", color = KColors.White, fontSize = 25.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("모든 항목은 선택이에요. 입력하지 않은 정보는 추정해 채우지 않아요.", color = KColors.Muted, fontSize = 13.sp)
            ProfileField("키 (cm)", height, true) { height = it }
            ProfileField("체중 (kg)", weight, true) { weight = it }
            ProfileField("체중 측정일 (YYYY-MM-DD)", measured) { measured = it }
            ProfileField("체지방률 (%)", fat, true) { fat = it }
            ProfileField("목표", goal) { goal = it }
            ProfileField("운동 경력", experience) { experience = it }
            ProfileField("제약·주의할 점", constraints) { constraints = it }
            ProfileField("사용 기구·증량 단위", equipment) { equipment = it }
            if (error.isNotBlank()) Text(error, color = KColors.Red, fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("취소") }
            TextButton(onClick = {
                try {
                    fun number(value: String) = value.trim().takeIf { it.isNotEmpty() }?.toDouble()
                    val draft = profile.copy(heightCm = number(height), weightKg = number(weight),
                        weightMeasuredOn = if (weight.isBlank()) "" else measured.trim(), bodyFatPercent = number(fat),
                        goal = goal.trim(), experience = experience.trim(), constraints = constraints.trim(), equipment = equipment.trim())
                    save(draft)
                } catch (_: Exception) { error = "키·체중·체지방률의 숫자와 체중 측정일을 확인해 주세요" }
            }) { Text("저장") }
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String, decimal: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { if (it.length <= 2_000) change(it) }, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth(), singleLine = decimal, maxLines = if (decimal) 1 else 4)
}

@Composable
private fun RecordDialog(records: List<AiCoachRecord>, onDismiss: () -> Unit, link: (Set<String>) -> Unit) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("내 프로필에 운동 기록 연결") }, text = {
        Column {
            Text("이 프로필의 기록만 골라 주세요. 다른 프로필에 연결된 기록은 표시하지 않아요.", fontSize = 13.sp)
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(records, key = { it.sessionId }) { record ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = record.linked || record.sessionId in selected, enabled = !record.linked,
                            onCheckedChange = { chosen -> selected = if (chosen) selected + record.sessionId else selected - record.sessionId })
                        Column(Modifier.weight(1f)) {
                            Text(record.title)
                            Text("${dateLabel(record.startedAtMs)}${if (record.linked) " · 연결됨" else ""}", fontSize = 12.sp, color = KColors.Muted)
                        }
                    }
                }
                if (records.isEmpty()) item { Text("연결할 완료 기록이 없어요", modifier = Modifier.padding(vertical = 20.dp)) }
            }
        }
    }, confirmButton = { TextButton(onClick = { link(selected) }, enabled = selected.isNotEmpty()) { Text("선택한 기록 연결") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
}

private fun dateLabel(timestamp: Long) = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MM.dd HH:mm"))

private fun evidenceText(turn: AiCoachTurn): String = runCatching {
    val context = JSONObject(turn.contextJson)
    val records = context.getJSONArray("records")
    buildString {
        append("답변 당시의 기록이에요. 이후 수정된 기록은 다음 질문에서 다시 읽어요.\n\n")
        if (turn.citations.any { it.startsWith("profile:") }) {
            val profile = context.getJSONObject("profile")
            append("입력 프로필\n")
            for ((field, label) in listOf("height_cm" to "키(cm)", "weight_kg" to "체중(kg)", "weight_measured_on" to "체중 측정일",
                "body_fat_percent" to "체지방률(%)", "goal" to "목표", "experience" to "경력", "constraints" to "제약", "equipment" to "기구·증량")) {
                val value = profile.opt(field)?.takeIf { it != JSONObject.NULL && it.toString().isNotBlank() }
                if (value != null) append("$label: $value\n")
            }
            append('\n')
        }
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            if (record.getString("evidence_id") !in turn.citations) continue
            append("${dateLabel(record.getLong("started_at_ms"))} · ${record.getString("title")}\n")
            val sets = record.getJSONArray("completed_sets")
            for (j in 0 until sets.length()) {
                val set = sets.getJSONObject(j)
                val kg = if (set.isNull("weight_kg")) "중량 미입력" else "${set.getDouble("weight_kg")}kg"
                val reps = if (set.isNull("performed_reps")) "횟수 미확인" else "${set.getInt("performed_reps")}회"
                append("${set.getString("exercise_name")} ${set.getInt("set_index") + 1}세트 · $kg × $reps\n")
            }
            append('\n')
        }
    }
}.getOrDefault("답변 당시의 근거를 열지 못했어요")
