package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkoutLogTest {
    private val logs = listOf(
        WorkoutLog(100, "등·이두", 68, 22, 7840),
        WorkoutLog(101, "가슴·삼두", 81, 26, 10450),
        WorkoutLog(130, "등·이두", 60, 20, 7000),
    )

    @Test
    fun roundTrip() {
        assertEquals(logs, WorkoutLogCodec.decode(WorkoutLogCodec.encode(logs)))
    }

    @Test
    fun badLinesAreSkipped() {
        assertEquals(emptyList(), WorkoutLogCodec.decode("a\tb"))
        assertEquals(emptyList(), WorkoutLogCodec.decode(""))
    }

    @Test
    fun periodTotalsOnlyCountDaysInRange() {
        assertEquals(PeriodStats(2, 149, 48, 18290), WorkoutStats.between(logs, 100, 120))
        assertEquals(PeriodStats(0, 0, 0, 0), WorkoutStats.between(logs, 200, 210))
    }

    @Test
    fun byTitleSumsSetsAndSortsDescending() {
        assertEquals(listOf("등·이두" to 42, "가슴·삼두" to 26), WorkoutStats.byTitle(logs))
    }
}
