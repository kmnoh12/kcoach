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
class AiCoachContextTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private fun set(reps: Int?, kg: Double? = 40.0, type: String = "NORMAL", equipment: String = "barbell") =
        PlannedSetSnapshot(0, "press", 0, 8, kg, true, type, "프레스", performedReps = reps,
            repSource = SetRepSource.MANUAL, equipment = equipment)
    private fun finish(id: String, started: Long, sets: List<PlannedSetSnapshot>, routine: String = "routine") {
        val snapshot = WorkoutSnapshot(sessionId = id, routineId = routine, exerciseIndex = 0, setIndex = 0,
            plannedSets = sets.mapIndexed { index, value -> value.copy(setIndex = index) }, performedReps = emptyMap(),
            phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0, paused = false,
            startedAtMs = started, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
            lastCompletedSetKey = null, captureStates = emptyMap())
        FinishedWorkoutStore(app).finish(snapshot, "루틴 이름 $id", 30)
    }

    @Test fun emptyProfileStaysUnknownAndEditsUseTheirSavedRevision() {
        val store = AiCoachStore(app)
        val saved = store.saveProfile("account-a", AiCoachProfile())
        assertTrue(saved.json().isNull("height_cm")); assertTrue(saved.json().isNull("weight_kg"))
        assertEquals("", saved.goal)
        assertEquals(1, saved.revision)
        val edited = store.saveProfile("account-a", saved.copy(goal = "근비대"))
        assertEquals(2, edited.revision)
        try { store.saveProfile("account-a", saved.copy(goal = "오래된 수정")); fail("Stale edit") }
        catch (_: IllegalArgumentException) { }
        assertEquals("근비대", store.profile("account-a")!!.goal)
        try { AiCoachProfile(weightKg = 70.0); fail("Weight requires its measurement date") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun accountsCannotShareProfilesOrTakeEachOthersLinkedSessions() {
        val store = AiCoachStore(app)
        val profile = store.saveProfile("account-a", AiCoachProfile())
        try { store.saveProfile("account-b", profile); fail("Shared identity") } catch (_: IllegalArgumentException) { }
        store.saveProfile("account-b", AiCoachProfile())
        store.linkSessions("account-a", listOf("session-a"))
        try { store.linkSessions("account-b", listOf("session-a", "session-b")); fail("Owned record") }
        catch (_: IllegalArgumentException) { }
        assertEquals(setOf("session-a"), store.sessionIds("account-a"))
        assertTrue(store.sessionIds("account-b").isEmpty())
    }

    @Test fun contextUsesOnlyLinkedRecordsAndComputesNormalSetChangeAcrossRoutineRenames() {
        finish("before", 1000, listOf(set(8), set(8), set(8), set(25, 5.0, "WARMUP")))
        finish("after", 2000, listOf(set(8), set(8), set(9), set(1, 5.0, "WARMUP")))
        finish("other-person", 3000, listOf(set(99)))
        val store = AiCoachStore(app)
        store.saveProfile("account-a", AiCoachProfile())
        store.linkSessions("account-a", listOf("before", "after"))
        val context = AiCoachContextBuilder(app).build("account-a", "변화 알려줘")
        assertFalse(context.json.contains("other-person"))
        val comparison = JSONObject(context.json).getJSONArray("comparisons").getJSONObject(0)
        assertEquals("session:after", comparison.getString("current_evidence_id"))
        assertEquals("session:before", comparison.getString("baseline_evidence_id"))
        val exercise = comparison.getJSONArray("exercises").getJSONObject(0)
        assertEquals(24, exercise.getInt("previous_reps")); assertEquals(25, exercise.getInt("current_reps"))
        assertEquals(960.0, exercise.getDouble("previous_volume_kg_reps"), 0.0)
        assertEquals(1000.0, exercise.getDouble("current_volume_kg_reps"), 0.0)
        assertEquals(4.1666667, exercise.getDouble("volume_change_percent"), 0.000001)
        assertFalse(exercise.getBoolean("set_count_changed"))
    }

    @Test fun unknownCountsAndDifferentEquipmentAreNotInventedAsZeroOrProgression() {
        finish("before", 1000, listOf(set(8)))
        finish("after", 2000, listOf(set(null, null, equipment = "machine")))
        val store = AiCoachStore(app)
        store.saveProfile("account-a", AiCoachProfile())
        store.linkSessions("account-a", listOf("before", "after"))
        val json = JSONObject(AiCoachContextBuilder(app).build("account-a", "분석").json)
        val exercise = json.getJSONArray("comparisons").getJSONObject(0).getJSONArray("exercises").getJSONObject(0)
        assertTrue(exercise.isNull("previous_reps")); assertTrue(exercise.isNull("current_reps"))
        assertTrue(exercise.isNull("volume_change_percent"))
        assertEquals("machine", exercise.getString("equipment"))
    }

    @Test fun onlyValidatedCompleteAnswersWithKnownEvidenceCanBeStoredForTheirProfile() {
        val store = AiCoachStore(app)
        val profile = store.saveProfile("account-a", AiCoachProfile())
        val context = AiCoachContext(profile.id, "{}", setOf("session:valid"))
        val parsed = AiCoachAnswer.parse("{\"answer\":\"확인한 기록의 해석\",\"citations\":[\"session:valid\"]}", context)
        try { AiCoachAnswer.parse("{\"answer\":\"없는 기록\",\"citations\":[\"session:invented\"]}", context); fail("Unknown evidence") }
        catch (_: IllegalArgumentException) { }
        val turn = AiCoachTurn("질문", parsed.text, parsed.citations, "chosen-model", "actual-model", "high",
            "response-completed", 123, context.json)
        store.appendTurn("account-a", profile.id, turn)
        assertEquals(listOf(turn), AiCoachStore(app).turns("account-a"))
        assertTrue(store.turns("account-b").isEmpty())
        try { store.appendTurn("account-a", "other-profile", turn); fail("Wrong profile") }
        catch (_: IllegalArgumentException) { }
    }
}
