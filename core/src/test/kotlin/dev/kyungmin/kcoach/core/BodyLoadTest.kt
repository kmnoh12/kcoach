package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BodyLoadTest {
    private fun log(id: String, day: Long, sets: Int) = ExerciseLog(id, day, List(sets) { LoggedSet(50.0, 10) })
    private val parts = mapOf("bench" to "가슴", "row" to "등", "fly" to "가슴")

    @Test
    fun countsSetsPerPartInsideTheWindowOnly() {
        val logs = listOf(log("bench", 100, 4), log("fly", 98, 3), log("row", 93, 5), log("row", 101, 2))
        val result = BodyLoad.setsByPart(logs, parts::get, today = 100)
        assertEquals(mapOf("가슴" to 7), result)
    }

    @Test
    fun unknownExercisesAreSkipped() {
        assertEquals(emptyMap(), BodyLoad.setsByPart(listOf(log("mystery", 100, 3)), parts::get, today = 100))
    }

    @Test fun savedLayoutDoesNotCountUnfinishedOrUnenteredSetsAsTraining() {
        val rows = listOf(LoggedSet(50.0, 6), LoggedSet(50.0, 0, completed = false, repsEntered = false),
            LoggedSet(50.0, 0, repsEntered = false))
        assertEquals(mapOf("가슴" to 1), BodyLoad.setsByPart(listOf(ExerciseLog("bench", 100, rows)), parts::get, 100))
    }

    @Test
    fun intensityIsCappedAtOne() {
        val result = BodyLoad.intensity(mapOf("가슴" to 6, "등" to 40))
        assertEquals(0.5, result["가슴"])
        assertEquals(1.0, result["등"])
    }
}
