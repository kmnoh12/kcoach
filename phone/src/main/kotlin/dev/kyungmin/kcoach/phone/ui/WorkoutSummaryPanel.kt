package dev.kyungmin.kcoach.phone.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.kyungmin.kcoach.core.WorkoutComparison
import dev.kyungmin.kcoach.phone.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class WorkoutSummaryRead(val record: FinishedWorkoutStore.Evidence, val previous: FinishedWorkoutStore.Evidence?,
    val heart: WorkoutHeartSummary, val times: JSONObject?, val energy: JSONObject, val captures: JSONObject)

@Composable
internal fun WorkoutSummaryPanel(sessionId: String) {
    val app = LocalContext.current.applicationContext
    val loaded by produceState<WorkoutSummaryRead?>(null, sessionId) {
        value = withContext(Dispatchers.IO) { runCatching {
            val records = FinishedWorkoutStore(app).allEvidence()
            val record = FinishedWorkoutStore(app).evidenceForSessions(setOf(sessionId)).single()
            val legacyProfile = UserProfileStore(app).profileForSession(sessionId)
            val profile = WorkoutWearingStore(app).frozenBodyProfile(sessionId, legacyProfile).getOrThrow()
            val previousId = WorkoutComparison.previous(record.snapshot, records.map { it.snapshot })?.sessionId
            WorkoutSummaryRead(record, records.firstOrNull { it.snapshot.sessionId == previousId },
                WorkoutVitalsStore(app).summary(sessionId, record.snapshot.startedAtMs, record.finishedAtMs), WorkoutTimeStore(app).read(sessionId),
                WorkoutEnergyStore(app).estimate(record, profile), WorkoutCaptureStatus(app).read(record.snapshot))
        }.getOrNull() }
    }
    loaded?.let { WorkoutSummaryContent(it) }
}

@Composable
internal fun WorkoutSummaryContent(read: WorkoutSummaryRead) {
    val done = read.record.snapshot.plannedSets.filter { it.completed }
    val totals = WorkoutComparison.totals(done)
    var expanded by remember { mutableStateOf<String?>(null) }
    fun n(value: Double) = String.format(java.util.Locale.KOREA, "%.1f", value)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${read.record.minutes}분 · ${done.size}세트", color = KColors.White, fontSize = 30.sp)
        Text("일시정지 제외 · 일반 ${done.count { it.setType == "NORMAL" }} / 웜업 ${done.count { it.setType == "WARMUP" }}세트", color = KColors.Muted, fontSize = 12.sp)
        read.times?.let { time -> Text("측정 구간 · 운동 ${time.optLong("working_ms") / 60_000}분 / 휴식 ${time.optLong("resting_ms") / 60_000}분 / 정지 ${time.optLong("paused_ms") / 60_000}분${if (time.getBoolean("partial")) " · 일부 구간" else ""}", color = KColors.Muted, fontSize = 12.sp) }
        Text("총 볼륨 · ${totals.volumeKgReps?.let { n(it) + " kg" } ?: "중량·반복 미입력 포함"}", color = KColors.White, fontSize = 22.sp)
        Text("입력 중량 × 수행 반복 합계 · 자중·보조 중량 등 기록의 입력 기준", color = KColors.Muted, fontSize = 12.sp)
        val kcal = read.energy.opt("active_kcal") as? Number
        Text("추정 활동 칼로리 · ${kcal?.let { "약 ${it.toDouble().toInt()} kcal" } ?: read.energy.getString("reason")}", color = KColors.Soft, fontSize = 16.sp)
        if (kcal != null) Text(read.energy.getString("method"), color = KColors.Muted, fontSize = 12.sp)
        Text("평균 심박 ${read.heart.average?.let { n(it) } ?: "미측정"} · 최고 ${read.heart.maximum?.toString() ?: "미측정"} bpm", color = KColors.Soft, fontSize = 15.sp)
        if (read.heart.points.isNotEmpty()) HeartRateGraph(read.heart.points, read.record.snapshot.startedAtMs, read.record.finishedAtMs)
        Text(if (read.heart.hasGaps) "심박 일부 미측정 · 공백은 연결하지 않아요" else "운동 중 심박 · 폰 수신 시각 기준", color = KColors.Muted, fontSize = 12.sp)
        Text("운동별 기록", color = KColors.White, fontSize = 20.sp)
        done.groupBy { it.exerciseId }.forEach { (id, sets) ->
            Row(Modifier.fillMaxWidth()) {
                ResultExerciseAnimation(id)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    TextButton(onClick = { expanded = if (expanded == id) null else id }) { Text(sets.first().exerciseName, color = KColors.White, fontSize = 18.sp) }
                    val volume = WorkoutComparison.totals(sets).volumeKgReps
                    Text("${sets.size}세트 · ${volume?.let { n(it) + " kg" } ?: "볼륨 미확인"}", color = KColors.Muted, fontSize = 13.sp)
                    val changes = WorkoutComparison.compare(read.record.snapshot, read.previous?.snapshot).filter { it.condition.exerciseId == id }
                    if (changes.any { it.condition.missingFields.isNotEmpty() }) Text("기구·수행 조건 일부 미입력 · 입력이 같은 범위의 비교예요", color = KColors.Muted, fontSize = 12.sp)
                    changes.forEach { change ->
                        Text(change.volumeChange?.let { "같은 수행 조건의 지난 기록 대비 ${if (it >= 0) "+" else ""}${n(it)} kg · 일반 세트" }
                            ?: "같은 수행 조건의 이전 기록이 없어요", color = KColors.Soft, fontSize = 13.sp)
                        if (change.setCountChanged) Text("완료한 본세트 수가 달라요 · 볼륨 증가만으로 수행 향상을 판단하지 않아요", color = KColors.Muted, fontSize = 12.sp)
                        change.sameWeight.filter { it.comparableSetCount }.forEach { same ->
                            val delta = same.currentReps - same.previousReps
                            Text("${n(same.weightKg)} kg · 같은 세트 수에서 ${same.previousReps} → ${same.currentReps}회 (${if (delta >= 0) "+" else ""}$delta)", color = KColors.Soft, fontSize = 13.sp)
                        }
                        change.sameReps.forEach { same -> Text("${same.reps}회 · 중량 ${same.previousWeightsKg.joinToString { n(it) }} → ${same.currentWeightsKg.joinToString { n(it) }} kg", color = KColors.Soft, fontSize = 13.sp) }
                    }
                }
            }
            if (expanded == id) sets.forEach { set -> Text("${set.setIndex + 1}세트 · ${if (set.setType == "WARMUP") "웜업 · " else ""}${set.weightKg?.let(::n) ?: "중량 미입력"} kg × ${set.performedReps?.toString() ?: "횟수 미입력"}회", color = KColors.Soft, fontSize = 14.sp) }
        }
        val removed = read.previous?.snapshot?.plannedSets?.filter { it.completed }?.map { it.exerciseId }?.toSet().orEmpty() - done.map { it.exerciseId }.toSet()
        if (removed.isNotEmpty()) Text("지난 기록에서 빠진 운동 · " + read.previous!!.snapshot.plannedSets.filter { it.exerciseId in removed }.distinctBy { it.exerciseId }.joinToString { it.exerciseName }, color = KColors.Muted, fontSize = 12.sp)
        Text("세부 분석", color = KColors.White, fontSize = 20.sp)
        Text(read.captures.getString("message"), color = KColors.Muted, fontSize = 14.sp)
        Text("운동 기록은 저장됐어요. 이 화면을 나가도 원본 수신과 분석은 계속돼요.", color = KColors.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun HeartRateGraph(points: List<WorkoutHeartPoint>, start: Long, end: Long) {
    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
        val span = (end - start).coerceAtLeast(1)
        fun at(p: WorkoutHeartPoint) = Offset(((p.receivedAtMs - start).toDouble() / span * size.width).toFloat(), size.height * (1 - (p.bpm - 20) / 220f))
        points.zipWithNext().forEach { (a, b) -> if (b.receivedAtMs - a.receivedAtMs <= 15_000) drawLine(Color(0xFFE26C72), at(a), at(b), strokeWidth = 3.dp.toPx()) }
        points.forEach { drawCircle(Color(0xFFE26C72), 2.dp.toPx(), at(it)) }
    }
}

@Composable
internal fun ResultExerciseAnimation(exerciseId: String) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    var image by remember(exerciseId) { mutableStateOf<ImageView?>(null) }
    var visible by remember { mutableStateOf(false) }
    fun playback() { (image?.drawable as? AnimatedImageDrawable)?.let { if (visible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) it.start() else it.stop() } }
    DisposableEffect(lifecycle, exerciseId) {
        val observer = LifecycleEventObserver { _, _ -> playback() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); (image?.drawable as? AnimatedImageDrawable)?.stop() }
    }
    AndroidView(modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black).onGloballyPositioned {
        val bounds = it.boundsInWindow(); visible = bounds.bottom > 0 && bounds.top < view.height; playback()
    }, factory = {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (exerciseId.matches(Regex("[a-z0-9_]+"))) {
                val id = resources.getIdentifier("exercise_$exerciseId", "drawable", context.packageName)
                if (id != 0) runCatching { setImageDrawable(ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources, id))) }
            }
            image = this
        }
    }, update = { image = it; playback() })
}
