package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainingLoadTest {
    @Test
    fun `a usual session is close to full`() {
        assertEquals(0.93, TrainingLoad.score(69, 22), 0.01)
    }

    @Test
    fun `a high volume day is the tallest`() {
        val heavy = TrainingLoad.score(75, 29)
        assertEquals(1.08, heavy, 0.01)
        assertTrue(heavy > TrainingLoad.score(69, 22))
    }

    @Test
    fun `a light short day is clearly lower`() {
        assertEquals(0.53, TrainingLoad.score(40, 12), 0.01)
    }

    @Test
    fun `time beyond the reference does not add load`() {
        assertEquals(TrainingLoad.score(70, 25), TrainingLoad.score(110, 25), 1e-9)
    }

    @Test
    fun `score never exceeds the maximum`() {
        assertEquals(TrainingLoad.MAX_SCORE, TrainingLoad.score(300, 200), 1e-9)
        assertEquals(0.0, TrainingLoad.score(0, 0), 1e-9)
    }

    @Test
    fun `days ago parses the stored label`() {
        assertEquals(28, TrainingLoad.daysAgo("28일 전"))
        assertEquals(0, TrainingLoad.daysAgo("오늘"))
        assertNull(TrainingLoad.daysAgo("기록 없음"))
    }
}
