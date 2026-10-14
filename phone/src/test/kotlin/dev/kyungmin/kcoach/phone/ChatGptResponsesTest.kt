package dev.kyungmin.kcoach.phone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatGptResponsesTest {
    private fun complete(text: String): JSONObject {
        val content = JSONArray().put(JSONObject().put("type", "output_text").put("text", text))
        val message = JSONObject().put("type", "message").put("role", "assistant").put("content", content)
        val response = JSONObject().put("id", "response-test").put("status", "completed")
            .put("model", "fixture-model").put("output", JSONArray().put(message))
        return JSONObject().put("type", "response.completed").put("response", response)
    }
    private fun sse(vararg events: JSONObject) = events.joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" }

    @Test fun completionUsesTheFinalCanonicalTextRatherThanAnUnfinishedPreview() {
        val preview = mutableListOf<String>()
        val stream = ": keep-alive\n\n" + sse(JSONObject().put("type", "response.output_text.delta").put("delta", "미완성"), complete("최종 답변"))
        val result = ChatGptResponseStream.read(StringReader(stream), { preview += it })
        assertEquals(listOf("미완성"), preview)
        assertEquals("최종 답변", result.text)
        assertEquals("response-test", result.responseId)
    }
    @Test fun doneInterruptedIncompleteAndMidstreamUsageLimitNeverBecomeSuccess() {
        val error = JSONObject().put("code", "subscription_sharing_usage_limit_exceeded")
            .put("message", "sensitive upstream text")
        val failed = JSONObject().put("type", "response.failed").put("response", JSONObject().put("error", error))
        for (stream in listOf("data: [DONE]\n\n", sse(JSONObject().put("type", "response.output_text.delta").put("delta", "부분")),
            sse(JSONObject().put("type", "response.incomplete")), sse(failed))) {
            try { ChatGptResponseStream.read(StringReader(stream)); fail("Expected incomplete or failed response") }
            catch (e: ChatGptWorkError) { assertFalse(e.message.orEmpty().contains("sensitive")) }
        }
    }
    @Test fun requestUsesOnlyEligibleFieldsAndCannotInjectATopLevelSystemMessage() {
        val options = JSONObject().put("model", "fixture-model").put("store", false).put("stream", true)
            .put("reasoning", JSONObject().put("effort", "fixture-effort"))
        val request = ChatGptResponseRequest.build(options, "고정 앱 지침", listOf(ChatGptWorkMessage("user", "수치 맥락")))
        assertFalse(request.getBoolean("store")); assertTrue(request.getBoolean("stream"))
        assertEquals("fixture-effort", request.getJSONObject("reasoning").getString("effort"))
        assertEquals("user", request.getJSONArray("input").getJSONObject(0).getString("role"))
        for (key in listOf("temperature", "max_output_tokens", "metadata", "previous_response_id", "background")) assertFalse(request.has(key))
        try { ChatGptWorkMessage("system", "잘못된 역할"); fail("Unsupported system input") } catch (_: IllegalArgumentException) {}
        try { ChatGptResponseRequest.build(options.put("max_output_tokens", 10), "지침", listOf(ChatGptWorkMessage("user", "질문"))); fail("Unsupported options") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun oversizedOrContradictoryEventEnvelopesAreRefused() {
        val wrongType = "event: response.failed\ndata: ${complete("답변")}\n\n"
        for (value in listOf(wrongType, "data: " + "x".repeat(70_000) + "\n\n")) {
            try { ChatGptResponseStream.read(StringReader(value)); fail("Invalid stream") } catch (_: ChatGptWorkError) {}
        }
    }
}
