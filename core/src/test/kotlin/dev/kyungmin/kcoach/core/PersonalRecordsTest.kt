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

    @Test
    fun heavierSetBeatsHeaviestWeight() {
        val hits = PersonalRecords.detect(history, log(10, 65.0 to 5))
        assertEquals(listOf(RecordHit("bench", RecordKind.HEAVIEST_WEIGHT, 65.0, 62.5)), hits)
    }

    @Test
    fun moreVolumeBeatsBestVolume() {
        val hits = PersonalRecords.detect(history, log(10, 60.0 to 10, 60.0 to 10))
        assertEquals(listOf(RecordHit("bench", RecordKind.BEST_VOLUME, 1200.0, 960.0)), hits)
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
