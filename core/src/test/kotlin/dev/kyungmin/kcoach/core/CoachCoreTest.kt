package dev.kyungmin.kcoach.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CoachCoreTest {
    private val bench = ExercisePlan(
        id = "bench",
        name = "바벨 플랫 벤치 프레스",
        sets = listOf(
            SetPlan(12, 60.0, 120),
            SetPlan(12, 60.0, 120),
        ),
        repRange = 10..13,
    )

    @Test
    fun `routine advances through rest and completes`() {
        val coach = SessionCoach(RoutinePlan("chest", "가슴", listOf(bench)))
        assertIs<SessionPhase.Working>(coach.start().phase)
        coach.completeSet(completed(0, 12))
        val rest = coach.tickRest(120, HeartRateSnapshot(110, 145, 120))
        assertTrue(rest.cues.contains(CoachCue.RestTargetReached))
        assertIs<SessionPhase.Working>(coach.startNextSet().phase)
        coach.completeSet(completed(1, 12))
        coach.tickRest(120, HeartRateSnapshot(108, 142, 120))
        assertIs<SessionPhase.Completed>(coach.startNextSet().phase)
    }

    @Test
    fun `high heart rate recommends extra rest instead of nagging`() {
        val coach = SessionCoach(RoutinePlan("chest", "가슴", listOf(bench)))
        coach.start()
        coach.completeSet(completed(0, 12))
        val update = coach.tickRest(120, HeartRateSnapshot(138, 160, 120))
        assertTrue(update.cues.any { it is CoachCue.RecommendExtraRest })
    }

    @Test
    fun `incline estimate matches current MyMountain routine`() {
        val result = InclineEnergyEstimator().estimateWalking(
            weightKg = 72.0,
            speedKmh = 4.0,
            gradePercent = 13.0,
            durationMinutes = 30.0,
        )
        assertTrue(abs(result.grossKilocalories - 278.4) < 1.0)
        assertTrue(abs(result.activeKilocalories - 240.6) < 1.0)
    }

    @Test
    fun `double progression adds load only after all sets hit top`() {
        val fourSetBench = bench.copy(
            sets = List(4) { SetPlan(13, 60.0, 120) },
        )
        val sets = List(4) { index -> completed(index, 13, rir = 1.5) }
        val decision = ProgressionEngine().decide(fourSetBench, sets, 2.5)
        assertEquals(ProgressionDecision.IncreaseWeight::class, decision::class)
    }

    @Test
    fun `rep floor blocks progression`() {
        val sets = listOf(
            completed(0, 9),
            completed(1, 10),
        )
        assertIs<ProgressionDecision.Hold>(
            ProgressionEngine().decide(bench, sets, 2.5),
        )
    }

    private fun completed(
        index: Int,
        reps: Int,
        rir: Double = 2.0,
    ) = CompletedSet(
        exerciseId = "bench",
        setIndex = index,
        reps = reps,
        weightKg = 60.0,
        rir = rir,
        averageHeartRate = 120,
        repSource = RepSource.CUSTOM_IMU,
        completedAtEpochMillis = 0,
    )
}
