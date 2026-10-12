package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Rest state derived from the model at a given clock time. */
data class RestClock(val resting: Boolean, val pausedRest: Boolean, val leftMs: Long, val totalMs: Long) {
    val leftSeconds: Int get() = ((leftMs + 999L) / 1000L).toInt()
    val fraction: Float get() = if (totalMs <= 0L) 0f else (leftMs.toFloat() / totalMs).coerceIn(0f, 1f)

    companion object {
        fun of(model: WorkoutUiModel, now: Long): RestClock = when {
            model.restEndAtMs > 0L -> {
                val total = (model.restEndAtMs - model.restStartAtMs).coerceAtLeast(1L)
                RestClock(true, false, (model.restEndAtMs - now).coerceAtLeast(0L), total)
            }
            model.pausedRestRemainingMs > 0L -> {
                val total = (model.exercises.getOrNull(model.exerciseIndex)?.restSeconds ?: 0) * 1000L
                RestClock(false, true, model.pausedRestRemainingMs, total.coerceAtLeast(model.pausedRestRemainingMs))
            }
            else -> RestClock(false, false, 0L, 0L)
        }
    }
}

/** Collapsed rest bar pinned above the keypad / bottom edge. Drag the handle up to expand. */
@Composable
fun RestBar(
    rest: RestClock,
    idleSeconds: Int,
    paused: Boolean,
    onExpand: () -> Unit,
    onAdjust: (Int) -> Unit,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
) {
    val active = rest.resting || rest.pausedRest
    Column(
        Modifier.fillMaxWidth().background(KColors.Surface)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -12f) onExpand() }
            },
    ) {
        // Thin progress line + grab handle.
        Box(Modifier.fillMaxWidth().height(3.dp).background(KColors.SurfaceRaised)) {
            if (active) Box(Modifier.fillMaxWidth(rest.fraction).fillMaxHeight().background(KColors.Accent))
        }
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(34.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(KColors.Red).clickable { onExpand() })
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).clickable { onExpand() }) {
                Text("휴식 시간", color = KColors.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.Bottom) {
                    FlipText((if (active) rest.leftSeconds else idleSeconds).toString(), 26.sp, KColors.White)
                    Text(" 초", color = KColors.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            RoundButton(KIcon.Minus, KColors.Red) { onAdjust(-10) }
            Spacer(Modifier.width(10.dp))
            RoundButton(KIcon.Plus, KColors.Red) { onAdjust(10) }
            Spacer(Modifier.width(10.dp))
            RoundButton(if (paused) KIcon.Play else KIcon.Pause, Color.Transparent) { onTogglePause() }
            Spacer(Modifier.width(6.dp))
            RoundButton(KIcon.Stop, Color.Transparent) { onStop() }
        }
    }
}

/** Expanded rest panel: big gradient ring, controls and presets. */
@Composable
fun ExpandedRestPanel(
    rest: RestClock,
    idleSeconds: Int,
    paused: Boolean,
    onClose: () -> Unit,
    onAdjust: (Int) -> Unit,
    onPreset: (Int) -> Unit,
    onTogglePause: () -> Unit,
    onSkip: () -> Unit,
) {
    val active = rest.resting || rest.pausedRest
    Column(
        Modifier.fillMaxSize().background(KColors.Bg)
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> if (dragAmount > 18f) onClose() }
            }
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = 10.dp).width(34.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(KColors.Red))
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("휴식", color = KColors.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("✕", color = KColors.Muted, fontSize = 22.sp, modifier = Modifier.clickable { onClose() }.padding(8.dp))
        }
        Spacer(Modifier.height(28.dp))
        Box(contentAlignment = Alignment.Center) {
            RestRing(if (active) rest.fraction else 1f, 260.dp, 16.dp)
            Row(verticalAlignment = Alignment.Bottom) {
                FlipText((if (active) rest.leftSeconds else idleSeconds).toString(), 72.sp, KColors.White)
            }
        }
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            RoundButton(KIcon.Minus, KColors.Red, 52) { onAdjust(-10) }
            RoundButton(KIcon.Plus, KColors.Red, 52) { onAdjust(10) }
            RoundButton(if (paused) KIcon.Play else KIcon.Pause, KColors.SurfaceRaised, 52) { onTogglePause() }
            if (rest.resting) RoundButton(KIcon.Skip, KColors.SurfaceRaised, 52) { onSkip() }
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 60, 90, 120, 150, 180).forEach { seconds ->
                Text(
                    String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60),
                    color = if (seconds == idleSeconds) KColors.Accent else KColors.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(KShape.Control)
                        .border(1.dp, if (seconds == idleSeconds) KColors.Accent else KColors.Faint, KShape.Control)
                        .clickable { onPreset(seconds) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** Banner that drops in during the last seconds of rest: what's next, a 0.1s countdown and skip. */
@Composable
fun NextSetBanner(text: String, leftMs: Long, onSkip: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp)
            .clip(KShape.Control)
            .background(KColors.White)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, color = KColors.Bg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(
                String.format(Locale.US, "%.1f", leftMs / 1000.0),
                color = KColors.Bg,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text("건너뛰기", color = KColors.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onSkip() }.padding(8.dp))
    }
}

@Composable
fun RoundButton(icon: KIcon, fill: Color, sizeDp: Int = 38, onClick: () -> Unit) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(fill).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        KIcon(icon, KColors.White, size = (sizeDp * 0.52).dp)
    }
}
