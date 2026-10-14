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
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiCalibrationTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private fun input(): AiCalibrationInput {
        val key = PatternCompatibility(PatternCandidateStore(app).localUserId(), MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch", "watch-native-si-v1")
        val records = (0..450).map { i ->
            val time = 1_000_000_000L + i * 20_000_000L; val phase = (i % 150) / 150.0
            SensorCaptureRecord(SensorSample(time, (4 * (1 - cos(2 * PI * phase))).toFloat(), 0f, 9.8f,
                (1.2 * sin(2 * PI * phase)).toFloat(), 0f, 0f), accelerationTimestampNs = time,
                gyroscopeTimestampNs = time, receivedElapsedNs = time + 1_000_000)
        }
        val raw = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val refs = (0..2).map { i -> RepReference("training", "set", "attempt",
            RepCycle(1_000_000_000L + i * 3_000_000_000L, 2_500_000_000L + i * 3_000_000_000L,
                4_000_000_000L + i * 3_000_000_000L, MusclePhase.CONCENTRIC), ReferenceSource.REVIEWED_VIDEO,
            5_000_000, true, true, 100, revision = 1) }
        val set = ReviewedPatternSet("training", SensorCaptureFormat.sha256(raw), "dumbbell_curl", key, "2026-10-14", SensorCaptureDocument(2, 50.0, records), refs)
        val profile = ReviewedPatternBuilder.build(listOf(set), "base", 1).getOrThrow()
        val candidate = PatternCandidateStore(app).saveCandidate(profile).getOrThrow()
        val hash = "a".repeat(64); val request = "request-fixture"
        return AiCalibrationInput(candidate, listOf(set), mapOf(set.captureId to 3), JSONObject().put("request", JSONObject()
            .put("request_id", request).put("input_hash", hash)).toString(), hash, request, setOf("candidate:base", "rep:training:0:1"),
            PatternCalibrationReplayer.replay(profile, listOf(set), mapOf(set.captureId to 3)))
    }
    private fun answer(input: AiCalibrationInput, propose: Boolean): JSONObject = JSONObject().put("request_id", input.requestId).put("input_hash", input.hash)
        .put("decision", if (propose) "propose" else "keep_current").put("summary", "확인한 학습 원본을 비교했어요")
        .put("findings", JSONArray().put(JSONObject().put("text", "이 비교는 독립 검증이 아니에요").put("evidence_ids", JSONArray(listOf("candidate:base")))))
        .put("candidate_proposals", if (!propose) JSONArray() else JSONArray().put(JSONObject().put("base_candidate_id", "base")
            .put("engine_id", "reviewed-six-axis-dtw-v1").put("reason", "일치 기준의 후보를 비교해 보세요").put("evidence_ids", JSONArray(listOf("rep:training:0:1")))
            .put("parameter_changes", JSONArray().put(JSONObject().put("key", "maximum_distance").put("value", .3).put("unit", "normalized_dtw")))))
        .put("review_suggestions", JSONArray()).put("limitations", JSONArray(listOf("실제 워치 적용과 정확도 검증은 아직이에요")))
    private fun rejected(input: AiCalibrationInput, edit: (JSONObject) -> Unit) {
        val row = answer(input, true); edit(row)
        try { AiCalibrationAnswer.parse(row.toString(), input); fail("Invalid proposal accepted") } catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
    @Test fun modelMustEchoTheSnapshotAndCanOnlyProposeKnownParametersWithOwnedEvidence() {
        val input = input(); assertEquals(1, AiCalibrationAnswer.parse(answer(input, true).toString(), input).proposals.size)
        rejected(input) { it.put("input_hash", "b".repeat(64)) }
        rejected(input) { it.put("request_id", "other") }
        rejected(input) { it.put("decision", "keep_current") }
        rejected(input) { it.getJSONArray("candidate_proposals").getJSONObject(0).put("base_candidate_id", "other") }
        rejected(input) { it.getJSONArray("candidate_proposals").getJSONObject(0).put("evidence_ids", JSONArray(listOf("held-out:secret"))) }
        rejected(input) { it.getJSONArray("candidate_proposals").getJSONObject(0).getJSONArray("parameter_changes").getJSONObject(0).put("key", "executable_code") }
        rejected(input) { it.getJSONArray("candidate_proposals").getJSONObject(0).getJSONArray("parameter_changes").getJSONObject(0).put("unit", "percent") }
        rejected(input) { it.getJSONArray("candidate_proposals").getJSONObject(0).getJSONArray("parameter_changes").getJSONObject(0).put("value", "0.3") }
        try { AiCalibrationAnswer.parse(answer(input, true).toString(), input.copy(counts = mapOf("training" to null))); fail("Unreviewed total") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun proposalOutcomesAreDurableButCannotChangeAnAppliedWatchProfile() {
        val input = input(); val options = JSONObject().put("model", "chosen").put("reasoning", JSONObject().put("effort", "high"))
        val store = AiCalibrationStore(app); val key = store.key("account", input, options, "prompt")
        val pending = store.begin(key, "account", input, options, "prompt")
        val parsed = AiCalibrationAnswer.parse(answer(input, true).toString(), input)
        val profile = PatternCalibrationParameters.apply(input.candidate.profile, parsed.proposals.single().changes, "new", 2)
        val candidate = PatternCandidateStore(app).saveCandidate(profile).getOrThrow()
        val replay = PatternCalibrationReplayer.replay(profile, input.sets, input.counts)
        val result = store.complete(key, pending.getString("run_id"), parsed, ChatGptResponseResult("complete", "actual", parsed.json),
            listOf(candidate to replayJson(replay)), JSONObject().put("total", 1))
        assertEquals("COMPLETED", AiCalibrationStore(app).get(key)!!.getString("status"))
        assertEquals("INDEPENDENT_VALIDATION_PENDING", result.getJSONArray("candidates").getJSONObject(0).getString("adoption_status"))
        assertTrue(PatternAdoptionController(app).activate(candidate.profile.id).isFailure)
        assertNull(PatternAdoptionController(app).state(candidate.profile.id).getOrThrow().observedActive)
        assertNull(PatternAdoptionController(app).state(candidate.profile.id).getOrThrow().transition)
        assertEquals(input.candidate.profile, PatternCandidateStore(app).load("base").getOrThrow().profile)
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < until) { shadowOf(Looper.getMainLooper()).idle(); if (predicate()) return; Thread.sleep(10) }
        fail(label)
    }
    private fun auth(): ChatGptAuth {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(); val store = ChatGptAccountStore(app) { key }
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
    @Test fun aKeepCurrentResponseIsRequestedOnceAndThenReusedForTheSameInputAndModel() {
        val input = input(); val auth = auth(); val calls = AtomicInteger()
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation, onDelta: (String) -> Unit): ChatGptResponseResult {
                calls.incrementAndGet(); assertEquals("fixture-model", body.getString("model")); assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
                assertFalse(body.getBoolean("store")); assertTrue(body.getBoolean("stream")); assertTrue(body.getString("instructions").contains("measurement_calibration"))
                onDelta("미완성 제안"); return ChatGptResponseResult("complete", "actual", answer(input, false).toString())
            }
        }
        val controller = AiCalibrationController(app, auth, transport, inputLoader = { _, check -> check(); input }, variantSaver = { _, _, _ -> error("No variants") })
        controller.bind("fixture-a", "base"); await("Input did not load") { controller.state.value.loaded }
        assertEquals(0, calls.get()); controller.start(); controller.start()
        await("Calibration did not finish") { !controller.state.value.busy && controller.state.value.resultJson != null }
        assertEquals(1, calls.get()); controller.start(); await("Cached result did not load") { !controller.state.value.busy }
        assertEquals(1, calls.get())
    }
    @Test fun aValidProposalIsReplayedBeforeTheExistingCandidateStoreIsUsedAndNoActivationIsRequested() {
        val input = input(); val auth = auth(); val saves = AtomicInteger()
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation, onDelta: (String) -> Unit) =
                ChatGptResponseResult("complete", "actual", answer(input, true).toString())
        }
        val controller = AiCalibrationController(app, auth, transport, inputLoader = { _, check -> check(); input }, variantSaver = { material, proposal, id ->
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            saves.incrementAndGet(); PatternCandidateStore(app).saveCandidate(PatternCalibrationParameters.apply(material.candidate.profile, proposal.changes, id, 2)).getOrThrow()
        })
        controller.bind("fixture-a", "base"); await("Input did not load") { controller.state.value.loaded }
        controller.start(); await("Proposal did not finish") { !controller.state.value.busy && controller.state.value.resultJson != null }
        assertEquals(1, saves.get())
        val result = JSONObject(controller.state.value.resultJson!!)
        val id = result.getJSONArray("candidates").getJSONObject(0).getString("id")
        assertEquals("CAUSAL_PHONE_REPLAY_ON_TRAINING_ONLY", result.getJSONArray("candidates").getJSONObject(0).getJSONObject("training_replay").getString("scope"))
        assertNull(PatternAdoptionController(app).state(id).getOrThrow().transition)
        assertTrue(result.getJSONObject("timings_ms").getDouble("total") >= 0)
    }
    @Test fun cancellationBlocksLateProposalsBeforeAnyVariantIsSaved() {
        val input = input(); val auth = auth(); val started = CountDownLatch(1); val release = CountDownLatch(1); val saves = AtomicInteger()
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation, onDelta: (String) -> Unit): ChatGptResponseResult {
                started.countDown(); check(release.await(5, TimeUnit.SECONDS)); return ChatGptResponseResult("late", "actual", answer(input, true).toString())
            }
        }
        val controller = AiCalibrationController(app, auth, transport, inputLoader = { _, check -> check(); input }, variantSaver = { _, _, _ -> saves.incrementAndGet(); error("Must not save") })
        controller.bind("fixture-a", "base"); await("Input did not load") { controller.state.value.loaded }
        controller.start(); await("Model did not start") { started.count == 0L }; controller.cancel(); release.countDown()
        await("Auth did not finish") { !auth.state.value.busy }
        assertEquals(0, saves.get()); assertNull(controller.state.value.resultJson)
        assertEquals("CANCELLED", AiCalibrationStore(app).latest("fixture-a", "base")!!.getString("status"))
    }
    @Test fun changedInputAndTheForegroundDeadlineRejectRepliesWithoutSavingCandidates() {
        val input = input(); val auth = auth(); val saves = AtomicInteger(); var changed = false
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation, onDelta: (String) -> Unit): ChatGptResponseResult {
                changed = true; return ChatGptResponseResult("stale", "actual", answer(input, true).toString())
            }
        }
        val controller = AiCalibrationController(app, auth, transport, inputLoader = { _, check -> check(); if (changed) input.copy(hash = "b".repeat(64)) else input },
            variantSaver = { _, _, _ -> saves.incrementAndGet(); error("Must not save") })
        controller.bind("fixture-a", "base"); await("Input did not load") { controller.state.value.loaded }; controller.start()
        await("Changed input did not fail") { !controller.state.value.busy }
        assertEquals(0, saves.get()); assertNull(controller.state.value.resultJson)
        assertEquals("FAILED", AiCalibrationStore(app).latest("fixture-a", "base")!!.getString("status"))
    }
    @Test fun foregroundTimeoutCancelsTheRequestAndDoesNotRetryOrSaveALateResponse() {
        val input = input(); val auth = auth(); val started = CountDownLatch(1); val release = CountDownLatch(1); val saves = AtomicInteger(); val calls = AtomicInteger()
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation, onDelta: (String) -> Unit): ChatGptResponseResult {
                calls.incrementAndGet(); started.countDown(); check(release.await(5, TimeUnit.SECONDS)); return ChatGptResponseResult("late", "actual", answer(input, true).toString())
            }
        }
        val controller = AiCalibrationController(app, auth, transport, inputLoader = { _, check -> check(); input }, variantSaver = { _, _, _ -> saves.incrementAndGet(); error("Must not save") })
        controller.bind("fixture-a", "base"); await("Input did not load") { controller.state.value.loaded }; controller.start()
        await("Model did not start") { started.count == 0L }
        shadowOf(Looper.getMainLooper()).idleFor(46, TimeUnit.SECONDS)
        release.countDown(); await("Auth did not finish") { !auth.state.value.busy }
        assertEquals(1, calls.get()); assertEquals(0, saves.get()); assertNull(controller.state.value.resultJson)
        assertEquals("timeout", AiCalibrationStore(app).latest("fixture-a", "base")!!.getString("error_code"))
    }
}
