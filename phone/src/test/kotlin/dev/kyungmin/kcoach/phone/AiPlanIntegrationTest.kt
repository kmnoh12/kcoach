package dev.kyungmin.kcoach.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiPlanIntegrationTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private fun snapshot(id: String, time: Long = 1_000, reps: Int = 8) = WorkoutSnapshot(sessionId = id, routineId = "routine",
        exerciseIndex = 0, setIndex = 0, plannedSets = listOf(PlannedSetSnapshot(0, "flat_bench", 0, 8, 40.0, true,
            exerciseName = "벤치", performedReps = reps, repSource = SetRepSource.MANUAL, setId = "set-$id", attemptId = "attempt-$id")),
        performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
        paused = false, startedAtMs = time, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
        lastCompletedSetKey = null, captureStates = emptyMap())
    private fun save(s: WorkoutSnapshot) {
        app.getSharedPreferences("finished-workouts", Context.MODE_PRIVATE).edit().putString("session:${s.sessionId}",
            JSONObject().put("snapshot", WorkoutSnapshotCodec.encode(s)).put("title", s.sessionId).put("minutes", 60)
                .put("analysisRevision", 0).put("projected", true).put("epochDay", 0).toString()).commit()
    }
    private fun history(): AiCoachHistory {
        save(snapshot("owned")); save(snapshot("private", reps = 99))
        val store = AiCoachStore(app); store.saveProfile("a", AiCoachProfile(goal = "strength")); store.saveProfile("b", AiCoachProfile())
        store.linkSessions("a", listOf("owned")); store.linkSessions("b", listOf("private"))
        return AiCoachHistoryReader(app).read("a")
    }
    private fun query() = JSONObject().put("routine_id", "routine").put("exercise_id", JSONObject.NULL)
        .put("from_ms", 0).put("to_ms", JSONObject.NULL).put("limit", 10).put("offset", 0)
    private fun reject(action: () -> Unit) { try { action(); fail("Unsafe input accepted") } catch (_: IllegalArgumentException) {} }
    private fun call(id: String, name: String = "read_profile", namespace: String = "coach_records", arguments: String = "{}") =
        JSONObject().put("type", "function_call").put("name", name).put("namespace", namespace).put("arguments", arguments).put("call_id", id)

    @Test fun scopedReadsCanFindOlderRecordsAndNeverExposeOtherAccountsOrArbitraryFiles() {
        val h = history()
        for (i in 1..110) { save(snapshot("old-$i", i.toLong())); AiCoachStore(app).linkSessions("a", listOf("old-$i")) }
        val reads = AiCoachReadTools(app, AiCoachHistoryReader(app).read("a"))
        val page = reads.execute("read_workouts", query().put("offset", 100))
        assertEquals(111, page.getInt("total_matching")); assertEquals(10, page.getJSONArray("records").length())
        assertFalse(page.toString().contains("private"))
        assertEquals("strength", reads.execute("read_profile", JSONObject()).getJSONObject("profile").getString("goal"))
        reject { reads.execute("read_workouts", query().put("limit", 100)) }
        reject { reads.execute("read_workouts", query().put("path", "/private")) }
        try { reads.execute("read_sensor_summary", JSONObject().put("session_id", "private").put("set_id", JSONObject.NULL)); fail() } catch (_: IllegalStateException) {}
        reject { reads.execute("read_sensor_summary", JSONObject().put("session_id", "owned").put("set_id", "unknown")) }
        assertEquals(8, h.records.single().evidence.snapshot.plannedSets.single().performedReps)
    }
    @Test fun completedToolOnlySseCanContinueWithoutInventingAnAssistantAnswer() {
        val raw = JSONObject().put("type", "response.completed").put("response", JSONObject().put("id", "r")
            .put("model", "fixture").put("status", "completed").put("output", JSONArray().put(call("c"))))
        val result = ChatGptResponseStream.read(StringReader("event: response.completed\ndata: $raw\n\n"))
        assertEquals("", result.text); assertEquals("c", JSONArray(result.outputJson).getJSONObject(0).getString("call_id"))
    }
    @Test fun statelessReadLoopPreservesReasoningAndToolResultsAndDisablesFurtherTools() {
        val reads = AiCoachReadTools(app, history()); val requests = mutableListOf<JSONObject>()
        val http = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation, onDelta: (String) -> Unit): ChatGptResponseResult {
                requests.add(JSONObject(body.toString()))
                return when (requests.size) {
                    1 -> ChatGptResponseResult("r1", "fixture", "", JSONArray().put(JSONObject().put("type", "reasoning").put("id", "reason")
                        .put("summary", JSONArray()).put("encrypted_content", "fixture-encrypted")).put(call("c1")).toString())
                    2 -> ChatGptResponseResult("r2", "fixture", "", JSONArray().put(call("c2", "read_workouts", arguments = query().toString())).toString())
                    else -> ChatGptResponseResult("r3", "fixture", "final")
                }
            }
        }
        val body = JSONObject().put("model", "fixture").put("store", false).put("stream", true)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", "question")))
        assertEquals("final", AiCoachToolHttp(http, reads).execute("fixture-token", body, ChatGptWorkCancellation()) {}.text)
        assertEquals(3, requests.size); assertEquals("none", requests.last().getString("tool_choice"))
        assertTrue(requests.last().getJSONArray("input").toString().contains("fixture-encrypted"))
        assertTrue(requests.last().getJSONArray("input").toString().contains("function_call_output"))
        assertTrue("session:owned" in reads.evidenceIds)
        requests.forEach { assertFalse(it.has("previous_response_id")); assertFalse(it.has("max_tool_calls")); assertFalse(it.getBoolean("store")) }
    }
    @Test fun unknownToolNamespaceCannotBeExecuted() {
        val reads = AiCoachReadTools(app, history())
        val http = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation, onDelta: (String) -> Unit) =
                ChatGptResponseResult("r", "fixture", "", JSONArray().put(call("c", namespace = "filesystem")).toString())
        }
        reject { AiCoachToolHttp(http, reads).execute("fixture-token", JSONObject().put("input", JSONArray()), ChatGptWorkCancellation()) {} }
        assertEquals(0, reads.outputs.length())
    }
    @Test fun modelInterpretationsRetainTheirSourcesAndDisappearOnManualCorrectionOrProfileEdit() {
        val h = history(); val store = AiCoachInterpretationStore(app)
        val proposals = JSONArray().put(JSONObject().put("text", "같은 중량을 유지하는 방향을 검토해요")
            .put("citations", JSONArray(listOf("session:owned"))))
        val response = ChatGptResponseResult("r", "fixture", "answer")
        store.save("a", h, proposals, response)
        assertEquals("MODEL_INTERPRETATION", store.current("a", h).getJSONObject(0).getString("kind"))
        save(snapshot("new", 2000)); AiCoachStore(app).linkSessions("a", listOf("new"))
        assertTrue(store.current("a", AiCoachHistoryReader(app).read("a")).getJSONObject(0).getBoolean("scope_changed"))
        save(snapshot("owned", reps = 10)); assertEquals(0, store.current("a", AiCoachHistoryReader(app).read("a")).length())
        store.save("a", AiCoachHistoryReader(app).read("a"), proposals, response)
        val p = AiCoachStore(app).profile("a")!!; AiCoachStore(app).saveProfile("a", p.copy(goal = "updated"))
        assertEquals(0, store.current("a", AiCoachHistoryReader(app).read("a")).length())
        assertEquals("updated", AiCoachStore(app).profile("a")!!.goal)
    }
    @Test fun missingAndStaleSourcesCannotBecomePersonalFacts() {
        val h = history(); val memory = AiCoachInterpretationStore(app)
        reject { memory.save("a", h, JSONArray().put(JSONObject().put("text", "fabrication").put("citations", JSONArray(listOf("session:private")))), ChatGptResponseResult("r", "f", "a")) }
        assertEquals(0, memory.current("a", h).length())
    }
    @Test fun automaticFinishClaimsAreAccountScopedAndNeverReplayAfterRecreation() {
        val settings = AiTaskSettings(app)
        assertFalse(settings.read("a").automaticReview)
        settings.save("a", AiTaskPreferences(automaticReview = true))
        assertTrue(settings.claimReviewEvent("a", "s"))
        assertFalse(AiTaskSettings(app).claimReviewEvent("a", "s"))
        assertFalse(settings.read("b").automaticReview); assertTrue(settings.claimReviewEvent("b", "s"))
    }
    @Test fun onlyFreshHeartSamplesAreStoredAndGapsAreNotInterpolated() {
        val store = WorkoutVitalsStore(app)
        store.receive("1|s|1000000000|100|0|3|WORKING", 1000)
        store.receive("1|s|3000000000|120|0|3|WORKING", 3000)
        store.receive("1|s|23000000000|150|0|3|RESTING", 23000)
        store.receive("1|s|23000000000|150|0|3|RESTING", 24000)
        reject { store.receive("1|s|25000000000|160|12000|3|WORKING", 25000) }
        reject { store.receive("1|s|25000000000|160|0|0|WORKING", 25000) }
        val read = store.summary("s", 1000, 23000)
        assertEquals(3, read.points.size); assertEquals(100.0, read.average!!, 0.0); assertEquals(150, read.maximum); assertTrue(read.hasGaps)
    }
    @Test fun energyRequiresExplicitAdultBasisAndKnownNonFutureBodyMass() {
        val day = LocalDate.of(2026, 10, 14)
        val start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val record = FinishedWorkoutStore.Evidence(snapshot("energy", start), "routine", 60, 0)
        val store = WorkoutEnergyStore(app)
        assertTrue(store.estimate(record, null).isNull("active_kcal"))
        val p = AiCoachProfile(ageYears = 25, weightKg = 80.0, weightMeasuredOn = "2026-10-14", calorieBasis = "RESISTANCE_MULTIPLE_8_15")
        val value = store.estimate(record, p)
        assertEquals(210.0, value.getDouble("active_kcal"), 0.0001)
        assertTrue(store.estimate(record, p.copy(weightMeasuredOn = "2026-10-15")).isNull("active_kcal"))
        assertTrue(store.estimate(record, p.copy(ageYears = 15)).isNull("active_kcal"))
        assertTrue(store.estimate(record, p.copy(calorieBasis = "")).isNull("active_kcal"))
        assertTrue(store.estimate(record, p.copy(weightMeasuredOn = "2026-01-01")).isNull("active_kcal"))
    }
    @Test fun captureStatusUsesCaptureIdsRatherThanTheNumberOfCompletedSets() {
        val s = snapshot("capture")
        assertEquals(0, WorkoutCaptureStatus(app).read(s).getInt("known_capture_count"))
        val status = WorkoutCaptureStatus(app).read(s.copy(captureStates = mapOf("one" to CaptureState.PENDING_UPLOAD)))
        assertEquals(1, status.getInt("pending")); assertEquals(0, status.getInt("received"))
        assertTrue(status.getString("message").contains("0/1"))
    }
    @Test fun committedPhaseTimelineSurvivesRecreationAndDuplicateBoundaries() {
        val s = snapshot("time", 1000); val store = WorkoutTimeStore(app)
        store.record(s, 1000); store.record(s, 1500)
        store.record(s.copy(phase = WorkoutPhase.RESTING), 3000)
        WorkoutTimeStore(app).record(s.copy(phase = WorkoutPhase.PAUSED, paused = true), 5000)
        store.close("time", 6000); store.close("time", 9000)
        val read = store.read("time")!!
        assertEquals(2000, read.getLong("working_ms")); assertEquals(2000, read.getLong("resting_ms")); assertEquals(1000, read.getLong("paused_ms"))
        assertFalse(read.getBoolean("partial"))
    }
}
