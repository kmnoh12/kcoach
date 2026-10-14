package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ManualCountSheet(title: String, targets: List<Int>, setNumbers: List<Int>, onSubmit: (List<Int>) -> Unit) {
    require(targets.isNotEmpty() && targets.size == setNumbers.size)
    var values by rememberSaveable(targets, setNumbers) { mutableStateOf(List(targets.size) { "" }) }
    val counts = values.map { it.toIntOrNull()?.takeIf { count -> count in 0..9_999 } }
    Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text(title, color = KColors.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text("실제로 한 횟수를 입력해 주세요. 0회도 기록할 수 있어요.", color = KColors.Muted,
            fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            targets.forEachIndexed { index, target ->
                Text("${setNumbers[index]}세트 · 목표 ${target}회", color = KColors.Soft, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                BasicTextField(value = values[index], onValueChange = { value ->
                    if (value.length <= 4 && value.all { it in '0'..'9' }) values = values.toMutableList().apply { set(index, value) }
                }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = TextStyle(color = KColors.White, fontSize = 24.sp), cursorBrush = SolidColor(KColors.Accent),
                    modifier = Modifier.testTag("actual-reps-$index").fillMaxWidth().background(KColors.SurfaceRaised, KShape.Control)
                        .padding(14.dp), decorationBox = { inner ->
                        Box { if (values[index].isEmpty()) Text("실제 횟수", color = KColors.Muted, fontSize = 24.sp); inner() }
                    })
            }
        }
        TextButton(onClick = { onSubmit(counts.map { requireNotNull(it) }) }, enabled = counts.all { it != null },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("입력한 횟수로 완료", color = if (counts.all { it != null }) KColors.Accent else KColors.Muted)
        }
        TextButton(onClick = { onSubmit(targets) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (targets.size == 1) "목표 ${targets.single()}회대로 기록" else "모두 목표 횟수대로 기록", color = KColors.Soft)
        }
    }
}
