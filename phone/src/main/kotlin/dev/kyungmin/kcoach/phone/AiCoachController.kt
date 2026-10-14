package dev.kyungmin.kcoach.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.util.concurrent.Executors

internal data class AiCoachRecord(val sessionId: String, val title: String, val startedAtMs: Long, val linked: Boolean)
internal data class AiCoachUi(val accountId: String? = null, val loaded: Boolean = false, val busy: Boolean = false,
    val profile: AiCoachProfile? = null, val records: List<AiCoachRecord> = emptyList(),
    val turns: List<AiCoachTurn> = emptyList(), val interpretations: List<String> = emptyList(), val pendingQuestion: String = "", val message: String = "")

/** App-scoped work survives Activity recreation; no Activity or browser credential is retained. */
internal class AiCoachController(
    context: Context,
    private val auth: ChatGptAuth = ChatGptAuth.get(context.applicationContext),
    private val transport: ChatGptWorkHttp = ChatGptResponsesHttps(60_000),
) {
    private val app = context.applicationContext
    private val store = AiCoachStore(app)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "kcoach-ai-context").apply { isDaemon = true } }
    private val guard = Any()
    @Volatile private var ticket = 0L
    @Volatile private var accountId: String? = null
    private var cancellation: ChatGptWorkCancellation? = null
    val state = mutableStateOf(AiCoachUi())

    fun bindAccount(id: String?, force: Boolean = false) {
        if (id == accountId && state.value.loaded && !force) return
        if (id == accountId && state.value.busy) return
        synchronized(guard) {
            cancellation?.cancel(); cancellation = null
            ticket++; accountId = id
        }
        state.value = AiCoachUi(accountId = id, loaded = id == null)
        if (id != null) load(id, ticket)
    }

    private fun load(id: String, generation: Long) {
        worker.execute {
            val result = runCatching { readUi(id) }
            handler.post {
                if (current(id, generation)) state.value = result.getOrElse {
                    AiCoachUi(accountId = id, loaded = true, message = "운동 프로필과 대화를 열지 못했어요. 다시 열어 주세요")
                }
            }
        }
    }
    private fun readUi(id: String): AiCoachUi {
        val profile = store.profile(id)
        val recent = FinishedWorkoutStore(app).recentEvidence(100)
        val allowed = store.linkableSessionIds(id, recent.map { it.snapshot.sessionId })
        val linked = store.sessionIds(id)
        return AiCoachUi(id, loaded = true, profile = profile, records = recent.filter { it.snapshot.sessionId in allowed }
            .map { AiCoachRecord(it.snapshot.sessionId, it.title, it.snapshot.startedAtMs, it.snapshot.sessionId in linked) },
            turns = store.turns(id), interpretations = if (profile == null) emptyList() else AiCoachInterpretationStore(app).current(id, AiCoachHistoryReader(app).read(id)).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getString("text") } })
    }
    private fun current(id: String, generation: Long) = accountId == id && ticket == generation

    fun saveProfile(profile: AiCoachProfile) = change("프로필을 저장하지 못했어요. 입력 값과 저장 공간을 확인해 주세요") { id ->
        store.saveProfile(id, profile)
    }
    fun linkRecords(ids: Set<String>) = change("기록을 연결하지 못했어요. 현재 프로필과 선택한 기록을 다시 확인해 주세요") { id ->
        val actual = FinishedWorkoutStore(app).recentEvidence(100).map { it.snapshot.sessionId }.toSet()
        require(ids.all { it in actual })
        store.linkSessions(id, ids)
    }
    private fun change(failure: String, action: (String) -> Unit) {
        val id = accountId ?: return
        if (!state.value.loaded || state.value.busy || auth.state.value.activeProfileId != id || !auth.state.value.connected) return
        val generation = ticket
        state.value = state.value.copy(busy = true, message = "저장 중…")
        worker.execute {
            val result = runCatching {
                synchronized(guard) { check(current(id, generation)); action(id) }
                readUi(id)
            }
            handler.post {
                if (current(id, generation)) state.value = result.getOrElse { state.value.copy(busy = false, message = failure) }
            }
        }
    }

    fun send(question: String) {
        val id = accountId ?: return
        if (!AiTaskSettings(app).read(id).chatEnabled) { state.value = state.value.copy(message = "설정에서 운동 상담을 켜 주세요"); return }
        val trimmed = question.trim()
        if (!state.value.loaded || state.value.busy || state.value.profile == null || trimmed.isEmpty() || trimmed.length > 4_000) return
        val generation = ticket
        val options = try { auth.selectedWorkRequestOptions() } catch (_: Exception) {
            state.value = state.value.copy(message = "설정에서 계정 연결·한도 사용·작업 모델을 확인해 주세요"); return
        }
        if (auth.state.value.activeProfileId != id || !auth.state.value.connected) return
        state.value = state.value.copy(busy = true, pendingQuestion = trimmed, message = "기록을 읽는 중…")
        worker.execute {
            val prepared = runCatching {
                val context = AiCoachContextBuilder(app).build(id, trimmed)
                val instructions = app.assets.open("ai-coach/coach-skill.md").bufferedReader().use { it.readText() } + "\n\n" +
                    app.assets.open("ai-coach/chat-coach.prompt.md").bufferedReader().use { it.readText() }
                val history = store.turns(id).takeLast(6).flatMap { turn -> listOf(
                    ChatGptWorkMessage("user", turn.question), ChatGptWorkMessage("assistant", turn.answer)) }
                val reads = AiCoachReadTools(app, AiCoachHistoryReader(app).read(id))
                Prepared(context, instructions, history + listOf(ChatGptWorkMessage("user", context.json), ChatGptWorkMessage("user", trimmed)), reads)
            }
            handler.post {
                if (!current(id, generation)) return@post
                prepared.onFailure { fail(id, generation, "상담에 필요한 기록을 준비하지 못했어요. 프로필과 연결 기록을 확인해 주세요") }
                    .onSuccess { (context, instructions, messages, reads) ->
                        state.value = state.value.copy(message = "답변을 생성하는 중…")
                        try {
                            cancellation = auth.performWork(instructions, messages, onResult = { result ->
                                if (!current(id, generation)) return@performWork
                                result.onFailure { error -> fail(id, generation, (error as? ChatGptWorkError)?.userMessage ?: "답변을 완료하지 못했어요") }
                                    .onSuccess { response -> runCatching { toolContext(context, reads) }.onSuccess { finalContext -> saveAnswer(id, generation, trimmed, finalContext, options, response) }.onFailure { fail(id, generation, "조회한 근거의 크기를 확인하지 못했어요. 질문 범위를 줄여 주세요") } }
                            }, transport = AiCoachToolHttp(transport, reads), expectedAccountId = id, expectedOptions = options)
                        } catch (error: Exception) {
                            fail(id, generation, (error as? ChatGptWorkError)?.userMessage ?: "계정 연결과 모델 설정을 다시 확인해 주세요")
                        }
                    }
            }
        }
    }

    private data class Prepared(val context: AiCoachContext, val instructions: String, val messages: List<ChatGptWorkMessage>, val reads: AiCoachReadTools)
    private fun toolContext(context: AiCoachContext, reads: AiCoachReadTools): AiCoachContext {
        val root = JSONObject(context.json).put("read_tool_results", reads.outputs)
        val ids = context.evidenceIds + reads.evidenceIds
        root.put("evidence_index", org.json.JSONArray(ids.toList()))
        for (key in listOf("records", "comparisons")) {
            val rows = root.optJSONArray(key) ?: continue
            while (root.toString().length > 60_000 && rows.length() > 0) { rows.remove(rows.length() - 1); root.put("context_is_partial", true) }
        }
        require(root.toString().length <= 60_000)
        return context.copy(json = root.toString(), evidenceIds = ids)
    }
    private fun saveAnswer(id: String, generation: Long, question: String, context: AiCoachContext,
        options: JSONObject, response: ChatGptResponseResult) {
        state.value = state.value.copy(message = "답변의 근거를 확인하는 중…")
        worker.execute {
            val result = runCatching {
                val latest = AiCoachContextBuilder(app).build(id, question)
                val before = JSONObject(context.json).getJSONObject("personal_summary").getString("input_fingerprint")
                require(JSONObject(latest.json).getJSONObject("personal_summary").getString("input_fingerprint") == before) { "Records changed during answer" }
                val answer = AiCoachAnswer.parse(response.text, context)
                val turn = AiCoachTurn(question, answer.text, answer.citations, options.getString("model"), response.model,
                    options.optJSONObject("reasoning")?.optString("effort"), response.responseId, System.currentTimeMillis(), context.json)
                synchronized(guard) {
                    check(current(id, generation)); cancellation?.check()
                    store.appendTurn(id, context.profileId, turn)
                    AiCoachInterpretationStore(app).save(id, AiCoachHistoryReader(app).read(id), answer.memoryProposals, response)
                }
                readUi(id)
            }
            handler.post {
                if (current(id, generation)) {
                    cancellation = null
                    state.value = result.getOrElse { state.value.copy(busy = false, pendingQuestion = "",
                        message = "답변 형식·근거 또는 저장을 확인하지 못했어요. 이 답변은 표시하지 않아요") }
                }
            }
        }
    }
    private fun fail(id: String, generation: Long, message: String) {
        if (current(id, generation)) { cancellation = null; state.value = state.value.copy(busy = false, pendingQuestion = "", message = message) }
    }
    fun cancel() {
        synchronized(guard) {
            ticket++; cancellation?.cancel(); cancellation = null
        }
        state.value = state.value.copy(busy = false, pendingQuestion = "", message = "상담 요청을 취소했어요")
    }

    companion object {
        @Volatile private var instance: AiCoachController? = null
        fun get(context: Context): AiCoachController = instance ?: synchronized(this) {
            instance ?: AiCoachController(context.applicationContext).also { instance = it }
        }
    }
}
