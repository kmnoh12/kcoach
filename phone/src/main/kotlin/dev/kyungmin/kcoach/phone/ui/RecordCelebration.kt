package dev.kyungmin.kcoach.phone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** How loud a record is: a heavier top weight is the big moment, a rep or an estimate is a quiet note. */
enum class RecordTier { MAX_WEIGHT, REPS, ESTIMATE }

/** One broken record, already worded for the screen (the unit conversion happened before this). */
data class RecordUi(val name: String, val tier: RecordTier, val headline: String, val detail: String)

/** A record shown right after a set; [id] changes for every new one so the timer starts over. */
data class RecordBannerUi(val id: Long, val record: RecordUi)

private const val BANNER_VISIBLE_MS = 5_000L

/** Records in the order they matter: top weight first, estimates last. */
@Composable
fun RecordList(records: List<RecordUi>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        records.sortedBy { it.tier.ordinal }.forEach { RecordView(it) }
    }
}

@Composable
fun RecordView(record: RecordUi, modifier: Modifier = Modifier) {
    when (record.tier) {
        RecordTier.MAX_WEIGHT -> Column(
            modifier.fillMaxWidth().clip(KShape.Panel).background(KColors.AccentWash)
                .border(0.5.dp, KColors.Sage, KShape.Panel).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("최고 중량 갱신 · ${record.name}", color = KColors.Soft, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(record.headline, color = KColors.White, fontSize = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp)
                Spacer(Modifier.width(12.dp))
                Text(record.detail, color = KColors.Sage, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        RecordTier.REPS -> Column(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text("${record.name} · ${record.headline}", color = KColors.Sage, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(record.detail, color = KColors.Soft, fontSize = 12.sp)
        }
        RecordTier.ESTIMATE -> Text("${record.name} · ${record.headline}", color = KColors.Soft, fontSize = 12.sp, modifier = modifier)
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
        banner?.let { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) { RecordView(it.record) } }
    }
}
