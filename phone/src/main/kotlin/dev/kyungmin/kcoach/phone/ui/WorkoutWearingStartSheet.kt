package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.BorderStroke
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

@Composable
fun WorkoutWearingStartSheet(previous: WorkoutWearingSelection?, cancel: () -> Unit,
    start: (WorkoutWearingSelection) -> Unit, startBasic: () -> Unit) {
    var side by rememberSaveable { mutableStateOf(previous?.side) }
    var placement by rememberSaveable { mutableStateOf(previous?.placement) }
    val ready = side != null && placement != null
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
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
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { start(WorkoutWearingSelection(requireNotNull(side), requireNotNull(placement))) }, enabled = ready,
            modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("이 착용 상태로 시작", color = if (ready) KColors.Accent else KColors.Muted, fontSize = 17.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = cancel) { Text("취소", color = KColors.Muted) }
            TextButton(onClick = startBasic) { Text("보정 없이 시작", color = KColors.Soft) }
        }
    }
}
