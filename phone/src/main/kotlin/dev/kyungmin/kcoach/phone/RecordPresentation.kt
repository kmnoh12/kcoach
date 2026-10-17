package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.RecordHit
import dev.kyungmin.kcoach.core.RecordKind
import dev.kyungmin.kcoach.phone.ui.RecordTier
import dev.kyungmin.kcoach.phone.ui.RecordUi
import java.util.Locale

/** Words a broken record for the screen, in the unit the user trains in. */
object RecordPresentation {
    private const val POUNDS_PER_KG = 2.20462

    fun record(name: String, hit: RecordHit, pounds: Boolean): RecordUi {
        val unit = if (pounds) POUNDS_PER_KG else 1.0
        val suffix = if (pounds) "lb" else "kg"
        fun weight(kg: Double) = clean(kg * unit)
        return when (hit.kind) {
            RecordKind.HEAVIEST_WEIGHT -> RecordUi(name, RecordTier.MAX_WEIGHT, "${weight(hit.value)}$suffix",
                "이전 ${weight(hit.previous)}$suffix · +${weight(hit.value - hit.previous)}$suffix",
                value = hit.value * unit, previous = hit.previous * unit)
            RecordKind.SAME_WEIGHT_REPS -> RecordUi(name, RecordTier.REPS,
                "${hit.weightKg?.let { "${weight(it)}$suffix " }.orEmpty()}${clean(hit.value)}회",
                "이 무게 최고 횟수 · 이전 ${clean(hit.previous)}회", reps = hit.value.toInt(), previousReps = hit.previous.toInt())
            RecordKind.ESTIMATED_ONE_REP_MAX -> RecordUi(name, RecordTier.ESTIMATE,
                "추정 1RM ${weight(hit.previous)} → ${weight(hit.value)}$suffix", "")
            RecordKind.BEST_VOLUME -> RecordUi(name, RecordTier.ESTIMATE,
                "최고 볼륨 ${weight(hit.previous)} → ${weight(hit.value)}$suffix", "")
        }
    }

    private fun clean(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
}

/**
 * Decides which record to celebrate right after a set: the first one an exercise breaks, then only a stronger
 * kind or a bigger value, so finishing more sets at the same level stays quiet.
 */
class RecordBannerTracker {
    private val shown = mutableMapOf<String, Pair<Int, Double>>()

    fun reset() = shown.clear()

    fun pick(hits: List<RecordHit>): RecordHit? {
        val hit = hits.minWithOrNull(compareBy<RecordHit> { rank(it.kind) }.thenByDescending { it.value }) ?: return null
        val last = shown[hit.exerciseId]
        val stronger = last == null || rank(hit.kind) < last.first || (rank(hit.kind) == last.first && hit.value > last.second)
        if (!stronger) return null
        shown[hit.exerciseId] = rank(hit.kind) to hit.value
        return hit
    }

    private fun rank(kind: RecordKind) = when (kind) {
        RecordKind.HEAVIEST_WEIGHT -> 0
        RecordKind.SAME_WEIGHT_REPS -> 1
        RecordKind.ESTIMATED_ONE_REP_MAX -> 2
        RecordKind.BEST_VOLUME -> 3
    }
}
