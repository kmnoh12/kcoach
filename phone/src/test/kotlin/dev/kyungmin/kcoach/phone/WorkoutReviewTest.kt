package dev.kyungmin.kcoach.phone

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutReviewTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private fun snapshot(id: String, time: Long, reps: Int, otherExercise: Boolean = false): WorkoutSnapshot {
        val set = PlannedSetSnapshot(0, "press", 0, 8, 40.0, true, "NORMAL", "프레스", performedReps = reps,
            repSource = SetRepSource.MANUAL, equipment = "barbell", family = "flat", gripType = "overhand")
        return WorkoutSnapshot(sessionId = id, routineId = "r", exerciseIndex = 0, setIndex = 0,
            plannedSets = if (otherExercise) listOf(set, set.copy(exerciseId = "curl", exerciseName = "컬", exerciseIndex = 1)) else listOf(set),
            performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0, pausedRestRemainingMs = 0,
            paused = false, startedAtMs = time, pausedSinceMs = 0, totalPausedMs = 0, lastSequence = 0,
            lastCompletedSetKey = null, captureStates = emptyMap())
    }
    private fun fixture(account: String = "fixture-a", otherExercise: Boolean = false): WorkoutReviewContext {
        val store = AiCoachStore(app); store.saveProfile(account, AiCoachProfile(goal = "근비대"))
        FinishedWorkoutStore(app).finish(snapshot("before", 1, 8, otherExercise), "가슴", 30)
        FinishedWorkoutStore(app).finish(snapshot("after", 2, 9, otherExercise), "가슴", 30)
        store.linkSessions(account, listOf("before", "after"))
        return WorkoutReviewContextBuilder(app).build(account, "after")
    }

    private fun answer(context: WorkoutReviewContext): JSONObject {
        val evidence = JSONArray(listOf("session:after"))
        return JSONObject().put("summary", "같은 조건의 반복이 늘었어요")
            .put("overview", "일반 세트의 변화를 비교했어요")
            .put("findings", JSONArray().put(JSONObject().put("text", "수행 기록을 기준으로 비교했어요").put("evidence_ids", evidence)))
            .put("exercise_analyses", JSONArray(context.exerciseNames.keys.map { id -> JSONObject().put("exercise_id", id)
                .put("status", "partial").put("headline", "일반 세트 반복 합계는 9회예요")
                .put("metric_refs", JSONArray(listOf("metric:exercise:$id")))
                .put("observations", JSONArray().put(JSONObject().put("kind", "observed").put("text", "일반 세트 반복 합계는 9회예요")
                    .put("metric_refs", JSONArray(listOf("metric:exercise:$id"))).put("evidence_ids", evidence)))
                .put("highlighted_set_ids", JSONArray()).put("limitations", JSONArray(listOf("카메라 자세 관측은 없어요"))) }))
            .put("next_targets", JSONArray().put(JSONObject().put("exercise_id", "press").put("target", "다음에는 40kg으로 10회를 시도해 보세요")
                .put("reason", "입력한 목표와 수행 기록을 바탕으로 한 제안이에요").put("evidence_ids", evidence)))
            .put("coverage", JSONObject().put("evidence_ids", evidence).put("limitations", JSONArray(listOf("센서 분석이 아직 없어요"))))
            .put("limitations", JSONArray()).put("memory_proposals", JSONArray())
    }
    private fun rejected(context: WorkoutReviewContext, edit: (JSONObject) -> Unit) {
        val output = answer(context); edit(output)
        try { WorkoutReviewAnswer.parse(output.toString(), context); fail("Invalid review accepted") } catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test fun reviewInputTargetsTheOwnedSessionAndIncludesARealRoutineBaselineAndStableSetIds() {
        val context = fixture(); val json = JSONObject(context.json)
        assertEquals("after", json.getJSONObject("session").getString("session_id"))
        assertEquals("before", json.getJSONObject("baseline_session").getString("session_id"))
        assertEquals(40.0, json.getJSONObject("comparison").getJSONArray("exercises").getJSONObject(0).getDouble("volume_change_kg_reps"), 0.0)
        assertEquals(mapOf("legacy-0" to "press"), context.sets)
        assertTrue(context.evidenceIds.contains("set:after:legacy-0"))
        assertTrue(json.isNull("optional_camera_metrics"))
        assertEquals(context.fingerprint, WorkoutReviewContextBuilder(app).build("fixture-a", "after").fingerprint)
        AiCoachStore(app).saveProfile("other", AiCoachProfile())
        try { WorkoutReviewContextBuilder(app).build("other", "after"); fail("Unowned session") } catch (_: IllegalStateException) { }
    }

    @Test fun validDetailedReviewAndProposedTargetsPassWhileInventedFactsAndIdsFail() {
        val context = fixture()
        assertEquals(answer(context).toString(), WorkoutReviewAnswer.parse(answer(context).toString(), context).json)
        rejected(context) { it.put("summary", "일반 세트는 9999회예요") }
        rejected(context) { it.getJSONArray("findings").getJSONObject(0).put("evidence_ids", JSONArray(listOf("session:invented"))) }
        rejected(context) { it.getJSONArray("exercise_analyses").getJSONObject(0).put("metric_refs", JSONArray(listOf("metric:invented"))) }
        rejected(context) { it.getJSONArray("exercise_analyses").getJSONObject(0).put("highlighted_set_ids", JSONArray(listOf("made-up"))) }
        rejected(context) { it.getJSONArray("exercise_analyses").getJSONObject(0).put("exercise_id", "unknown") }
        rejected(context) { it.put("exercise_analyses", JSONArray()) }
        rejected(context) { it.put("memory_proposals", JSONArray(listOf("assumed personal fact"))) }
    }

    @Test fun observationsCannotBorrowAnotherExercisesMetricsOrSetEvidence() {
        val context = fixture(otherExercise = true)
        rejected(context) { it.getJSONArray("exercise_analyses").getJSONObject(0).put("metric_refs", JSONArray(listOf("metric:exercise:curl"))) }
        rejected(context) { it.getJSONArray("exercise_analyses").getJSONObject(0).getJSONArray("observations").getJSONObject(0)
            .put("evidence_ids", JSONArray(listOf("set:after:legacy-1"))) }
    }

    @Test fun completedReviewsKeepExecutionMetadataAndDuplicateOrCancelledRequestsCannotOverwriteThem() {
        val context = fixture(); val store = WorkoutReviewStore(app)
        val options = JSONObject().put("model", "chosen").put("reasoning", JSONObject().put("effort", "high"))
        val key = store.key("fixture-a", context, options, "prompt-hash")
        val pending = store.begin(key, "fixture-a", context, options, "prompt-hash")
        try { store.begin(key, "fixture-a", context, options, "prompt-hash"); fail("Duplicate request") } catch (_: IllegalArgumentException) { }
        val saved = store.complete(key, pending.getString("request_id"), context, ChatGptResponseResult("completed", "actual", answer(context).toString()))
        assertEquals("COMPLETED", saved.getString("status")); assertEquals("chosen", saved.getString("requested_model")); assertEquals("actual", saved.getString("actual_model"))
        assertEquals("high", saved.getString("effort")); assertEquals(context.fingerprint, saved.getString("input_fingerprint"))
        assertEquals(saved.toString(), store.begin(key, "fixture-a", context, options, "prompt-hash").toString())
        assertEquals(saved.toString(), WorkoutReviewStore(app).lookup(key).toString())
        assertNotEquals(key, store.key("other-account", context, options, "prompt-hash"))
        assertNotEquals(key, store.key("fixture-a", context, options, "new-prompt"))
        val cancelledKey = store.key("fixture-a", context, options.put("model", "other-model"), "prompt-hash")
        val cancelled = store.begin(cancelledKey, "fixture-a", context, options, "prompt-hash")
        store.end(cancelledKey, cancelled.getString("request_id"), "CANCELLED", "cancelled")
        try { store.complete(cancelledKey, cancelled.getString("request_id"), context, ChatGptResponseResult("late", "actual", answer(context).toString())); fail("Late answer") }
        catch (_: IllegalArgumentException) { }
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val until = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); if (predicate()) return; Thread.sleep(10) }
        fail(label)
    }
    private fun auth(): ChatGptAuth {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = ChatGptAccountStore(app) { key }
        val account = ChatGptAccount("fixture-a", "subject-a", "fixture@example.invalid", "", "fixture-access", "fixture-refresh", "fixture-id",
            System.currentTimeMillis() + 3_600_000, setOf(CHATGPT_PLAN_SCOPE), true, "fixture-model", "high")
        store.write(ChatGptSavedAccounts(accounts = mutableListOf(account), activeClientId = "fixture-a"))
        val http = object : ChatGptHttp {
            override fun getJson(url: String): JSONObject = error("Unexpected discovery")
            override fun postForm(url: String, body: Map<String, String>): JSONObject = error("Unexpected refresh")
            override fun getModels(accessToken: String) = JSONObject().put("models", JSONArray().put(JSONObject().put("slug", "fixture-model")
                .put("display_name", "Fixture model").put("visibility", "list").put("supported_reasoning_levels", JSONArray().put(JSONObject().put("effort", "high")))))
        }
        return ChatGptAuth(app, store, ChatGptOAuth(http)).also {
            await("Account did not load") { !it.state.value.loading }; it.loadModels()
            await("Models did not load") { it.modelSettings.value.loaded && !it.state.value.busy }
        }
    }

    @Test fun explicitReviewUsesChosenOptionsSavesOnlyTheCompletedAnswerAndReusesAnIdenticalRequest() {
        val context = fixture(); val auth = auth(); val calls = AtomicInteger()
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation, onDelta: (String) -> Unit): ChatGptResponseResult {
                calls.incrementAndGet(); assertEquals("fixture-model", body.getString("model")); assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
                assertFalse(body.getBoolean("store")); assertTrue(body.getBoolean("stream"))
                assertTrue(body.getString("instructions").contains("workout_review"))
                assertEquals("after", JSONObject(body.getJSONArray("input").getJSONObject(0).getString("content")).getJSONObject("session").getString("session_id"))
                onDelta("provisional answer that cannot be saved")
                return ChatGptResponseResult("complete-id", "actual-model", answer(context).toString())
            }
        }
        val controller = WorkoutReviewController(app, auth, transport)
        controller.bind("fixture-a", "after"); await("Evidence did not load") { controller.state.value.loaded }
        assertEquals(0, calls.get())
        controller.start(); controller.start()
        await("Review did not complete") { !controller.state.value.busy && controller.state.value.answerJson != null }
        assertEquals(1, calls.get()); assertEquals("actual-model", controller.state.value.model)
        controller.start(); await("Cache did not load") { !controller.state.value.busy }
        assertEquals(1, calls.get())
        val recovered = WorkoutReviewController(app, auth, transport)
        recovered.bind("fixture-a", "after"); await("Saved answer did not restore") { recovered.state.value.loaded }
        assertNotNull(recovered.state.value.answerJson); assertEquals(1, calls.get())
    }

    @Test fun cancellationRejectsLateRepliesWithoutChangingWorkoutRecords() {
        val context = fixture(); val auth = auth(); val started = CountDownLatch(1); val release = CountDownLatch(1)
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation, onDelta: (String) -> Unit): ChatGptResponseResult {
                started.countDown(); check(release.await(5, TimeUnit.SECONDS)); return ChatGptResponseResult("late", "actual", answer(context).toString())
            }
        }
        val controller = WorkoutReviewController(app, auth, transport)
        controller.bind("fixture-a", "after"); await("Evidence did not load") { controller.state.value.loaded }
        controller.start(); await("Inference did not start") { started.count == 0L }
        controller.cancel(); release.countDown(); await("Auth worker did not finish") { !auth.state.value.busy }
        assertNull(controller.state.value.answerJson)
        val row = WorkoutReviewStore(app).latest("fixture-a", context)!!
        assertEquals("CANCELLED", row.getString("status")); assertFalse(row.has("answer"))
        assertEquals(9, FinishedWorkoutStore(app).recentEvidence().first().snapshot.plannedSets.single().performedReps)
    }

    @Test fun interruptedRequestRestoresAsInterruptedWithoutAnAutomaticModelRetry() {
        val context = fixture(); val auth = auth(); val calls = AtomicInteger(); val store = WorkoutReviewStore(app)
        val options = auth.selectedWorkRequestOptions()
        val key = store.key("fixture-a", context, options, "lost-process")
        store.begin(key, "fixture-a", context, options, "lost-process")
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation, onDelta: (String) -> Unit): ChatGptResponseResult {
                calls.incrementAndGet(); error("No automatic retry")
            }
        }
        val controller = WorkoutReviewController(app, auth, transport)
        controller.bind("fixture-a", "after"); await("Recovery did not load") { controller.state.value.loaded }
        assertEquals(0, calls.get()); assertEquals("INTERRUPTED", store.lookup(key)!!.getString("status"))
        assertNull(controller.state.value.answerJson)
    }

    @Test fun recordEditsDuringInferenceRejectTheOldAnswerAndMakeAnOlderSavedReviewStale() {
        val context = fixture(); val auth = auth(); val started = CountDownLatch(1); val release = CountDownLatch(1)
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation, onDelta: (String) -> Unit): ChatGptResponseResult {
                started.countDown(); check(release.await(5, TimeUnit.SECONDS)); return ChatGptResponseResult("stale", "actual", answer(context).toString())
            }
        }
        val controller = WorkoutReviewController(app, auth, transport)
        controller.bind("fixture-a", "after"); await("Evidence did not load") { controller.state.value.loaded }
        controller.start(); await("Inference did not start") { started.count == 0L }
        val prefs = app.getSharedPreferences("finished-workouts", Context.MODE_PRIVATE)
        val row = JSONObject(prefs.getString("session:after", null)!!)
        row.put("snapshot", WorkoutSnapshotCodec.encode(snapshot("after", 2, 10)))
        prefs.edit().putString("session:after", row.toString()).commit()
        release.countDown(); await("Stale response did not finish") { !controller.state.value.busy }
        assertNull(controller.state.value.answerJson); assertTrue(controller.state.value.message.contains("표시하지 않아요"))
        val failed = WorkoutReviewStore(app).latest("fixture-a", context)!!
        assertEquals("FAILED", failed.getString("status")); assertFalse(failed.has("answer"))
        controller.bind("fixture-a", "after"); await("Fresh context did not load") { controller.state.value.loaded }
        assertTrue(controller.state.value.stale); assertNull(controller.state.value.answerJson)
    }
}
