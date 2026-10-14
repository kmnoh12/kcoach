package dev.kyungmin.kcoach.phone

import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class ChatGptWorkMessage(val role: String, val text: String) {
    init { require(role in setOf("user", "assistant", "developer") && text.isNotBlank() && text.length <= 60_000) }
}
data class ChatGptResponseResult(val responseId: String, val model: String, val text: String)
class ChatGptWorkError(val userMessage: String, val code: String = "request_failed", val httpStatus: Int? = null) : Exception(userMessage)

internal object ChatGptResponseRequest {
    fun build(options: JSONObject, instructions: String, messages: List<ChatGptWorkMessage>): JSONObject {
        require(options.keys().asSequence().all { it in setOf("model", "reasoning", "store", "stream") })
        require(options.getString("model").isNotBlank() && !options.getBoolean("store") && options.getBoolean("stream"))
        require(instructions.isNotBlank() && instructions.length <= 30_000 && messages.size in 1..100 &&
            messages.sumOf { it.text.length } <= 180_000)
        return JSONObject(options.toString()).put("instructions", instructions)
            .put("input", JSONArray(messages.map { JSONObject().put("role", it.role).put("content", it.text) }))
    }
}

/** Preview is provisional; only the terminal response's complete assistant output is returned. */
internal object ChatGptResponseStream {
    fun read(reader: Reader, onDelta: (String) -> Unit = {}, checkpoint: () -> Unit = {}): ChatGptResponseResult {
        val data = StringBuilder()
        var eventName = ""
        var total = 0
        var previewLength = 0
        fun event(): ChatGptResponseResult? {
            if (data.isEmpty()) { eventName = ""; return null }
            val raw = data.toString().trimEnd('\n'); data.setLength(0)
            if (raw == "[DONE]") throw ChatGptWorkError("응답이 완료되기 전에 연결이 끝났어요", "stream_interrupted")
            val json = try { JSONObject(raw) } catch (_: Exception) { throw ChatGptWorkError("모델 응답 형식을 확인할 수 없어요", "invalid_stream") }
            val type = json.optString("type")
            if (eventName.isNotEmpty() && eventName != type) throw ChatGptWorkError("모델 응답 순서를 확인할 수 없어요", "invalid_stream")
            eventName = ""
            when (type) {
                "response.output_text.delta" -> {
                    val delta = json.opt("delta") as? String ?: throw ChatGptWorkError("모델 응답 형식을 확인할 수 없어요", "invalid_stream")
                    previewLength += delta.length
                    if (previewLength > 120_000) throw ChatGptWorkError("응답이 너무 길어요. 질문 범위를 줄여 주세요", "output_limit")
                    onDelta(delta)
                }
                "response.failed", "error" -> {
                    val code = json.optJSONObject("response")?.optJSONObject("error")?.optString("code")
                        ?: json.optJSONObject("error")?.optString("code")
                        ?: json.optString("code")
                    throw when (code) {
                        "subscription_sharing_usage_limit_exceeded" -> ChatGptWorkError("ChatGPT 공유 한도를 다 썼어요. 한도 갱신 후 다시 시도해 주세요", code)
                        "subscription_sharing_usage_unavailable" -> ChatGptWorkError("ChatGPT 한도 사용을 확인할 수 없어요. 계정 연결과 동의를 확인해 주세요", code)
                        else -> ChatGptWorkError("모델 작업을 완료하지 못했어요. 다시 시도해 주세요", "response_failed")
                    }
                }
                "response.incomplete" -> throw ChatGptWorkError("모델 응답이 미완료예요. 다시 시도해 주세요", "response_incomplete")
                "response.completed" -> {
                    val response = json.optJSONObject("response") ?: throw ChatGptWorkError("완료 응답을 확인할 수 없어요", "invalid_completion")
                    val id = response.opt("id") as? String
                    val model = response.opt("model") as? String
                    if (response.opt("status") != "completed" || id.isNullOrBlank() || model.isNullOrBlank())
                        throw ChatGptWorkError("완료 응답을 확인할 수 없어요", "invalid_completion")
                    val output = response.optJSONArray("output") ?: throw ChatGptWorkError("완료 응답이 비어 있어요", "invalid_completion")
                    val parts = mutableListOf<String>()
                    for (i in 0 until output.length()) {
                        val item = output.optJSONObject(i) ?: continue
                        if (item.optString("type") != "message" || item.optString("role") != "assistant") continue
                        val content = item.optJSONArray("content") ?: continue
                        for (j in 0 until content.length()) {
                            val part = content.optJSONObject(j) ?: continue
                            if (part.optString("type") == "refusal") throw ChatGptWorkError("이 요청에는 답변을 제공할 수 없어요", "refused")
                            if (part.optString("type") == "output_text") parts += part.opt("text") as? String
                                ?: throw ChatGptWorkError("완료 답변 형식을 확인할 수 없어요", "invalid_completion")
                        }
                    }
                    val text = parts.joinToString("\n")
                    if (text.isBlank() || text.length > 120_000) throw ChatGptWorkError("완료된 답변을 확인할 수 없어요", "invalid_completion")
                    return ChatGptResponseResult(id, model, text)
                }
            }
            return null
        }
        val line = StringBuilder()
        while (true) {
            checkpoint()
            val char = reader.read()
            if (char < 0) break
            if (++total > 8_000_000 || line.length > 64_000) throw ChatGptWorkError("모델 응답이 너무 커요", "stream_limit")
            if (char != '\n'.code) { if (char != '\r'.code) line.append(char.toChar()); continue }
            val value = line.toString(); line.setLength(0)
            when {
                value.isEmpty() -> event()?.let { return it }
                value.startsWith("data:") -> {
                    data.append(value.removePrefix("data:").removePrefix(" ")).append('\n')
                    if (data.length > 1_000_000) throw ChatGptWorkError("모델 응답이 너무 커요", "stream_limit")
                }
                value.startsWith("event:") -> eventName = value.removePrefix("event:").trim()
            }
        }
        throw ChatGptWorkError("응답이 완료되기 전에 연결이 끊겼어요", "stream_interrupted")
    }
}

class ChatGptWorkCancellation {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { cancelled.set(true); connection?.disconnect() }
    internal fun attach(value: HttpURLConnection) { connection = value; check() }
    internal fun detach() { connection = null }
    internal fun check() { if (cancelled.get()) throw ChatGptWorkError("작업을 취소했어요", "cancelled") }
}

internal interface ChatGptWorkHttp {
    fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation,
        onDelta: (String) -> Unit): ChatGptResponseResult
}

internal class ChatGptResponsesHttps(private val deadlineMs: Long = 600_000L) : ChatGptWorkHttp {
    init { require(deadlineMs in 1..600_000) }
    override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation,
        onDelta: (String) -> Unit): ChatGptResponseResult {
        require(accessToken.isNotBlank())
        val connection = URL("$CHATGPT_RESOURCE/responses").openConnection() as HttpURLConnection
        val started = System.nanoTime()
        val deadlineReached = AtomicBoolean(false)
        val deadline = java.util.Timer("kcoach-ai-deadline", true)
        deadline.schedule(object : java.util.TimerTask() {
            override fun run() { deadlineReached.set(true); connection.disconnect() }
        }, deadlineMs)
        fun check() {
            cancellation.check()
            if (deadlineReached.get() || System.nanoTime() - started > deadlineMs * 1_000_000L) throw ChatGptWorkError("응답 시간이 초과됐어요. 다시 시도해 주세요", "timeout")
        }
        try {
            cancellation.attach(connection)
            connection.connectTimeout = 15_000; connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false; connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            check()
            val status = connection.responseCode
            if (status !in 200..299) throw ChatGptWorkError(when (status) {
                401 -> "ChatGPT 연결이 만료됐어요. 다시 연결해 주세요"
                403 -> "이 계정의 한도 사용 권한을 확인해 주세요"
                429 -> "ChatGPT 요청 한도를 확인한 뒤 다시 시도해 주세요"
                else -> "모델에 연결하지 못했어요. 다시 시도해 주세요"
            }, "http_error", status)
            if (!connection.contentType.orEmpty().substringBefore(';').equals("text/event-stream", true))
                throw ChatGptWorkError("모델의 응답 형식을 확인할 수 없어요", "invalid_stream")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { ChatGptResponseStream.read(it, onDelta, ::check) }
        } catch (e: ChatGptWorkError) { throw e }
        catch (_: Exception) { check(); throw ChatGptWorkError("모델 연결이 중단됐어요. 다시 시도해 주세요", "connection_failed") }
        finally { deadline.cancel(); cancellation.detach(); connection.disconnect() }
    }
}
