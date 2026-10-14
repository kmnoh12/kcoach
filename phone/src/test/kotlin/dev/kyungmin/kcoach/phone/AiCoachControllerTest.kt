package dev.kyungmin.kcoach.phone

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiCoachControllerTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clearFixtureState() {
        for (name in listOf("chatgpt-accounts.enc", "ai-coach.json")) {
            val file = File(app.noBackupFilesDir, name)
            file.delete(); File(file.path + ".bak").delete()
        }
        app.getSharedPreferences("finished-workouts", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun await(description: String, predicate: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        fail(description)
    }

    private fun auth(): ChatGptAuth {
        fun account(suffix: String) = ChatGptAccount("fixture-client-$suffix", "fixture-subject-$suffix",
            "fixture-$suffix@example.invalid", "", "fixture-access-$suffix", "fixture-refresh-$suffix", "fixture-id-$suffix",
            System.currentTimeMillis() + 3_600_000, setOf(CHATGPT_PLAN_SCOPE), true, "fixture-model", "high")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = ChatGptAccountStore(app) { key }
        store.write(ChatGptSavedAccounts(accounts = mutableListOf(account("a"), account("b")), activeClientId = "fixture-client-a"))
        val http = object : ChatGptHttp {
            override fun getJson(url: String): JSONObject = error("Unexpected discovery request")
            override fun postForm(url: String, body: Map<String, String>): JSONObject = error("Unexpected OAuth request")
            override fun getModels(accessToken: String): JSONObject = JSONObject().put("models", JSONArray().put(
                JSONObject().put("slug", "fixture-model").put("display_name", "Fixture model").put("visibility", "list")
                    .put("supported_reasoning_levels", JSONArray().put(JSONObject().put("effort", "high")))))
        }
        return ChatGptAuth(app, store, ChatGptOAuth(http)).also {
            await("Account fixture did not load") { !it.state.value.loading }
            it.loadModels()
            await("Model fixture did not load") { it.modelSettings.value.loaded && !it.state.value.busy }
        }
    }

    private fun bind(controller: AiCoachController, id: String = "fixture-client-a") {
        controller.bindAccount(id)
        await("Coach profile did not load") { controller.state.value.accountId == id && controller.state.value.loaded }
    }

    private fun finish(id: String, started: Long, reps: Int) {
        val set = PlannedSetSnapshot(0, "press", 0, 8, 40.0, true, "NORMAL", "프레스",
            performedReps = reps, repSource = SetRepSource.MANUAL, equipment = "barbell")
        val snapshot = WorkoutSnapshot(sessionId = id, routineId = "fixture-routine", exerciseIndex = 0, setIndex = 0,
            plannedSets = listOf(set), performedReps = emptyMap(), phase = WorkoutPhase.WORKING, restEndAtMs = 0,
            pausedRestRemainingMs = 0, paused = false, startedAtMs = started, pausedSinceMs = 0, totalPausedMs = 0,
            lastSequence = 0, lastCompletedSetKey = null, captureStates = emptyMap())
        FinishedWorkoutStore(app).finish(snapshot, "프레스 루틴 $id", 30)
    }

    private fun response(text: String) = ChatGptResponseResult("fixture-completed-response", "fixture-returned-model", text)
    private fun answer(text: String, citations: List<String> = emptyList()) =
        JSONObject().put("answer", text).put("citations", JSONArray(citations)).toString()

    @Test fun explicitSendReadsOnlyLinkedEvidenceAndStoresTheValidatedCanonicalAnswer() {
        val auth = auth()
        val store = AiCoachStore(app)
        val profile = store.saveProfile("fixture-client-a", AiCoachProfile(goal = "근비대"))
        finish("linked-before", 1000, 8)
        finish("linked-after", 2000, 9)
        finish("unlinked-private", 3000, 99)
        store.linkSessions("fixture-client-a", setOf("linked-before", "linked-after"))
        val calls = AtomicInteger()
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation,
                onDelta: (String) -> Unit): ChatGptResponseResult {
                calls.incrementAndGet()
                assertEquals("fixture-model", body.getString("model"))
                assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
                assertFalse(body.getBoolean("store")); assertTrue(body.getBoolean("stream"))
                assertTrue(body.getString("instructions").contains("coach_chat v1"))
                val input = body.getJSONArray("input")
                assertEquals("프레스 변화 알려줘", input.getJSONObject(input.length() - 1).getString("content"))
                val context = JSONObject(input.getJSONObject(input.length() - 2).getString("content"))
                assertEquals("근비대", context.getJSONObject("profile").getString("goal"))
                val records = context.getJSONArray("records")
                assertEquals(setOf("linked-before", "linked-after"), (0 until records.length()).map {
                    records.getJSONObject(it).getString("session_id")
                }.toSet())
                assertFalse(context.toString().contains("unlinked-private"))
                val comparison = context.getJSONArray("comparisons").getJSONObject(0).getJSONArray("exercises").getJSONObject(0)
                assertEquals(40.0, comparison.getDouble("volume_change_kg_reps"), 0.0)
                onDelta("보관되면 안 되는 미완성 답변")
                return response(answer("확인한 일반 세트 볼륨은 40kg·회 증가했어.", listOf("session:linked-after")))
            }
        }
        val controller = AiCoachController(app, auth, transport)
        bind(controller)
        assertEquals(0, calls.get())
        controller.send("  프레스 변화 알려줘  ")
        await("Validated answer did not finish") { !controller.state.value.busy && controller.state.value.turns.size == 1 }
        assertEquals(1, calls.get())
        val turn = AiCoachStore(app).turns("fixture-client-a").single()
        assertEquals("프레스 변화 알려줘", turn.question)
        assertEquals("확인한 일반 세트 볼륨은 40kg·회 증가했어.", turn.answer)
        assertEquals(listOf("session:linked-after"), turn.citations)
        assertEquals("fixture-model", turn.requestedModel)
        assertEquals("fixture-returned-model", turn.actualModel)
        assertEquals("high", turn.effort)
        assertEquals(profile.id, JSONObject(turn.contextJson).getJSONObject("profile").getString("id"))
        assertTrue(store.turns("fixture-client-b").isEmpty())
        assertFalse(turn.json().toString().contains("fixture-access"))
    }

    @Test fun malformedAndInventedEvidenceAnswersAreNeverStoredOrDisplayed() {
        val auth = auth()
        val store = AiCoachStore(app)
        store.saveProfile("fixture-client-a", AiCoachProfile())
        val calls = AtomicInteger()
        val invalid = listOf("자유로운 원문 답변", answer("꾸며낸 기록", listOf("session:invented")))
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation,
                onDelta: (String) -> Unit) = response(invalid[calls.getAndIncrement()])
        }
        val controller = AiCoachController(app, auth, transport)
        bind(controller)
        invalid.indices.forEach { index ->
            controller.send("질문 $index")
            await("Invalid answer did not fail") { !controller.state.value.busy && calls.get() == index + 1 }
            assertTrue(controller.state.value.turns.isEmpty())
            assertTrue(store.turns("fixture-client-a").isEmpty())
            assertTrue(controller.state.value.message.contains("표시하지 않아요"))
            assertEquals("", controller.state.value.pendingQuestion)
        }
    }

    @Test fun cancellationAndAccountRebindingCannotSaveOrDisplayALateAnswer() {
        val auth = auth()
        val store = AiCoachStore(app)
        store.saveProfile("fixture-client-a", AiCoachProfile(goal = "A의 목표"))
        store.saveProfile("fixture-client-b", AiCoachProfile(goal = "B의 목표"))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation,
                onDelta: (String) -> Unit): ChatGptResponseResult {
                calls.incrementAndGet(); started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                // A transport that races cancellation must still not produce a stored success.
                return response(answer("A 계정에만 해당하는 늦은 답변"))
            }
        }
        val controller = AiCoachController(app, auth, transport)
        bind(controller)
        controller.send("A의 상담")
        await("Inference fixture did not start") { started.count == 0L }
        controller.cancel()
        bind(controller, "fixture-client-b")
        assertEquals("B의 목표", controller.state.value.profile!!.goal)
        release.countDown()
        await("Cancelled transport did not finish") { !auth.state.value.busy }
        auth.select("fixture-client-b")
        await("Second account did not select") { auth.state.value.activeProfileId == "fixture-client-b" && !auth.state.value.busy }
        assertEquals(1, calls.get())
        assertEquals("fixture-client-b", controller.state.value.accountId)
        assertEquals("B의 목표", controller.state.value.profile!!.goal)
        assertTrue(controller.state.value.turns.isEmpty())
        assertEquals("", controller.state.value.pendingQuestion)
        assertTrue(store.turns("fixture-client-a").isEmpty())
        assertTrue(store.turns("fixture-client-b").isEmpty())
    }

    @Test fun modelProfileAndRecordSettingsAloneNeverInvokeInference() {
        val auth = auth()
        finish("link-me", 1000, 8)
        val calls = AtomicInteger()
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation,
                onDelta: (String) -> Unit): ChatGptResponseResult {
                calls.incrementAndGet(); error("Settings must never start inference")
            }
        }
        val controller = AiCoachController(app, auth, transport)
        bind(controller)
        auth.chooseEffort("high")
        await("Effort did not save") { !auth.state.value.busy }
        controller.saveProfile(AiCoachProfile(goal = "기록 기반 상담"))
        await("Profile did not save") { controller.state.value.profile != null && !controller.state.value.busy }
        controller.linkRecords(setOf("link-me"))
        await("Record did not link") { !controller.state.value.busy && controller.state.value.records.single().linked }
        assertEquals(setOf("link-me"), AiCoachStore(app).sessionIds("fixture-client-a"))
        assertEquals("기록 기반 상담", controller.state.value.profile!!.goal)
        assertEquals(0, calls.get())
        assertTrue(controller.state.value.turns.isEmpty())
    }
}
