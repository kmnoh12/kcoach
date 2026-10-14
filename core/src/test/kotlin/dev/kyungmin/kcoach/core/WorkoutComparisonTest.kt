package dev.kyungmin.kcoach.core

import kotlin.test.*

class WorkoutComparisonTest {
    private fun set(reps: Int?, kg: Double? = 40.0, type: String = "NORMAL", equipment: String = "barbell") =
        PlannedSetSnapshot(0, "press", 0, 8, kg, true, type, performedReps = reps, equipment = equipment, family = "flat", gripType = "overhand")
    private fun record(id: String, time: Long, sets: List<PlannedSetSnapshot>, routine: String = "r") =
        WorkoutSnapshot(sessionId = id, routineId = routine, exerciseIndex = 0, setIndex = 0,
            plannedSets = sets.mapIndexed { i, s -> s.copy(setIndex = i) }, performedReps = emptyMap(),
            phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0, paused = false,
            startedAtMs = time, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
            lastCompletedSetKey = null, captureStates = emptyMap())

    @Test fun sameDayAndRoutineRenamesStillUseThePreviousSessionIdentity() {
        val old = record("a", 1_000, listOf(set(8))).copy(routineName = "이전 이름")
        val latest = record("b", 2_000, listOf(set(9))).copy(routineName = "바꾼 이름")
        val other = record("c", 1_900, listOf(set(20)), "other")
        assertEquals(old, WorkoutComparison.previous(latest, listOf(latest, old, other)))
        assertNull(WorkoutComparison.previous(old, listOf(old, latest, other)))
    }

    @Test fun timestampTiesDoNotInventAnOrderFromSessionNames() {
        val a = record("a", 1_000, listOf(set(8))); val b = record("b", 1_000, listOf(set(9)))
        val c = record("c", 2_000, listOf(set(10)))
        assertNull(WorkoutComparison.previous(b, listOf(a, b)))
        assertNull(WorkoutComparison.previous(c, listOf(a, b, c)))
    }

    @Test fun warmupsAndIncompleteSetsNeverInflateProgress() {
        val before = record("a", 1, listOf(set(8), set(8), set(8), set(50, 5.0, "WARMUP")))
        val after = record("b", 2, listOf(set(8), set(8), set(9), set(1, 5.0, "WARMUP"), set(90).copy(completed = false)))
        val row = WorkoutComparison.compare(after, before).single()
        assertEquals(24L, row.previous!!.reps); assertEquals(25L, row.current.reps)
        assertEquals(40.0, row.volumeChange); assertEquals(1000.0, row.current.volumeKgReps)
        assertEquals(4.1666666667, row.volumeChangePercent!!, 0.000001)
        assertEquals(1L, row.sameWeight.single().currentReps - row.sameWeight.single().previousReps)
        assertTrue(row.sameWeight.single().comparableSetCount)
    }

    @Test fun extraSetsAreNotReportedAsLikeForLikeRepProgress() {
        val row = WorkoutComparison.compare(record("b", 2, listOf(set(8), set(8), set(8))), record("a", 1, listOf(set(8), set(8)))).single()
        assertTrue(row.setCountChanged); assertFalse(row.sameWeight.single().comparableSetCount)
        assertTrue(row.sameReps.isEmpty()); assertEquals(320.0, row.volumeChange)
    }

    @Test fun equalRepsExposeWeightChangesWithoutComparingDifferentConditions() {
        val before = record("a", 1, listOf(set(8, 40.0), set(8, 42.5)))
        val after = record("b", 2, listOf(set(8, 45.0), set(8, 42.5)))
        val row = WorkoutComparison.compare(after, before).single()
        assertEquals(listOf(40.0, 42.5), row.sameReps.single().previousWeightsKg)
        assertEquals(listOf(42.5, 45.0), row.sameReps.single().currentWeightsKg)
        for (changed in listOf(set(8).copy(equipment = "machine"), set(8).copy(gripType = "neutral"), set(8).copy(family = "incline"))) {
            val different = WorkoutComparison.compare(record("c", 3, listOf(changed)), before).single()
            assertNull(different.previous); assertNull(different.volumeChange)
            assertTrue(different.sameReps.isEmpty()); assertTrue(different.sameWeight.isEmpty())
        }
    }

    @Test fun unknownCountsWeightsAndZeroBaselineRemainDistinct() {
        val missing = WorkoutComparison.compare(record("b", 2, listOf(set(null), set(8, null))), record("a", 1, listOf(set(8)))).single()
        assertNull(missing.current.reps); assertNull(missing.current.volumeKgReps); assertNull(missing.volumeChange)
        assertTrue(missing.sameWeight.isEmpty())
        val zero = WorkoutComparison.compare(record("b", 2, listOf(set(1))), record("a", 1, listOf(set(0)))).single()
        assertEquals(40.0, zero.volumeChange); assertNull(zero.volumeChangePercent)
        assertEquals(0L, zero.previous!!.reps)
    }

    @Test fun oldMissingMetadataIsMarkedUnknownRatherThanAssumedCorrect() {
        val blank = set(8).copy(equipment = "", gripType = "", family = "")
        val row = WorkoutComparison.compare(record("b", 2, listOf(blank)), record("a", 1, listOf(blank))).single()
        assertEquals(listOf("equipment", "grip", "family"), row.condition.missingFields)
    }
}
