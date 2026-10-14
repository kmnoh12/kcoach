package dev.kyungmin.kcoach.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kyungmin.kcoach.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiCoachHistoryTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private fun record(id: String, time: Long, reps: Int? = 8, routine: String = "r", manual: Boolean = true, equipment: String = "barbell") =
        WorkoutSnapshot(sessionId = id, routineId = routine, exerciseIndex = 0, setIndex = 0,
            plannedSets = listOf(PlannedSetSnapshot(0, "press", 0, 8, 40.0, true, exerciseName = "프레스",
                performedReps = reps, repSource = if (manual) SetRepSource.MANUAL else SetRepSource.WATCH,
                equipment = equipment, family = "flat", gripType = "overhand")), performedReps = emptyMap(),
            phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0, paused = false,
            startedAtMs = time, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
            lastCompletedSetKey = null, captureStates = emptyMap())
    private fun save(snapshot: WorkoutSnapshot) {
        val json = JSONObject().put("snapshot", WorkoutSnapshotCodec.encode(snapshot)).put("title", snapshot.sessionId)
            .put("minutes", 30).put("analysisRevision", 0).put("projected", true).put("epochDay", 0)
        app.getSharedPreferences("finished-workouts", Context.MODE_PRIVATE).edit().putString("session:${snapshot.sessionId}", json.toString()).commit()
    }
    private fun profile(account: String = "account-a", ids: List<String>): AiCoachProfile {
        val store = AiCoachStore(app); val profile = store.saveProfile(account, AiCoachProfile())
        store.linkSessions(account, ids); return profile
    }
    private fun build(account: String = "account-a") = JSONObject(AiCoachContextBuilder(app).build(account, "운동 변화 알려줘").json)
    private fun analysis(id: String, reps: Int) {
        val manifest = WireEnvelope(messageType = WireMessageType.CAPTURE_MANIFEST, sessionId = id, source = WireSource.WATCH,
            sentAtElapsedMs = 0, sentAtWallMs = 0, payload = mapOf(WirePayloadKeys.CAPTURE_ID to "capture-$id",
                WirePayloadKeys.EXERCISE_ID to "press", WirePayloadKeys.SET_INDEX to "0"))
        app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE).edit()
            .putString(CaptureInbox.manifestKey("capture-$id"), WireCodec.encode(manifest)).commit()
        val encoded = MutableList(24) { "" }.apply { this[0] = "8"; this[1] = "$reps"; this[2] = "$reps"; this[11] = "0.9" }.joinToString("|")
        SetAnalysisStore(app).persistAnalysis(manifest, encoded)
    }

    @Test fun baselineBeyondOneHundredRecordsIsIncludedAndSummaryUsesTheWholeOwnedHistory() {
        save(record("old", 1)); save(record("latest", 1000, 9))
        val fillers = (1..105).map { "filler-$it" }
        fillers.forEachIndexed { i, id -> save(record(id, 100L + i, routine = "unrelated")) }
        profile(ids = listOf("old", "latest") + fillers)
        val json = build(); val comparison = json.getJSONArray("comparisons").getJSONObject(0)
        assertEquals("session:old", comparison.getString("baseline_evidence_id"))
        val rows = json.getJSONArray("records")
        assertTrue((0 until rows.length()).any { rows.getJSONObject(it).getString("session_id") == "old" })
        assertEquals(107, json.getJSONObject("personal_summary").getInt("completed_sessions"))
        assertTrue(json.getBoolean("context_is_partial")); assertTrue(json.toString().length <= 60_000)
    }

    @Test fun unownedRecentRecordsDoNotCrowdOutOrLeakIntoAnotherAccountsContextOrSummary() {
        save(record("owned-a", 1)); save(record("owned-b", 2, 50))
        (1..105).forEach { save(record("private-$it", 100L + it)) }
        profile(ids = listOf("owned-a")); profile("account-b", listOf("owned-b"))
        val a = build(); val b = build("account-b")
        assertEquals(1, a.getJSONObject("personal_summary").getInt("completed_sessions"))
        assertEquals(8, a.getJSONObject("personal_summary").getInt("normal_reps"))
        assertFalse(a.toString().contains("owned-b")); assertFalse(a.toString().contains("private-"))
        assertEquals(50, b.getJSONObject("personal_summary").getInt("normal_reps"))
        assertNotEquals(a.getJSONObject("personal_summary").getString("evidence_id"), b.getJSONObject("personal_summary").getString("evidence_id"))
    }

    @Test fun summarySurvivesRecreationAndRefreshesOnRecordProfileAndMembershipChanges() {
        save(record("a", 1)); save(record("b", 2, 9)); val p = profile(ids = listOf("a"))
        val first = build().getJSONObject("personal_summary")
        assertEquals(first.toString(), build().getJSONObject("personal_summary").toString())
        save(record("a", 1, 10)); val corrected = build().getJSONObject("personal_summary")
        assertEquals(10, corrected.getInt("normal_reps")); assertNotEquals(first.getString("input_fingerprint"), corrected.getString("input_fingerprint"))
        AiCoachStore(app).saveProfile("account-a", p.copy(goal = "새 목표"))
        val edited = build().getJSONObject("personal_summary")
        assertEquals(2L, edited.getLong("profile_revision")); assertNotEquals(corrected.getString("input_fingerprint"), edited.getString("input_fingerprint"))
        AiCoachStore(app).linkSessions("account-a", listOf("b"))
        val linked = build().getJSONObject("personal_summary")
        assertEquals(2, linked.getInt("completed_sessions")); assertEquals(19, linked.getInt("normal_reps"))
        app.getSharedPreferences("finished-workouts", Context.MODE_PRIVATE).edit().remove("session:b").commit()
        val removed = build().getJSONObject("personal_summary")
        assertEquals(1, removed.getInt("unavailable_linked_sessions")); assertEquals(10, removed.getInt("normal_reps"))
    }

    @Test fun lateAnalysisInvalidatesSummaryAndReadsThroughBeforeRecordReconcileWithoutOverwritingManualCounts() {
        save(record("automatic", 1, manual = false)); save(record("manual", 2))
        profile(ids = listOf("automatic", "manual")); val before = build().getJSONObject("personal_summary")
        analysis("automatic", 6); analysis("manual", 99)
        val json = build(); val after = json.getJSONObject("personal_summary")
        assertNotEquals(before.getString("input_fingerprint"), after.getString("input_fingerprint"))
        assertEquals(14, after.getInt("normal_reps"))
        val rows = json.getJSONArray("records")
        val manual = (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("session_id") == "manual" }
        assertEquals(8, manual.getJSONArray("completed_sets").getJSONObject(0).getInt("performed_reps"))
        assertEquals(99, manual.getJSONArray("sensor_summary").getJSONObject(0).getInt("phone_record_proposal"))
        assertTrue(manual.getBoolean("record_reconciliation_pending"))
        assertFalse(manual.getJSONArray("sensor_summary").getJSONObject(0).has("wrist_motion_range"))
        val original = JSONObject(app.getSharedPreferences("finished-workouts", Context.MODE_PRIVATE).getString("session:automatic", null)!!)
        assertEquals(8, WorkoutSnapshotCodec.decode(original.getString("snapshot")).plannedSets.single().performedReps)
    }

    @Test fun summaryDoesNotSkipADifferentEquipmentSessionToClaimProgressAgainstAnOlderMatchingOne() {
        save(record("old-barbell", 1)); save(record("new-machine", 2, equipment = "machine")); save(record("latest-barbell", 3, 10))
        profile(ids = listOf("old-barbell", "new-machine", "latest-barbell"))
        val json = build(); val trend = json.getJSONObject("personal_summary").getJSONArray("recent_condition_trends").getJSONObject(0)
        assertEquals("session:new-machine", trend.getString("baseline_evidence_id"))
        assertEquals("NEW_CONDITION", trend.getString("condition_status")); assertTrue(trend.isNull("previous_reps"))
        assertEquals(1, json.getJSONArray("comparisons").getJSONObject(0).getJSONArray("removed_conditions").length())
    }

    @Test fun unknownCountsStayUnknownAndModelAdviceIsNotPromotedIntoFactMemory() {
        save(record("unknown", 1, null)); val p = profile(ids = listOf("unknown"))
        AiCoachStore(app).appendTurn("account-a", p.id, AiCoachTurn("질문", "키는 200cm이며 50회 했어요", emptyList(), "m", "m", null, "completed", 1, "{}"))
        val json = build(); val summary = json.getJSONObject("personal_summary")
        assertEquals("COMPUTED_FACTS", summary.getString("kind")); assertTrue(summary.isNull("normal_reps"))
        assertEquals(1, summary.getInt("unknown_rep_sets")); assertFalse(json.toString().contains("200cm"))
        assertTrue(json.getJSONObject("profile").isNull("height_cm"))
    }

    @Test fun firstAndTimestampAmbiguousRecordsAreExplicitAndNeverComparedAgainstAFutureSession() {
        save(record("a", 1)); profile(ids = listOf("a"))
        assertEquals("FIRST_ROUTINE_RECORD", build().getJSONArray("comparisons").getJSONObject(0).getString("baseline_status"))
        save(record("b", 1)); AiCoachStore(app).linkSessions("account-a", listOf("b"))
        val comparisons = build().getJSONArray("comparisons")
        for (i in 0 until comparisons.length()) {
            assertEquals("AMBIGUOUS_TIMESTAMP", comparisons.getJSONObject(i).getString("baseline_status"))
            assertTrue(comparisons.getJSONObject(i).isNull("baseline_evidence_id"))
        }
    }

    @Test fun largeValidProfilesAndSessionsFitTheRequestBudgetAndDiscloseOmissions() {
        val source = record("large", 1)
        val sets = (0 until 100).map { source.plannedSets.single().copy(setIndex = it, exerciseName = "운동".repeat(100)) }
        save(source.copy(plannedSets = sets)); val p = profile(ids = listOf("large"))
        AiCoachStore(app).saveProfile("account-a", p.copy(goal = "\\".repeat(2000), experience = "\\".repeat(2000),
            constraints = "\\".repeat(2000), equipment = "\\".repeat(2000)))
        val json = build()
        assertTrue(json.toString().length <= 60_000); assertTrue(json.getBoolean("context_is_partial"))
        assertEquals(100, json.getJSONObject("personal_summary").getInt("normal_sets"))
        assertEquals(40, json.getJSONArray("records").getJSONObject(0).getInt("sets_omitted"))
    }

    @Test fun regenerableCorruptCacheDoesNotLoseOriginalsOrPreventAContextRequest() {
        save(record("a", 1)); profile(ids = listOf("a")); val first = build().getJSONObject("personal_summary")
        val prefs = app.getSharedPreferences("ai-coach-summary", Context.MODE_PRIVATE)
        val cache = JSONObject(prefs.getString("account-a", null)!!)
        cache.remove("summary"); prefs.edit().putString("account-a", cache.toString()).commit()
        assertEquals(first.toString(), build().getJSONObject("personal_summary").toString())
        prefs.edit().putString("account-a", "broken json").commit()
        assertEquals(first.toString(), build().getJSONObject("personal_summary").toString())
        assertEquals(1, FinishedWorkoutStore(app).evidenceForSessions(setOf("a")).size)
    }

    @Test fun mismatchedStoredKeyCannotSmuggleAnotherSessionsSnapshotThroughAnOwnedId() {
        save(record("private", 1, 99)); profile(ids = listOf("owned"))
        val prefs = app.getSharedPreferences("finished-workouts", Context.MODE_PRIVATE)
        prefs.edit().putString("session:owned", prefs.getString("session:private", null)).commit()
        val json = build()
        assertEquals(0, json.getJSONObject("personal_summary").getInt("completed_sessions"))
        assertFalse(json.toString().contains("private")); assertEquals(0, json.getJSONArray("records").length())
    }
}
