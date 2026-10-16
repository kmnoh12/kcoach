package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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

/** Per-exercise start posture. Unset means "모름": the app does not assume a posture. */
@Composable
internal fun WorkoutPostureScreen(answers: Map<String, String>, back: () -> Unit, onAnswer: (String, String) -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = KColors.Accent, onPrimary = KColors.OnAccent,
        background = KColors.Bg, surface = KColors.Surface, onSurface = KColors.White, outline = KColors.Hairline)) {
        Column(Modifier.fillMaxSize().background(KColors.Bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back) { KIcon(KIcon.Back, KColors.White) }
                Text("운동별 자세", color = KColors.White, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            }
            Text("평소 하는 그립이나 자세를 알려주면 횟수 인식이 더 정확해져요. 정하지 않은 운동은 '모름'으로 시작해요.",
                color = KColors.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            WorkoutPreparation.allQuestions().forEach { question ->
                Text(question.title, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(question.description, color = KColors.Soft, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(question.expectedVariant to "이 방식", "OTHER" to "다른 방식", "UNKNOWN" to "모름").forEach { (value, label) ->
                        val chosen = (answers[question.exerciseId] ?: "UNKNOWN") == value
                        OutlinedButton(onClick = { onAnswer(question.exerciseId, value) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .testTag("posture:${question.exerciseId}:$value").semantics { selected = chosen },
                            shape = KShape.Control,
                            border = BorderStroke(1.dp, if (chosen) KColors.Accent else KColors.Hairline)) {
                            Text(label, color = if (chosen) KColors.Accent else KColors.White, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
