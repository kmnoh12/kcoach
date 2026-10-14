package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.phone.WorkoutWearingSelection
import dev.kyungmin.kcoach.core.ExerciseMotionCatalog
import dev.kyungmin.kcoach.core.StartWristContext

@Composable
fun WorkoutWearingStartSheet(previous: WorkoutWearingSelection?, cancel: () -> Unit,
    start: (WorkoutWearingSelection) -> Unit, startBasic: () -> Unit, exerciseIds: List<String> = emptyList()) {
    var side by rememberSaveable { mutableStateOf(previous?.side) }
    var placement by rememberSaveable { mutableStateOf(previous?.placement) }
    var deviceTop by rememberSaveable { mutableStateOf(previous?.deviceTop) }
    var variants by rememberSaveable { mutableStateOf(previous?.startVariants.orEmpty()) }
    val candidates = exerciseIds.map(ExerciseMotionCatalog::normalizeExerciseId).distinct()
        .filter { StartWristContext.expectedVariant(it) != null }
    val ready = side != null && placement != null
    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        Text("운동 시작", color = KColors.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        Text("이번 운동의 워치 착용 상태를 확인해 주세요. 같은 조건의 보정값이 있으면 사용해요.",
            color = KColors.Soft, fontSize = 15.sp)
        Spacer(Modifier.height(20.dp))
        Text("착용 손", color = KColors.Muted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("LEFT" to "왼손", "RIGHT" to "오른손").forEach { (value, label) ->
                OutlinedButton(onClick = { side = value }, modifier = Modifier.weight(1f).height(50.dp), shape = KShape.Control,
                    border = BorderStroke(1.dp, if (side == value) KColors.Accent else KColors.Hairline)) {
                    Text(label, color = if (side == value) KColors.Accent else KColors.White)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("워치 화면 방향", color = KColors.Muted, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("OUTSIDE" to "손등 쪽", "INSIDE" to "손바닥 쪽").forEach { (value, label) ->
                OutlinedButton(onClick = { placement = value }, modifier = Modifier.weight(1f).height(50.dp), shape = KShape.Control,
                    border = BorderStroke(1.dp, if (placement == value) KColors.Accent else KColors.Hairline)) {
                    Text(label, color = if (placement == value) KColors.Accent else KColors.White)
                }
            }
        }
        if (candidates.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("본체 위쪽이 향하는 곳", color = KColors.Muted, fontSize = 13.sp)
            Text("화면 회전과 본체 방향은 달라요. 모르면 미확인으로 둬도 돼요.", color = KColors.Soft, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ELBOW" to "팔꿈치 쪽", "HAND" to "손 쪽", "UNKNOWN" to "미확인").forEach { (value, label) ->
                    val selected = (deviceTop ?: "UNKNOWN") == value
                    OutlinedButton(onClick = { deviceTop = value.takeUnless { it == "UNKNOWN" } },
                        modifier = Modifier.weight(1f), shape = KShape.Control,
                        border = BorderStroke(1.dp, if (selected) KColors.Accent else KColors.Hairline)) {
                        Text(label, color = if (selected) KColors.Accent else KColors.White, fontSize = 13.sp)
                    }
                }
            }
            candidates.forEach { id ->
                Spacer(Modifier.height(16.dp))
                val title = when (id) {
                    "external_rotation" -> "케이블 익스터널 로테이션 시작"
                    "crossover_reverse_fly" -> "케이블 리버스 플라이 시작"
                    else -> "덤벨 사레레 시작"
                }
                val label = when (id) {
                    "external_rotation" -> "손바닥 위 · 전완 수평"
                    "crossover_reverse_fly" -> "손등 위 그립"
                    else -> "기본 팔 내림"
                }
                Text(title, color = KColors.Muted, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(StartWristContext.expectedVariant(id)!! to label, "UNKNOWN" to "다른 방식 · 미확인").forEach { (value, text) ->
                        val selected = (variants[id] ?: "UNKNOWN") == value
                        OutlinedButton(onClick = { variants = variants + (id to value) }, modifier = Modifier.weight(1f), shape = KShape.Control,
                            border = BorderStroke(1.dp, if (selected) KColors.Accent else KColors.Hairline)) {
                            Text(text, color = if (selected) KColors.Accent else KColors.White, fontSize = 12.sp)
                        }
                    }
                }
            }
            Text("선택한 조건을 저장해요. 센서의 시작 방향 확인은 별도로 검증해요.", color = KColors.Soft, fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { start(WorkoutWearingSelection(requireNotNull(side), requireNotNull(placement),
            deviceTop.takeIf { candidates.isNotEmpty() }, variants.filterKeys { it in candidates })) }, enabled = ready,
            modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("이 착용 상태로 시작", color = if (ready) KColors.Accent else KColors.Muted, fontSize = 17.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = cancel) { Text("취소", color = KColors.Muted) }
            TextButton(onClick = startBasic) { Text("보정 없이 시작", color = KColors.Soft) }
        }
    }
}
