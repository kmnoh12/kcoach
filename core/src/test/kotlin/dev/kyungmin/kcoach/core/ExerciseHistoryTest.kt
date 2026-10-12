package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExerciseHistoryTest {
    private fun log(day: Long, vararg sets: Pair<Double?, Int>) =
        ExerciseLog("bench", day, sets.map { LoggedSet(it.first, it.second) })

    @Test
    fun `codec round trips including bodyweight sets`() {
        val logs = listOf(log(100, 60.0 to 10, 60.0 to 9), ExerciseLog("pullup", 101, listOf(LoggedSet(null, 8))))
        assertEquals(logs, ExerciseHistoryCodec.decode(ExerciseHistoryCodec.encode(logs)))
    }

    @Test
    fun `decode skips broken lines`() {
        assertEquals(emptyList(), ExerciseHistoryCodec.decode("garbage\nbench\tx\t60:10\nbench\t5\t"))
    }

    @Test
    fun `volume and top weight come from the sets`() {
        val l = log(1, 60.0 to 10, 50.0 to 8)
        assertEquals(1000.0, l.volumeKg, 1e-9)
        assertEquals(60.0, l.topWeightKg)
        assertEquals(18, l.totalReps)
    }

    @Test
    fun `last is the newest and the same day is replaced`() {
        val h = ExerciseHistory().with(log(1, 50.0 to 10)).with(log(3, 60.0 to 10)).with(log(3, 62.5 to 8))
        assertEquals(62.5, h.last("bench")?.topWeightKg)
        assertEquals(2, h.recent("bench").size)
    }

    @Test
    fun `history keeps only the newest entries per exercise`() {
        var h = ExerciseHistory()
        for (day in 1L..30L) h = h.with(log(day, 40.0 to 10))
        assertEquals(ExerciseHistory.MAX_PER_EXERCISE, h.recent("bench", 100).size)
        assertEquals(30L, h.recent("bench").first().epochDay)
    }

    @Test
    fun `best volume and heaviest set are found`() {
        val h = ExerciseHistory(listOf(log(1, 100.0 to 3), log(2, 60.0 to 12, 60.0 to 12)))
        assertEquals(2L, h.bestVolume("bench")?.epochDay)
        assertEquals(1L to LoggedSet(100.0, 3), h.heaviestSet("bench"))
        assertNull(h.last("missing"))
    }

    @Test
    fun `reaching the planned reps on every set raises the weight`() {
        val next = NextTargetAdvisor.advise(log(1, 60.0 to 10, 60.0 to 10), plannedReps = 10)
        val raise = assertIs<NextTarget.RaiseWeight>(next)
        assertEquals(62.5, raise.toKg, 1e-9)
    }

    @Test
    fun `light weights step by one kilo`() {
        val raise = assertIs<NextTarget.RaiseWeight>(NextTargetAdvisor.advise(log(1, 8.0 to 12, 8.0 to 12), 12))
        assertEquals(9.0, raise.toKg, 1e-9)
    }

    @Test
    fun `inside the range asks for one more rep and far below holds`() {
        assertIs<NextTarget.AddRep>(NextTargetAdvisor.advise(log(1, 60.0 to 9, 60.0 to 10), 10))
        assertIs<NextTarget.Hold>(NextTargetAdvisor.advise(log(1, 60.0 to 6, 60.0 to 10), 10))
    }

    @Test
    fun `bodyweight exercises never get a weight suggestion`() {
        val l = ExerciseLog("pullup", 1, listOf(LoggedSet(null, 10), LoggedSet(null, 10)))
        assertIs<NextTarget.AddRep>(NextTargetAdvisor.advise(l, 10))
    }

    @Test
    fun `set insight reads tempo and slowdown`() {
        assertEquals("템포 87 · 후반 12% 느려짐", SetInsight.describe(87, 0.12))
        assertEquals("템포 90 · 속도 일정", SetInsight.describe(90, 0.02))
        assertEquals("후반 15% 빨라짐", SetInsight.describe(null, -0.15))
        assertNull(SetInsight.describe(null, null))
    }
}
