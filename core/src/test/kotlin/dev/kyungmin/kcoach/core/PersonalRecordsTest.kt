package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalRecordsTest {
    private fun log(day: Long, vararg sets: Pair<Double?, Int>) =
        ExerciseLog("bench", day, sets.map { LoggedSet(it.first, it.second) })

    private val history = ExerciseHistory(listOf(log(1, 60.0 to 8, 60.0 to 8), log(5, 62.5 to 6)))

    @Test
    fun firstSessionIsNeverARecord() {
        assertEquals(emptyList(), PersonalRecords.detect(ExerciseHistory(), log(10, 100.0 to 5)))
    }

    @Test fun laterLegacyDaysCannotSuppressAnEarlierRecord() {
        val history = ExerciseHistory(listOf(log(1, 60.0 to 8), log(20, 100.0 to 8)))
        assertEquals(listOf(RecordHit("bench", RecordKind.HEAVIEST_WEIGHT, 65.0, 60.0)),
            PersonalRecords.detect(history, log(10, 65.0 to 8)))
    }

    @Test
    fun heavierSetBeatsHeaviestWeight() {
        val hits = PersonalRecords.detect(history, log(10, 65.0 to 5))
        assertEquals(listOf(RecordHit("bench", RecordKind.HEAVIEST_WEIGHT, 65.0, 62.5)), hits)
    }

    @Test
    fun lowerWeightRepGainOnlyReturnsAuxiliaryEstimate() {
        val hits = PersonalRecords.detect(history, log(10, 60.0 to 10, 60.0 to 10))
        assertEquals(RecordKind.ESTIMATED_ONE_REP_MAX, hits.single().kind)
    }

    @Test fun sameWeightRepsAreTheSecondTier() {
        val hits = PersonalRecords.detect(history, log(10, 62.5 to 7))
        assertEquals(listOf(RecordHit("bench", RecordKind.SAME_WEIGHT_REPS, 7.0, 6.0)), hits)
    }
    @Test fun lowerWeightHighRepsAndWarmupsAreNotRecords() {
        assertEquals(emptyList(), PersonalRecords.detect(history, log(10, 60.0 to 15)))
        assertEquals(emptyList(), PersonalRecords.detect(history, ExerciseLog("bench", 10, listOf(LoggedSet(100.0, 10, isWarmup = true)))))
    }
    @Test fun weightedPullupEstimateRequiresBodyMass() {
        fun pull(day: Long, weight: Double, reps: Int, mass: Double?) = ExerciseLog("pull", day,
            listOf(LoggedSet(weight, reps, bodyWeightKg = mass, load = ProgressionLoad.WEIGHTED_BODYWEIGHT)))
        val old = pull(1, 10.0, 5, 70.0)
        val newer = pull(2, 5.0, 10, 70.0)
        val hit = PersonalRecords.detect(ExerciseHistory(listOf(old)), newer).single()
        assertEquals(RecordKind.ESTIMATED_ONE_REP_MAX, hit.kind)
        assertEquals(100.0, hit.value)
        assertEquals(emptyList(), PersonalRecords.detect(ExerciseHistory(listOf(old)), pull(2, 5.0, 10, null)))
    }

    @Test
    fun equalOrLowerIsNotARecord() {
        assertEquals(emptyList(), PersonalRecords.detect(history, log(10, 62.5 to 6)))
    }

    @Test
    fun sameDayRerunIsComparedToEarlierDaysOnly() {
        val withToday = ExerciseHistory(history.all + log(10, 65.0 to 5))
        val hits = PersonalRecords.detect(withToday, log(10, 66.0 to 5))
        assertEquals(RecordKind.HEAVIEST_WEIGHT, hits.first().kind)
        assertEquals(62.5, hits.first().previous)
    }
}
