package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.phone.WorkoutWearingSelection

@Composable
internal fun WorkoutPreparationPanel(wearing: WorkoutWearingSelection?,
    connection: String, wearingReadFailed: Boolean, openProfile: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("운동 준비", color = KColors.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text(connection, color = KColors.Soft, fontSize = 14.sp)
        val summary = when {
            wearingReadFailed -> "준비 설정을 불러오지 못했어요. 저장 상태를 확인해 주세요."
            wearing == null -> "착용 정보 미설정 · 개인 보정 없이 시작해요"
            else -> "${if (wearing.side == "LEFT") "왼손" else "오른손"} · ${if (wearing.placement == "OUTSIDE") "손등 쪽" else "손바닥 쪽"}"
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(summary, color = KColors.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = openProfile) { Text("프로필·착용 설정", color = KColors.Accent) }
        }
        Text("목록을 확인하고 아래 시작 버튼을 눌러 주세요.", color = KColors.Muted, fontSize = 12.sp)
    }
}
