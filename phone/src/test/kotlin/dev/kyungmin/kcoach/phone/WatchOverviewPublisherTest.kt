package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchOverviewPublisherTest {
    @Test fun legacyWeekRecordsAreCountedWithoutInventingAResultSession() {
        val app = RuntimeEnvironment.getApplication()
        val now = Instant.parse("2026-10-15T08:00:00Z").toEpochMilli()
        val store = WorkoutLogStore(app)
        for (date in listOf("2026-10-11", "2026-10-12", "2026-10-15", "2026-10-19")) {
            store.add(WorkoutLog(java.time.LocalDate.parse(date).toEpochDay(), "루틴", 10, 1, 100))
        }
        val overview = WatchOverviewPublisher.snapshot(app, now, ZoneId.of("Asia/Seoul"))
        assertEquals("2026-10-12", overview.getString("weekStartDate"))
        assertEquals(2, overview.getInt("weekWorkoutCount"))
        assertTrue(overview.isNull("recent"))
    }

    @Test fun aDurablyFinishedManualWorkoutProvidesItsExactResultId() {
        val app = RuntimeEnvironment.getApplication()
        val start = System.currentTimeMillis() - 60_000
        val snapshot = WorkoutSnapshot(sessionId = "finished-manual", routineId = "r", exerciseIndex = 0, setIndex = 0,
            plannedSets = listOf(PlannedSetSnapshot(0, "leg_press", 0, 8, 40.0, true,
                exerciseName = "레그 프레스", performedReps = 8, repSource = SetRepSource.MANUAL)),
            performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
            paused = false, startedAtMs = start, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
            lastCompletedSetKey = null, captureStates = emptyMap())
        FinishedWorkoutStore(app).finish(snapshot, "하체 루틴", 1)
        val now = System.currentTimeMillis()
        val overview = WatchOverviewPublisher.snapshot(app, now)
        assertEquals(1, overview.getInt("weekWorkoutCount"))
        val recent = overview.getJSONObject("recent")
        assertEquals("finished-manual", recent.getString("sessionId"))
        assertEquals("하체 루틴", recent.getString("routineName"))
        assertTrue(recent.getLong("endedAtMs") <= overview.getLong("generatedAtMs"))
    }
}
