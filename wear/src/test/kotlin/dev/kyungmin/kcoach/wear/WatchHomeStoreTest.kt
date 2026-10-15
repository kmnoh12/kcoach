package dev.kyungmin.kcoach.wear

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchHomeStoreTest {
    @Test fun absentOrOldWeekDoesNotBecomeZeroAndLatePacketsCannotReplaceRecentData() {
        val store = WatchHomeStore(RuntimeEnvironment.getApplication())
        assertNull(store.weekCount())
        val stamp = System.currentTimeMillis()
        fun payload(at: Long, count: Int) = JSONObject().put("schemaVersion", 1).put("generatedAtMs", at)
            .put("weekStartDate", "2026-10-12").put("weekWorkoutCount", count).put("recent", JSONObject()
                .put("sessionId", "finished").put("routineName", "어깨전문").put("endedAtMs", at - 1)).toString()
        assertTrue(store.receiveOverview(payload(stamp, 3)))
        assertEquals(3, store.weekCount(LocalDate.parse("2026-10-15")))
        assertFalse(store.receiveOverview(payload(stamp - 1, 1)))
        assertEquals(3, store.weekCount(LocalDate.parse("2026-10-15")))
        assertNull(store.weekCount(LocalDate.parse("2026-10-19")))
    }
    @Test fun completionRetainsExactSessionAcrossRecreationAndOverviewDoesNotReplaceIt() {
        val context = RuntimeEnvironment.getApplication()
        WatchHomeStore(context).complete("finished-exact")
        val restored = WatchHomeStore(context)
        assertEquals("finished-exact", restored.completion()!!.sessionId)
        assertTrue(restored.showsCompletion())
        restored.leaveCompletion()
        assertFalse(WatchHomeStore(context).showsCompletion())
        assertEquals("finished-exact", WatchHomeStore(context).completion()!!.sessionId)
    }
    @Test fun invalidSummaryCannotFabricateCountsOrOverwriteTheStoredSnapshot() {
        val store = WatchHomeStore(RuntimeEnvironment.getApplication())
        assertFalse(store.receiveOverview("{}"))
        assertNull(store.overview())
        assertFalse(store.receiveOverview(JSONObject().put("schemaVersion", 1).put("generatedAtMs", System.currentTimeMillis())
            .put("weekStartDate", "invalid").put("weekWorkoutCount", 0).toString()))
    }
}
