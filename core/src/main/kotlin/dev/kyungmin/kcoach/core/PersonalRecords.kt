package dev.kyungmin.kcoach.core

enum class RecordKind { HEAVIEST_WEIGHT, BEST_VOLUME }

/** One broken record. [previous] is what stood before today, in kg. */
data class RecordHit(val exerciseId: String, val kind: RecordKind, val value: Double, val previous: Double)

object PersonalRecords {
    /**
     * Records broken by [today] against everything before it. An exercise with no earlier log has nothing to beat,
     * so the first session never counts as a record.
     */
    fun detect(history: ExerciseHistory, today: ExerciseLog): List<RecordHit> {
        val id = today.exerciseId
        if (history.recent(id, 1).isEmpty()) return emptyList()
        val before = ExerciseHistory(history.all.filter { it.exerciseId == id && it.epochDay != today.epochDay })
        if (before.last(id) == null) return emptyList()
        return buildList {
            val heaviest = today.topWeightKg
            val heaviestBefore = before.heaviestSet(id)?.second?.weightKg
            if (heaviest != null && heaviestBefore != null && heaviest > heaviestBefore) {
                add(RecordHit(id, RecordKind.HEAVIEST_WEIGHT, heaviest, heaviestBefore))
            }
            val volumeBefore = before.bestVolume(id)?.volumeKg
            if (volumeBefore != null && volumeBefore > 0.0 && today.volumeKg > volumeBefore) {
                add(RecordHit(id, RecordKind.BEST_VOLUME, today.volumeKg, volumeBefore))
            }
        }
    }
}
