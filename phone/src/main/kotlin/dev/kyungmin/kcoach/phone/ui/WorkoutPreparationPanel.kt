package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.phone.WorkoutPreparation
import dev.kyungmin.kcoach.phone.WorkoutWearingSelection

@Composable
internal fun WorkoutPreparationPanel(exerciseIds: List<String>, wearing: WorkoutWearingSelection?,
    connection: String, wearingReadFailed: Boolean, answers: Map<String, String>,
    onAnswer: (String, String) -> Unit, openProfile: () -> Unit) {
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
        val questions = WorkoutPreparation.questions(exerciseIds)
        questions.forEach { question ->
            Text(question.title, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(question.description, color = KColors.Soft, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(question.expectedVariant to "이 방식", "OTHER" to "다른 방식", "UNKNOWN" to "모름").forEach { (value, label) ->
                    val chosen = answers[question.exerciseId] == value
                    OutlinedButton(onClick = { onAnswer(question.exerciseId, value) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .testTag("preparation:${question.exerciseId}:$value").semantics { selected = chosen },
                        shape = KShape.Control,
                        border = BorderStroke(1.dp, if (chosen) KColors.Accent else KColors.Hairline)) {
                        Text(label, color = if (chosen) KColors.Accent else KColors.White, fontSize = 13.sp)
                    }
                }
            }
        }
        Text(if (questions.isEmpty()) "목록을 확인하고 아래 시작 버튼을 눌러 주세요." else
            "모르면 선택하지 않아도 시작할 수 있어요. 답변은 수행 방식이며 센서의 자세 확인과는 달라요.",
            color = KColors.Muted, fontSize = 12.sp)
    }
}
