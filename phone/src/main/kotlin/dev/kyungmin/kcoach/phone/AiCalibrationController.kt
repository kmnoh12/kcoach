package dev.kyungmin.kcoach.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import dev.kyungmin.kcoach.core.*
import org.json.JSONObject
import java.util.concurrent.Executors

internal data class AiCalibrationUi(val accountId: String? = null, val candidateId: String = "", val loaded: Boolean = false,
    val busy: Boolean = false, val input: AiCalibrationInput? = null, val resultJson: String? = null,
    val message: String = "", val stage: String = "IDLE")

/** One bounded model call; all replay and adoption still use the original measurement engine/store. */
internal class AiCalibrationController(
    context: Context, private val auth: ChatGptAuth = ChatGptAuth.get(context.applicationContext),
    private val transport: ChatGptWorkHttp? = null,
    private val inputLoader: (String, () -> Unit) -> AiCalibrationInput = { id, check -> AiCalibrationInputBuilder(context.applicationContext).build(id, check) },
    private val variantSaver: (AiCalibrationInput, AiCalibrationProposal, String) -> PatternCandidate = { input, proposal, id ->
        PatternCandidateStore(context.applicationContext).saveCalibrationVariant(input.candidate.profile.id, input.candidate.sha256, proposal.changes, id).getOrThrow()
    },
) {
    private val app = context.applicationContext
    private val store = AiCalibrationStore(app)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "kcoach-ai-calibration").apply { isDaemon = true } }
    @Volatile private var ticket = 0L
    private var cancellation: ChatGptWorkCancellation? = null
    private var pending: JSONObject? = null
    val state = mutableStateOf(AiCalibrationUi())
    private fun instructions(): String = app.assets.open("ai-coach/measurement-calibration.prompt.md").bufferedReader().use { it.readText() }
    private fun checkpoint(generation: Long, deadline: Long) {
        if (ticket != generation) throw ChatGptWorkError("보정 요청을 취소했어요", "cancelled")
        if (System.nanoTime() >= deadline) throw ChatGptWorkError("보정 도움 요청 시간이 초과됐어요. 원본과 기존 설정은 유지돼요", "timeout")
    }
    private fun cached(input: AiCalibrationInput, row: JSONObject, prompt: String): Boolean {
        if (row.optString("status") != "COMPLETED" || row.optString("input_hash") != input.hash || row.optString("prompt_hash") != aiCoachHash(prompt)) return false
        AiCalibrationAnswer.parse(row.getJSONObject("answer").toString(), input)
        val stored = row.getJSONArray("candidates")
        for (i in 0 until stored.length()) {
            val candidate = PatternCandidateStore(app).load(stored.getJSONObject(i).getString("id")).getOrThrow()
            require(candidate.sha256 == stored.getJSONObject(i).getString("hash"))
            PatternCandidateStore(app).checkCurrentSources(candidate.profile)
        }
        return true
    }

    fun bind(accountId: String?, candidateId: String) {
        if (state.value.accountId == accountId && state.value.candidateId == candidateId && state.value.busy) return
        if (state.value.accountId != accountId || state.value.candidateId != candidateId) cancel()
        val generation = ++ticket
        state.value = AiCalibrationUi(accountId, candidateId, message = "확인한 학습 원본을 읽는 중…", stage = "READING")
        if (accountId == null) { state.value = state.value.copy(loaded = true, message = "설정에서 ChatGPT 계정과 작업 모델을 연결해 주세요", stage = "IDLE"); return }
        worker.execute {
            val loaded = runCatching {
                val deadline = System.nanoTime() + 45_000_000_000L
                val input = inputLoader(candidateId) { checkpoint(generation, deadline) }
                val prompt = instructions()
                val previous = store.latest(accountId, candidateId)
                if (previous?.optString("status") == "REQUESTING") store.end(previous.getString("key"), previous.getString("run_id"), "INTERRUPTED", "process_interrupted")
                val ready = previous?.takeIf { cached(input, it, prompt) }
                AiCalibrationUi(accountId, candidateId, loaded = true, input = input, resultJson = ready?.toString(),
                    message = when { ready != null -> "저장된 보정 도움 결과예요 · 실제 적용은 별도 검증 후 진행해요"
                        previous?.optString("status") == "REQUESTING" -> "이전 요청의 완료를 확인하지 못했어요. 자동으로 다시 호출하지 않아요"
                        else -> "원본과 기존 후보는 유지돼요. GPT 보정 도움은 요청할 때만 실행해요" })
            }
            handler.post { if (ticket == generation) state.value = loaded.getOrElse { state.value.copy(loaded = true, stage = "IDLE", message =
                (it as? ChatGptWorkError)?.userMessage ?: "확인한 원본·정렬·횟수를 준비하지 못했어요. 기존 자료를 검토해 주세요") } }
        }
    }

    fun start() {
        if (state.value.busy || !state.value.loaded || state.value.input == null) return
        val accountId = state.value.accountId ?: return
        val id = state.value.candidateId
        val validationStarted = System.nanoTime()
        val options = try { auth.selectedWorkRequestOptions() } catch (_: Exception) {
            state.value = state.value.copy(message = "설정에서 계정·한도 사용·모델 선택을 확인해 주세요"); return
        }
        val validationMs = (System.nanoTime() - validationStarted) / 1e6
        val started = System.nanoTime(); val deadline = started + 45_000_000_000L
        val generation = ++ticket
        state.value = state.value.copy(busy = true, resultJson = null, message = "보정 입력과 원본 변경을 확인하는 중…", stage = "PREPARING")
        handler.postDelayed({ if (ticket == generation && state.value.busy) fail(generation,
            ChatGptWorkError("보정 도움 요청 시간이 초과됐어요. 원본과 기존 설정은 유지돼요", "timeout")) }, 45_000)
        worker.execute {
            val prepared = runCatching {
                val input = inputLoader(id) { checkpoint(generation, deadline) }
                val prompt = instructions()
                Triple(input, prompt, store.key(accountId, input, options, aiCoachHash(prompt)))
            }
            val inputReady = System.nanoTime()
            handler.post {
                if (ticket != generation) return@post
                prepared.onFailure { fail(generation, it) }.onSuccess { (input, prompt, key) ->
                    try {
                        checkpoint(generation, deadline)
                        val previous = store.get(key)
                        if (previous != null && cached(input, previous, prompt)) {
                            state.value = state.value.copy(busy = false, input = input, resultJson = previous.toString(), stage = "COMPLETED",
                                message = "같은 입력·모델의 결과를 불러왔어요. 새 모델 호출은 하지 않았어요")
                            return@onSuccess
                        }
                        pending = store.begin(key, accountId, input, options, aiCoachHash(prompt))
                        val row = requireNotNull(pending)
                        val modelStarted = System.nanoTime()
                        state.value = state.value.copy(input = input, message = "GPT가 보정 후보를 검토하고 있어요 · 최대 45초 요청", stage = "MODEL")
                        cancellation = auth.performWork(prompt, listOf(ChatGptWorkMessage("user", input.json)), expectedAccountId = accountId,
                            expectedOptions = options, transport = transport ?: ChatGptResponsesHttps(((deadline - System.nanoTime()) / 1_000_000).coerceIn(1, 45_000)),
                            onResult = { result ->
                                if (ticket != generation) return@performWork
                                result.onFailure { fail(generation, it) }.onSuccess { response ->
                                    val modelFinished = System.nanoTime()
                                    state.value = state.value.copy(message = "제안 범위를 검사하고 원본으로 비교하는 중…", stage = "REPLAY")
                                    worker.execute {
                                        val compared = runCatching {
                                            val check = { checkpoint(generation, deadline) }
                                            check()
                                            val answer = AiCalibrationAnswer.parse(response.text, input)
                                            val current = inputLoader(id, check)
                                            require(current.hash == input.hash && current.candidate.sha256 == input.candidate.sha256) { "Calibration evidence changed" }
                                            val variants = answer.proposals.mapIndexed { index, proposal ->
                                                check()
                                                val variantId = "gpt-${key.take(40)}-$index"
                                                val profile = PatternCalibrationParameters.apply(input.candidate.profile, proposal.changes, variantId,
                                                    Math.addExact(input.candidate.profile.version, 1))
                                                val replay = PatternCalibrationReplayer.replay(profile, current.sets, current.counts, check)
                                                Triple(proposal, profile, replay)
                                            }
                                            check()
                                            answer to variants
                                        }
                                        val replayFinished = System.nanoTime()
                                        val completed = compared.mapCatching { (answer, variants) ->
                                                    checkpoint(generation, deadline)
                                                    val saved = variants.map { (proposal, expected, replay) ->
                                                        val candidate = variantSaver(input, proposal, expected.id)
                                                        require(candidate.profile.copy(version = expected.version) == expected)
                                                        checkpoint(generation, deadline)
                                                        candidate to replayJson(replay)
                                                    }
                                                    val timing = JSONObject().put("account_validation", validationMs)
                                                        .put("input_assembly", (inputReady - started) / 1e6).put("model_response", (modelFinished - modelStarted) / 1e6)
                                                        .put("local_replay", (replayFinished - modelFinished) / 1e6)
                                                        .put("save", (System.nanoTime() - replayFinished) / 1e6).put("total", (System.nanoTime() - started) / 1e6)
                                                    val complete = store.complete(key, row.getString("run_id"), answer, response, saved, timing)
                                                    complete
                                        }
                                        handler.post finish@ {
                                            if (ticket != generation) return@finish
                                            completed.onFailure { fail(generation, it) }.onSuccess { complete ->
                                                    cancellation = null; pending = null
                                                    state.value = state.value.copy(busy = false, resultJson = complete.toString(), stage = "COMPLETED",
                                                        message = if (complete.getJSONArray("candidates").length() == 0) "검토 결과를 저장했어요. 기존 설정은 유지돼요" else "보정 후보를 저장했어요 · 독립 검증 대기 · 워치 설정은 유지돼요")
                                            }
                                        }
                                    }
                                }
                            })
                    } catch (error: Exception) { fail(generation, error) }
                }
            }
        }
    }

    private fun fail(generation: Long, error: Throwable) {
        if (ticket != generation) return
        ++ticket; cancellation?.cancel(); cancellation = null
        pending?.let { row -> runCatching { store.end(row.getString("key"), row.getString("run_id"), "FAILED", (error as? ChatGptWorkError)?.code ?: "invalid_or_stale_proposal") } }
        pending = null
        state.value = state.value.copy(busy = false, resultJson = null, stage = "FAILED", message =
            (error as? ChatGptWorkError)?.userMessage ?: "제안의 근거·허용 범위 또는 원본을 확인하지 못했어요. 기존 설정은 유지돼요")
    }
    fun cancel() {
        ++ticket; cancellation?.cancel(); cancellation = null
        pending?.let { row -> runCatching { store.end(row.getString("key"), row.getString("run_id"), "CANCELLED", "cancelled") } }
        pending = null
        state.value = state.value.copy(busy = false, resultJson = null, stage = "CANCELLED", message = "보정 도움 요청을 취소했어요. 기존 원본과 설정은 유지돼요")
    }
    companion object {
        @Volatile private var instance: AiCalibrationController? = null
        fun get(context: Context): AiCalibrationController = instance ?: synchronized(this) {
            instance ?: AiCalibrationController(context).also { instance = it }
        }
    }
}
