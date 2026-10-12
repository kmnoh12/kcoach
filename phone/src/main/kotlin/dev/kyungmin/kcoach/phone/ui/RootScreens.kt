package dev.kyungmin.kcoach.phone.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.phone.WorkoutRoutine
import kotlinx.coroutines.delay

/** Tab order matches MainActivity.RootTab. */
private val NavItems = listOf(KIcon.Home to "홈", KIcon.Records to "기록", KIcon.Stats to "통계", KIcon.Calibrate to "보정", KIcon.Menu to "메뉴")

/** False on the first frame, true right after: lets values animate in when a screen opens. */
@Composable
fun rememberEntered(): Boolean {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    return entered
}

/** Press feedback shared by every tappable surface: a small shrink on the UI curve. */
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.ui(), label = "press")
    return this.scale(scale).clickable(interactionSource = source, indication = null, onClick = onClick)
}

@Composable
fun RootBottomNav(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(KColors.Bg)
            .border(0.5.dp, KColors.Hairline)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        NavItems.forEachIndexed { index, (icon, title) ->
            val tint by animateColorAsState(if (index == selected) KColors.Accent else KColors.Muted, Motion.ui(), label = "navTint")
            Column(
                Modifier
                    .weight(1f)
                    .height(66.dp)
                    .pressable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                KIcon(icon, tint, size = 24.dp)
                Spacer(Modifier.height(2.dp))
                Text(title, color = tint, fontSize = 12.sp, fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

class HomeActions(
    val openRoutine: (WorkoutRoutine) -> Unit,
    val routineMenu: (WorkoutRoutine) -> Unit,
    val freeWorkout: () -> Unit,
    val add: () -> Unit,
    val openRunning: () -> Unit,
    val togglePause: () -> Unit,
    val stop: () -> Unit,
)

class RunningUi(val routineName: String, val detail: String, val paused: Boolean, val elapsedSeconds: () -> Long)

/** One weekday on the strip. [load] is null for a rest day, otherwise 0..TrainingLoad.MAX_SCORE. */
class WeekDayUi(val label: String, val load: Double?, val isToday: Boolean, val isFuture: Boolean)

class RoutineRowUi2(val routine: WorkoutRoutine, val daysAgo: Int?)

class HomeUi(
    val week: List<WeekDayUi>,
    val weekCount: Int,
    val rows: List<RoutineRowUi2>,
    val longestRest: RoutineRowUi2?,
)

@Composable
fun HomeScreen(ui: HomeUi, running: RunningUi?, actions: HomeActions) {
    Column(Modifier.fillMaxSize().background(KColors.Bg)) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 18.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                Text("홈", color = KColors.White, fontSize = 32.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("이번 주 ", color = KColors.Muted, fontSize = 13.sp)
                Text("${ui.weekCount}회", color = KColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            WeekStrip(ui.week)
            ui.longestRest?.let {
                Spacer(Modifier.height(14.dp))
                RestHero(it) { actions.openRoutine(it.routine) }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("내 루틴", color = KColors.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("최근", color = KColors.Muted, fontSize = 13.sp)
                KIcon(KIcon.Down, KColors.Muted, size = 14.dp)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(KColors.Hairline))
            ui.rows.forEach { row ->
                RoutineRow(row, onClick = { actions.openRoutine(row.routine) }, onMenu = { actions.routineMenu(row.routine) })
            }
            Spacer(Modifier.height(12.dp))
        }
        // The bar for a running workout fades in over the idle buttons (and back).
        AnimatedContent(
            targetState = running,
            transitionSpec = { fadeIn(Motion.ui()) togetherWith fadeOut(Motion.ui()) },
            label = "homeBottom",
        ) { current ->
            if (current == null) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp)) {
                    ActionButton("자유운동", KColors.Accent, KColors.OnAccent, outlined = false, modifier = Modifier.weight(1f), onClick = actions.freeWorkout)
                    Spacer(Modifier.width(8.dp))
                    ActionButton("추가", Color.Transparent, KColors.White, outlined = true, modifier = Modifier.weight(1f), onClick = actions.add)
                }
            } else {
                RunningBar(current, actions)
            }
        }
    }
}

@Composable
private fun WeekStrip(week: List<WeekDayUi>) {
    val maxBar = 28.dp
    val minBar = 4.dp
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(46.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        week.forEach { day ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                val entered = rememberEntered()
                val target = when {
                    day.load != null -> minBar + (maxBar - minBar) * (day.load / dev.kyungmin.kcoach.core.TrainingLoad.MAX_SCORE).toFloat()
                    else -> minBar
                }
                val barHeight by animateDpAsState(if (entered) target else minBar, Motion.panel(), label = "bar")
                val color = when {
                    day.load != null -> KColors.Accent
                    day.isToday -> KColors.White
                    day.isFuture -> Color(0xFF161A19)
                    else -> Color(0xFF1F2322)
                }
                Box(Modifier.fillMaxWidth().height(barHeight).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(color))
                Spacer(Modifier.height(3.dp))
                Text(
                    day.label,
                    color = if (day.isToday) KColors.White else KColors.Faint,
                    fontSize = 10.sp,
                    fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun RestHero(row: RoutineRowUi2, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(KShape.Control)
            .border(0.5.dp, Color(0xFF333A38), KShape.Control)
            .pressable(onClick)
            .padding(start = 12.dp, top = 12.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        val entered = rememberEntered()
        val shownDays by animateIntAsState(if (entered) row.daysAgo ?: 0 else 0, tween(700, easing = LinearEasing), label = "heroDays")
        Text(
            "$shownDays", color = KColors.Accent, fontSize = 64.sp, fontWeight = FontWeight.Medium,
            letterSpacing = (-3).sp, lineHeight = 58.sp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(bottom = 2.dp)) {
            Text("일째 쉬는 루틴", color = KColors.Muted, fontSize = 11.sp)
            Text(row.routine.name, color = KColors.White, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            Text("${row.routine.muscles} · ${row.routine.exercises.size}종목", color = KColors.Soft, fontSize = 12.sp)
        }
        KIcon(KIcon.Forward, KColors.Muted, Modifier.padding(end = 6.dp, bottom = 4.dp), 20.dp)
    }
}

@Composable
private fun RoutineRow(row: RoutineRowUi2, onClick: () -> Unit, onMenu: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().pressable(onClick).padding(start = 16.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.routine.name, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text("${row.routine.muscles} · ${row.routine.exercises.size}종목", color = KColors.Soft, fontSize = 12.sp)
            }
            if (row.daysAgo != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("${row.daysAgo}", color = KColors.White, fontSize = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp)
                    Text("일 전", color = KColors.Muted, fontSize = 10.sp)
                }
            } else {
                Text(row.routine.lastPerformed, color = KColors.Muted, fontSize = 12.sp)
            }
            Box(Modifier.size(48.dp).clickable(onClick = onMenu), contentAlignment = Alignment.Center) {
                KIcon(KIcon.DotsVertical, KColors.Faint, size = 20.dp)
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(KColors.Hairline))
    }
}

@Composable
private fun ActionButton(text: String, background: Color, content: Color, outlined: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(52.dp)
            .clip(KShape.Control)
            .background(background)
            .then(if (outlined) Modifier.border(0.5.dp, Color(0xFF3A4240), KShape.Control) else Modifier)
            .pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RunningBar(running: RunningUi, actions: HomeActions) {
    val elapsed by produceState(running.elapsedSeconds(), running) {
        while (true) {
            value = running.elapsedSeconds()
            delay(1_000)
        }
    }
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(KColors.Accent))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 78.dp)
                .background(KColors.Bg)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).pressable(actions.openRunning)) {
                Text(running.routineName, color = KColors.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(running.detail, color = KColors.Soft, fontSize = 12.sp, maxLines = 1)
                Text("%02d:%02d".format(elapsed / 60, elapsed % 60), color = KColors.Accent, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            }
            Box(Modifier.size(64.dp).pressable(actions.togglePause), contentAlignment = Alignment.Center) {
                KIcon(if (running.paused) KIcon.Play else KIcon.Pause, KColors.White, size = 26.dp)
            }
            Box(Modifier.size(64.dp).pressable(actions.stop), contentAlignment = Alignment.Center) {
                KIcon(KIcon.Stop, KColors.White, size = 24.dp)
            }
        }
    }
}
