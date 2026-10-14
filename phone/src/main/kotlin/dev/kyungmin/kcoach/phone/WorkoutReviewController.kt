package dev.kyungmin.kcoach.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.util.concurrent.Executors

internal data class WorkoutReviewUi(val accountId: String? = null, val sessionId: String = "", val loaded: Boolean = false,
    val busy: Boolean = false, val context: WorkoutReviewContext? = null, val answerJson: String? = null,
    val model: String = "", val message: String = "", val stale: Boolean = false)

/** Activity recreation keeps the app-scoped call. A process restart never reissues an unknown request. */
internal class WorkoutReviewController(context: Context, private val auth: ChatGptAuth = ChatGptAuth.get(context.applicationContext),
    private val transport: ChatGptWorkHttp = ChatGptResponsesHttps()) {
    private val app = context.applicationContext
    private val store = WorkoutReviewStore(app)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "kcoach-workout-review").apply { isDaemon = true } }
    val state = mutableStateOf(WorkoutReviewUi())
    private val automaticQueue = java.util.ArrayDeque<Pair<String, String>>()
    private var automaticSession: String? = null
    private var ticket = 0L
    private var cancellation: ChatGptWorkCancellation? = null
    private var request: JSONObject? = null
    private val taskPreferences = app.getSharedPreferences("ai-task-settings", Context.MODE_PRIVATE)
    private var settingsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private fun stopWatchingSettings() {
        settingsListener?.let(taskPreferences::unregisterOnSharedPreferenceChangeListener)
        settingsListener = null
    }
    private fun enabled(accountId: String, automatic: Boolean): Boolean = AiTaskSettings(app).read(accountId).let {
        it.reviewEnabled && (!automatic || it.automaticReview)
    }
    private fun watchSettings(accountId: String, generation: Long, automatic: Boolean) {
        stopWatchingSettings()
        settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf("$accountId:review-enabled", "$accountId:review") && !enabled(accountId, automatic)) handler.post {
                if (ticket == generation) {
                    cancel()
                    state.value = state.value.copy(message = "운동 리뷰를 꺼서 요청을 취소했어요")
                }
            }
        }.also(taskPreferences::registerOnSharedPreferenceChangeListener)
    }
    private fun instructions(): String = app.assets.open("ai-coach/coach-skill.md").bufferedReader().use { it.readText() } + "\n\n" +
        app.assets.open("ai-coach/workout-review.prompt.md").bufferedReader().use { it.readText() }

    fun automatic(accountId: String, sessionId: String) {
        if (automaticQueue.none { it.second == sessionId } && automaticSession != sessionId) automaticQueue.add(accountId to sessionId)
        drainAutomatic()
    }
    private fun drainAutomatic() {
        if (state.value.busy || automaticSession != null || automaticQueue.isEmpty()) return
        val (account, session) = automaticQueue.removeFirst()
        if (auth.state.value.activeProfileId != account || !AiTaskSettings(app).read(account).automaticReview) { drainAutomatic(); return }
        bind(account, session)
        automaticSession = session
    }

    fun bind(accountId: String?, sessionId: String) {
        if (state.value.accountId == accountId && state.value.sessionId == sessionId && state.value.busy) return
        if (state.value.accountId != accountId || state.value.sessionId != sessionId) cancel()
        val generation = ++ticket
        state.value = WorkoutReviewUi(accountId, sessionId, message = "리뷰에 필요한 기록을 읽는 중…")
        if (accountId == null) {
            state.value = state.value.copy(loaded = true, message = "설정에서 ChatGPT를 연결하고 운동 프로필에 이 기록을 연결해 주세요")
            return
        }
        worker.execute {
            val prepared = runCatching {
                val context = WorkoutReviewContextBuilder(app).build(accountId, sessionId)
                var row = store.latest(accountId, context)
                if (row?.getString("status") == "REQUESTING") row = store.end(row.getString("key"), row.getString("request_id"), "INTERRUPTED", "process_interrupted")
                val stale = row != null && (row.getString("input_fingerprint") != context.fingerprint || row.optString("prompt_hash") != aiCoachHash(instructions()))
                val complete = row?.takeIf { !stale && it.getString("status") == "COMPLETED" }
                complete?.let { WorkoutReviewAnswer.parse(it.getJSONObject("answer").toString(), context) }
                WorkoutReviewUi(accountId, sessionId, loaded = true, context = context,
                    answerJson = complete?.getJSONObject("answer")?.toString(), model = complete?.optString("actual_model").orEmpty(), stale = stale,
                    message = when { stale -> "기록이나 분석이 바뀌었어요. 새 리뷰를 요청해 주세요"
                        complete != null -> "저장된 리뷰예요"
                        row?.optString("status") == "INTERRUPTED" -> "이전 요청의 완료를 확인하지 못했어요. 자동으로 다시 요청하지 않아요"
                        else -> "운동 기록은 저장됐어요. 리뷰를 요청하면 선택한 모델로 분석해요" })
            }
            handler.post {
                if (ticket != generation) return@post
                state.value = prepared.getOrElse { state.value.copy(loaded = true, message = "운동 프로필에 이 기록을 연결한 뒤 다시 열어 주세요") }
                if (automaticSession == sessionId) {
                    automaticSession = null
                    if (state.value.context != null && state.value.answerJson == null) start(automatic = true) else drainAutomatic()
                }
            }
        }
    }

    fun start(automatic: Boolean = false) {
        if (state.value.busy || !state.value.loaded) return
        val accountId = state.value.accountId ?: return
        if (!enabled(accountId, automatic)) { state.value = state.value.copy(message = "설정에서 운동 리뷰를 켜 주세요"); drainAutomatic(); return }
        val sessionId = state.value.sessionId
        val options = try { auth.selectedWorkRequestOptions() } catch (_: Exception) {
            state.value = state.value.copy(message = "설정에서 계정 연결·한도 사용·모델 선택을 확인해 주세요"); return
        }
        val generation = ++ticket
        state.value = state.value.copy(busy = true, answerJson = null, message = "리뷰 근거를 확인하는 중…")
        watchSettings(accountId, generation, automatic)
        worker.execute {
            val prepared = runCatching {
                val context = WorkoutReviewContextBuilder(app).build(accountId, sessionId)
                val instructions = instructions()
                val promptHash = aiCoachHash(instructions)
                Triple(context, instructions, store.key(accountId, context, options, promptHash))
            }
            handler.post {
                if (ticket != generation) return@post
                if (!enabled(accountId, automatic)) { cancel(); return@post }
                prepared.onFailure { failure(generation, "리뷰 근거를 준비하지 못했어요. 프로필과 연결 기록을 확인해 주세요", "context_failed") }
                    .onSuccess { (context, instructions, key) ->
                        try {
                            val saved = store.lookup(key)
                            if (saved?.optString("status") == "COMPLETED") {
                                WorkoutReviewAnswer.parse(saved.getJSONObject("answer").toString(), context)
                                stopWatchingSettings()
                                state.value = state.value.copy(
                                    busy = false, context = context, answerJson = saved.getJSONObject("answer").toString(),
                                    model = saved.getString("actual_model"), message = "같은 기록·모델의 저장된 리뷰를 불러왔어요", stale = false,
                                )
                                handler.post { drainAutomatic() }
                                return@onSuccess
                            }
                            request = store.begin(key, accountId, context, options, aiCoachHash(instructions))
                            state.value = state.value.copy(context = context, stale = false, message = "AI 코치가 운동 리뷰를 정리하고 있어요. 화면을 나가도 요청은 계속돼요")
                            cancellation = auth.performWork(instructions, listOf(ChatGptWorkMessage("user", context.json)),
                                transport = transport, expectedAccountId = accountId, expectedOptions = options, onResult = { result ->
                                    if (ticket != generation) return@performWork
                                    result.onFailure { error -> failure(generation, (error as? ChatGptWorkError)?.userMessage ?: "리뷰를 완료하지 못했어요", (error as? ChatGptWorkError)?.code ?: "request_failed") }
                                        .onSuccess { response ->
                                            state.value = state.value.copy(message = "리뷰 근거와 기록 변경을 확인하는 중…")
                                            worker.execute {
                                                val completed = runCatching {
                                                    val latest = WorkoutReviewContextBuilder(app).build(accountId, sessionId)
                                                    require(latest.fingerprint == context.fingerprint) { "Evidence changed" }
                                                    WorkoutReviewAnswer.parse(response.text, context)
                                                }
                                                handler.post {
                                                    if (ticket != generation) return@post
                                                    completed.onFailure { failure(generation, "기록이 바뀌었거나 리뷰 근거를 확인하지 못했어요. 응답은 표시하지 않아요", "invalid_or_stale_review") }
                                                        .onSuccess {
                                                            try {
                                                                check(enabled(accountId, automatic) && auth.state.value.connected && auth.state.value.activeProfileId == accountId)
                                                                cancellation?.check()
                                                                val row = requireNotNull(request)
                                                                store.complete(row.getString("key"), row.getString("request_id"), context, response)
                                                                cancellation = null; request = null
                                                                stopWatchingSettings()
                                                                state.value = state.value.copy(busy = false, answerJson = response.text, model = response.model, message = "리뷰를 저장했어요", stale = false)
                                                                drainAutomatic()
                                                            } catch (_: Exception) { failure(generation, "리뷰를 저장하지 못했어요. 운동 기록은 유지돼요", "save_failed") }
                                                        }
                                                }
                                            }
                                        }
                                })
                        } catch (error: Exception) { failure(generation, (error as? ChatGptWorkError)?.userMessage ?: "리뷰 요청을 시작하지 못했어요", (error as? ChatGptWorkError)?.code ?: "start_failed") }
                    }
            }
        }
    }

    private fun failure(generation: Long, message: String, code: String) {
        if (ticket != generation) return
        stopWatchingSettings()
        request?.let { row -> runCatching { store.end(row.getString("key"), row.getString("request_id"), "FAILED", code) } }
        cancellation = null; request = null
        state.value = state.value.copy(busy = false, answerJson = null, message = message)
        drainAutomatic()
    }
    fun cancel() {
        stopWatchingSettings()
        automaticSession = null
        ++ticket; cancellation?.cancel(); cancellation = null
        request?.let { row -> runCatching { store.end(row.getString("key"), row.getString("request_id"), "CANCELLED", "cancelled") } }
        request = null
        state.value = state.value.copy(busy = false, answerJson = null, message = "리뷰 요청을 취소했어요. 운동 기록은 저장돼 있어요")
    }
    companion object {
        @Volatile private var instance: WorkoutReviewController? = null
        fun get(context: Context): WorkoutReviewController = instance ?: synchronized(this) {
            instance ?: WorkoutReviewController(context).also { instance = it }
        }
    }
}
