package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.core.*
import dev.kyungmin.kcoach.phone.WorkoutData

@Composable
fun WorkoutTempoScreen(id: String?, previous: WorkoutTempoSettings?, select: (String) -> Unit, back: () -> Unit,
    save: (WorkoutTempoSettings) -> Boolean) {
    val colors = darkColorScheme(primary = KColors.Accent, background = KColors.Bg, surface = KColors.Surface,
        onSurface = KColors.White, onBackground = KColors.White, onPrimary = KColors.OnAccent)
    MaterialTheme(colorScheme = colors) {
        Column(Modifier.fillMaxSize().background(KColors.Bg).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (id == null) "운동별 템포 알림" else "${WorkoutData.catalog.find { it.id == id }?.name.orEmpty()} · 템포",
                color = KColors.White, fontSize = 26.sp)
            if (id == null || previous == null) {
                Text("기본은 꺼져 있어요. 정한 시간 범위에서 연속으로 벗어나면 워치가 알려줘요. 검증된 개인 반복 측정을 사용하는 새 세트부터 반영해요.", color = KColors.Soft)
                WorkoutData.catalog.distinctBy { it.id }.filter {
                    ExerciseTrackingProfiles.resolve(it.id).mode != TrackingMode.MANUAL_REQUIRED
                }.forEach { exercise ->
                    Text(exercise.name, color = KColors.White, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .pressable { select(exercise.id) }.padding(vertical = 12.dp))
                    HorizontalDivider(color = KColors.Hairline, thickness = .5.dp)
                }
            } else key(id) {
                var enabled by rememberSaveable { mutableStateOf(previous.enabled) }
                var consecutive by rememberSaveable { mutableStateOf(previous.consecutiveRepetitions.toString()) }
                var interval by rememberSaveable { mutableStateOf((previous.minimumAlertIntervalMs / 1000.0).toString()) }
                var error by remember { mutableStateOf(false) }
                val ranges = linkedMapOf<WorkoutTempoMetric, Pair<String, String>>()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("템포 알림", color = KColors.White, modifier = Modifier.weight(1f))
                    Checkbox(enabled, { enabled = it })
                }
                Text("원하는 시간 범위를 초로 입력해 주세요. 비워 둔 항목은 알림을 꺼요. 목표 시간은 운동 처방이나 자세 점수가 아니에요.", color = KColors.Soft)
                WorkoutTempoMetric.entries.forEach { metric -> key(metric) {
                    var low by rememberSaveable { mutableStateOf(previous.targets[metric]?.let { (it.minimumMs/1000.0).toString() }.orEmpty()) }
                    var high by rememberSaveable { mutableStateOf(previous.targets[metric]?.let { (it.maximumMs/1000.0).toString() }.orEmpty()) }
                    ranges[metric] = low to high
                    Text(when (metric) { WorkoutTempoMetric.REPETITION -> "1회 전체 시간"; WorkoutTempoMetric.CONCENTRIC -> "힘쓰는 구간 시간"; WorkoutTempoMetric.ECCENTRIC -> "복귀 구간 시간" }, color = KColors.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondsField(low, { low = it }, "최소 초", Modifier.weight(1f))
                        SecondsField(high, { high = it }, "최대 초", Modifier.weight(1f))
                    }
                } }
                SecondsField(consecutive, { consecutive = it }, "연속 횟수 (1~100회)")
                SecondsField(interval, { interval = it }, "알림 사이 최소 간격 (초)")
                Text("확인되지 않은 힘쓰는·복귀 시간은 알림에 쓰지 않아요.", color = KColors.Muted, fontSize = 13.sp)
                if (error) Text("시간 범위·연속 횟수·알림 간격을 확인해 주세요. 알림을 켜려면 시간 범위가 필요해요.", color = KColors.Red)
                Box(Modifier.fillMaxWidth().height(52.dp).clip(KShape.Control).background(KColors.Accent).pressable {
                    error = runCatching {
                        val targets = ranges.mapNotNull { (metric, pair) ->
                            val from = pair.first.trim(); val until = pair.second.trim()
                            if (from.isEmpty() && until.isEmpty()) null else {
                                fun ms(value: String): Long {
                                    val seconds = value.toDouble(); require(seconds.isFinite() && seconds > 0 && seconds <= 120)
                                    return Math.round(seconds * 1000).also { require(it > 0) }
                                }
                                metric to WorkoutTempoRange(ms(from), ms(until))
                            }
                        }.toMap()
                        val seconds = interval.toDouble(); require(seconds.isFinite() && seconds in 1.0..120.0)
                        check(save(WorkoutTempoSettings(id, enabled, targets, consecutive.toInt(), Math.round(seconds*1000))))
                    }.isFailure
                }, contentAlignment = Alignment.Center) { Text("다음 새 세트부터 저장", color = KColors.OnAccent) }
            }
            Text(if (id == null) "설정으로 돌아가기" else "운동 목록", color = KColors.Soft,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).pressable(back).padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun SecondsField(value: String, change: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, modifier = modifier,
        shape = KShape.Control, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}
