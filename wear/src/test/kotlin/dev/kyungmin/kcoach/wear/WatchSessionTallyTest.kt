package dev.kyungmin.kcoach.wear

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchSessionTallyTest {
    private val tally get() = WatchSessionTally.get(RuntimeEnvironment.getApplication())

    @Test fun aPauseIsNotTheEndOfASetAndAnEditedSetCountsWithItsNewValues() {
        tally.observe("s", "a", "1", true, false, false, 6, 40.0, 0, 1_000L)
        tally.observe("s", "a", "1", false, false, true, 6, 40.0, 0, 2_000L)       // paused
        tally.observe("s", "a", "1", true, false, false, 10, 40.0, 0, 3_000L)      // resumed
        tally.observe("s", "b", "1", false, true, false, 10, 40.0, 0, 4_000L)      // rest: set a is over
        tally.editLast(reps = 9, weightKg = 42.5)
        val summary = requireNotNull(tally.finish("s", 61_000L))
        assertEquals(1, summary.sets); assertEquals(9 * 42.5, summary.volumeKg!!, 1e-9)
        assertEquals(60_000L, summary.elapsedMs)
        assertNull("No heart rate was read", summary.averageHeartRate)
        assertNull("No records were announced", summary.recordsBroken)
    }

    @Test fun recordsCountDistinctSetsSurviveRestartAndIgnoreOtherSessions() {
        tally.observe("records", "a", "1", true, false, false, 8, 20.0, 0, 1_000L)
        tally.noteSetRecord("records", "a")
        tally.noteSetRecord("records", "a")
        tally.noteSetRecord("wrong", "b")
        WatchSessionTally::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        tally.noteSetRecord("records", "a")
        tally.noteSetRecord("records", "b")
        tally.noteRecord("records", "exercise") // legacy UI callback must not add a third record
        assertEquals(2, tally.finish("records", 2_000L)!!.recordsBroken)
    }

    @Test fun calibrationAndUnknownSessionsLeaveNothingBehind() {
        tally.observe("calibration-1", "", "", true, false, false, 5, null, 100, 1_000L)
        assertNull(tally.finish("calibration-1", 2_000L))
        assertNull(tally.finish("never-seen", 2_000L))
    }

    @Test fun theTotalsSurviveARestartOfTheProcess() {
        tally.observe("r", "a", "1", true, false, false, 8, 20.0, 0, 1_000L)
        tally.observe("r", "a", "1", false, false, false, 8, 20.0, 0, 2_000L)
        // A fresh application context stands in for a new process reading the saved totals.
        val field = WatchSessionTally::class.java.getDeclaredField("instance").apply { isAccessible = true }
        field.set(null, null)
        val summary = requireNotNull(tally.finish("r", 11_000L))
        assertEquals(1, summary.sets); assertEquals(160.0, summary.volumeKg!!, 1e-9)
    }
}
