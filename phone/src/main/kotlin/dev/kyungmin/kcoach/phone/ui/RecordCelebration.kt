package dev.kyungmin.kcoach.phone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How loud a record is: a heavier top weight is the big moment, a rep or an estimate is a quiet note. */
enum class RecordTier { MAX_WEIGHT, REPS, ESTIMATE }

/**
 * One broken record, already worded for the screen (the unit conversion happened before this).
 * [value]/[previous] are the weights in the displayed unit and [reps]/[previousReps] the counts; they only
 * size the bars and dots, the words come from [headline] and [detail].
 */
data class RecordUi(
    val name: String, val tier: RecordTier, val headline: String, val detail: String,
    val value: Double = 0.0, val previous: Double = 0.0, val reps: Int = 0, val previousReps: Int = 0,
)

/** A record shown right after a set; [id] changes for every new one so the timer starts over. */
data class RecordBannerUi(val id: Long, val record: RecordUi)

private const val BANNER_VISIBLE_MS = 5_000L
private val PREVIOUS_BAR = Color(0xFF403E3A)

/** Records in the order they matter: top weight first, estimates last. */
@Composable
fun RecordList(records: List<RecordUi>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        records.sortedBy { it.tier.ordinal }.forEach { RecordView(it) }
    }
}

@Composable
fun RecordView(record: RecordUi, modifier: Modifier = Modifier, compact: Boolean = false) {
    when (record.tier) {
        RecordTier.MAX_WEIGHT -> HeaviestRecord(record, modifier, compact)
        RecordTier.REPS -> RepsRecord(record, modifier)
        RecordTier.ESTIMATE -> Text("${record.name} · ${record.headline}", color = KColors.Soft, fontSize = 12.sp, modifier = modifier)
    }
}

/** The system's "remove animations" setting: every record appears in its finished state. */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Last best against today's weight as two bars; today's bar rises into place when the record first shows. */
@Composable
private fun HeaviestRecord(record: RecordUi, modifier: Modifier, compact: Boolean) {
    val area: Dp = if (compact) 56.dp else 96.dp
    val rise = remember(record) { Animatable(0f) }
    val reduced = rememberReducedMotion()
    LaunchedEffect(record) {
        if (reduced) rise.snapTo(1f) else { rise.snapTo(0f); rise.animateTo(1f, tween(620, easing = CubicBezierEasing(.2f, .8f, .2f, 1f))) }
    }
    val gain = if (record.value > 0.0) ((record.value - record.previous) / record.value).toFloat() else 0f
    // Today's bar is always full height; the previous bar is shorter in proportion to the gain, so a small gain still shows.
    val previousShare = (1f - 4.8f * gain).coerceIn(.45f, .92f)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column {
            Row(Modifier.height(area), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(46.dp).fillMaxHeight(previousShare).clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)).background(PREVIOUS_BAR))
                Box(Modifier.width(46.dp).fillMaxHeight(rise.value.coerceAtLeast(.001f)).clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)).background(KColors.Sage))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("이전", color = KColors.Muted, fontSize = 11.sp, modifier = Modifier.width(46.dp), textAlign = TextAlign.Center)
                Text("오늘", color = KColors.Sage, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(46.dp), textAlign = TextAlign.Center)
            }
        }
        Column(Modifier.padding(bottom = 22.dp)) {
            Text("최고 중량 갱신 · ${record.name}", color = KColors.Soft, fontSize = 12.sp)
            Spacer(Modifier.height(2.dp))
            Text(unitSmall(record.headline), color = KColors.White, fontSize = if (compact) 30.sp else 40.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp)
            Text(record.detail, color = KColors.Sage, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** "62.5kg" with the unit set smaller and softer; the text itself stays one string. */
private fun unitSmall(headline: String): AnnotatedString = buildAnnotatedString {
    val split = headline.indexOfFirst { !it.isDigit() && it != '.' }.let { if (it < 0) headline.length else it }
    append(headline.substring(0, split))
    withStyle(SpanStyle(fontSize = 16.sp, color = KColors.Soft, fontWeight = FontWeight.Normal, letterSpacing = 0.sp)) { append(headline.substring(split)) }
}

/** One dot per rep: the earlier best in bone, today's extra reps in sage, popping in one after another. */
@Composable
private fun RepsRecord(record: RecordUi, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("${record.name} · ${record.headline}", color = KColors.Sage, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (record.reps > 0 && record.reps >= record.previousReps) {
            Spacer(Modifier.height(10.dp))
            RepDots(record)
            Spacer(Modifier.height(8.dp))
        }
        Text(record.detail, color = KColors.Soft, fontSize = 12.sp)
    }
}

@Composable
private fun RepDots(record: RecordUi) {
    val reduced = rememberReducedMotion()
    val total = record.reps
    val earlier = record.previousReps.coerceIn(0, total)
    val added = total - earlier
    val pops = remember(record) { List(added) { Animatable(if (reduced) 1f else 0f) } }
    LaunchedEffect(record) {
        if (reduced) return@LaunchedEffect
        pops.forEachIndexed { index, scale ->
            launch {
                delay(260L + 130L * index)
                // One overshoot, then settle: the dot goes "똥".
                scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium))
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (total > 40) {
            // Too many for single dots: one bar split between the earlier reps and today's.
            Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (earlier > 0) Box(Modifier.weight(earlier.toFloat()).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(KColors.Accent))
                if (added > 0) Box(Modifier.weight(added.toFloat()).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(KColors.Sage))
            }
        } else {
            val gap = 6.dp
            val dot = minOf(10.dp, (maxWidth - gap * (total - 1)) / total)
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                repeat(earlier) { Box(Modifier.size(dot).clip(CircleShape).background(KColors.Accent)) }
                repeat(added) { index ->
                    Box(Modifier.size(dot).scale(pops[index].value.coerceAtLeast(0f)).clip(CircleShape).background(KColors.Sage))
                }
            }
        }
    }
}

/** Appears above the rest bar for a few seconds when a set breaks a record; no sound, nothing to dismiss. */
@Composable
fun RecordBanner(banner: RecordBannerUi?, modifier: Modifier = Modifier) {
    // Keyed by the record's id, so every new record starts visible with a fresh timer.
    var visible by remember(banner?.id) { mutableStateOf(banner != null) }
    LaunchedEffect(banner?.id) {
        if (banner == null) return@LaunchedEffect
        delay(BANNER_VISIBLE_MS)
        visible = false
    }
    AnimatedVisibility(visible = visible && banner != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        banner?.let { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) { RecordView(it.record, compact = true) } }
    }
}
