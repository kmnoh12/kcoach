package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Number whose changed characters roll vertically (like a flip counter). */
@Composable
fun FlipText(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Bold,
) {
    val style = TextStyle(fontSize = size, color = color, fontWeight = weight)
    Row(modifier) {
        // Key each slot from the right so units stay put while tens appear/disappear.
        text.forEachIndexed { index, char ->
            val slot = text.length - index
            androidx.compose.runtime.key(slot) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        (slideInVertically(Motion.digit()) { -it } + fadeIn(Motion.digit())) togetherWith
                            (slideOutVertically(Motion.digit()) { it } + fadeOut(Motion.digit()))
                    },
                    label = "flip",
                ) { c -> Text(c.toString(), style = style) }
            }
        }
    }
}

/** Completion box: border/fill fade and a check that draws itself in. */
@Composable
fun CheckCell(checked: Boolean, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(260, easing = Motion.UiEasing), label = "check")
    val border by animateColorAsState(if (checked) KColors.Accent else Color.Transparent, Motion.ui(), label = "checkBorder")
    Box(
        modifier
            .clip(KShape.Control)
            .background(KColors.SurfaceRaised)
            .border(1.5.dp, border, KShape.Control),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            if (progress <= 0f) return@Canvas
            val p1 = Offset(size.width * 0.12f, size.height * 0.55f)
            val p2 = Offset(size.width * 0.42f, size.height * 0.82f)
            val p3 = Offset(size.width * 0.9f, size.height * 0.2f)
            val first = 0.4f
            val path = Path().apply {
                moveTo(p1.x, p1.y)
                val a = (progress / first).coerceAtMost(1f)
                lineTo(p1.x + (p2.x - p1.x) * a, p1.y + (p2.y - p1.y) * a)
                if (progress > first) {
                    val b = ((progress - first) / (1f - first)).coerceIn(0f, 1f)
                    lineTo(p2.x + (p3.x - p2.x) * b, p2.y + (p3.y - p2.y) * b)
                }
            }
            drawPath(path, KColors.Accent, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** Exercise position dots; the current one stretches into a pill. */
@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 16.dp else 5.dp, tween(Motion.PAGE_MS, easing = Motion.UiEasing), label = "dot")
            val color by animateColorAsState(if (i == current) KColors.Accent else KColors.Faint, Motion.ui(), label = "dotColor")
            Box(Modifier.width(width).height(5.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

/** Rest ring: gradient arc that shrinks counter-clockwise, with a fill-in intro. */
@Composable
fun RestRing(fraction: Float, diameter: Dp, stroke: Dp, modifier: Modifier = Modifier) {
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(Motion.RING_INTRO_MS, easing = Motion.PanelEasing)) }
    Canvas(modifier.size(diameter)) {
        val strokePx = stroke.toPx()
        val inset = strokePx / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - strokePx, size.height - strokePx)
        drawArc(
            color = KColors.SurfaceRaised,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(strokePx),
        )
        val sweep = 360f * fraction.coerceIn(0f, 1f) * intro.value
        rotate(-90f) {
            drawArc(
                brush = SolidColor(KColors.Accent),
                startAngle = 0f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(strokePx, cap = StrokeCap.Round),
            )
        }
    }
}

/** Today's volume against the plan: rolling number plus a thin progress track. */
@Composable
fun VolumeBar(done: Int, target: Int, unit: String, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(
        if (target > 0) (done.toFloat() / target).coerceIn(0f, 1f) else 0f,
        Motion.panel(),
        label = "volume",
    )
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("볼륨 ", color = KColors.Muted, fontSize = 13.sp)
            FlipText("%,d".format(done), 15.sp, if (done > 0) KColors.White else KColors.Muted)
            Text(" / %,d%s".format(target, unit), color = KColors.Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(KColors.Hairline)) {
            Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(KColors.Accent))
        }
    }
}
