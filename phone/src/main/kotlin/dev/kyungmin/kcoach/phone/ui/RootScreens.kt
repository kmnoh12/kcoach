package dev.kyungmin.kcoach.phone.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kyungmin.kcoach.phone.WorkoutRoutine
import kotlinx.coroutines.delay

/** Tab order matches MainActivity.RootTab. */
private val NavItems = listOf("⌂" to "홈", "▣" to "기록", "▥" to "통계", "◉" to "보정", "☰" to "메뉴")

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
            .background(Color(0xFF0E1010))
            .border(1.dp, Color(0xFF232626))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        NavItems.forEachIndexed { index, (icon, title) ->
            val tint by animateColorAsState(if (index == selected) KColors.Amber else KColors.Muted, Motion.ui(), label = "navTint")
            Column(
                Modifier
                    .weight(1f)
                    .height(66.dp)
                    .pressable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(icon, color = tint, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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

class RunningUi(val routineName: String, val paused: Boolean, val elapsedSeconds: () -> Long)

@Composable
fun HomeScreen(routines: List<WorkoutRoutine>, running: RunningUi?, actions: HomeActions) {
    Column(Modifier.fillMaxSize().background(KColors.Bg)) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("홈", color = KColors.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("☷   ◉   ●", color = KColors.Muted, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            Text("내 루틴", color = KColors.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("최근  ▾", color = KColors.Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
                Text("요약  ▾", color = KColors.Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("☷", color = KColors.Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(14.dp))
                Text("▦", color = KColors.Muted, fontSize = 17.sp)
            }
            Spacer(Modifier.height(10.dp))
            routines.forEach { routine ->
                RoutineCard(routine, onClick = { actions.openRoutine(routine) }, onMenu = { actions.routineMenu(routine) })
                Spacer(Modifier.height(10.dp))
            }
        }
        if (running == null) {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp)) {
                ActionButton("⚡  자유운동", KColors.AmberDark, KColors.White, Modifier.weight(1f), actions.freeWorkout)
                Spacer(Modifier.width(12.dp))
                ActionButton("＋  추가", Color.White, KColors.AmberDark, Modifier.weight(1f), actions.add)
            }
        } else {
            RunningBar(running, actions)
        }
    }
}

@Composable
private fun RoutineCard(routine: WorkoutRoutine, onClick: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(KColors.Surface)
            .pressable(onClick)
            .padding(start = 16.dp, top = 12.dp, end = 6.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚡", color = KColors.Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
        Column(Modifier.weight(1f)) {
            Text(routine.name, color = KColors.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(routine.muscles, color = KColors.Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text("${routine.lastPerformed} · ${routine.exercises.size}종목", color = KColors.Muted, fontSize = 12.sp)
        }
        Box(Modifier.size(48.dp).clickable(onClick = onMenu), contentAlignment = Alignment.Center) {
            Text("•••", color = KColors.Muted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ActionButton(text: String, background: Color, content: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = content, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp)
            .background(Color(0xFF0E1010))
            .border(1.dp, KColors.Amber)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).pressable(actions.openRunning)) {
            Text(running.routineName, color = KColors.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("%02d:%02d".format(elapsed / 60, elapsed % 60), color = KColors.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.size(70.dp).pressable(actions.togglePause), contentAlignment = Alignment.Center) {
            Text(if (running.paused) "▶" else "Ⅱ", color = KColors.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.size(70.dp).pressable(actions.stop), contentAlignment = Alignment.Center) {
            Text("■", color = KColors.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
}
