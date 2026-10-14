package dev.kyungmin.kcoach.phone

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
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
class ChatGptWorkTest {
    private fun fixture(planAllowed: Boolean = true): ChatGptAuth {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "chatgpt-accounts.enc")
        file.delete(); File(file.path + ".bak").delete()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = ChatGptAccountStore(context) { key }
        val account = ChatGptAccount("fixture-client", "fixture-subject", "fixture@example.invalid", "",
            "fixture-access", "fixture-refresh", "fixture-id", System.currentTimeMillis() + 3_600_000,
            if (planAllowed) setOf(CHATGPT_PLAN_SCOPE) else emptySet(), true, "fixture-model", "high")
        store.write(ChatGptSavedAccounts(accounts = mutableListOf(account), activeClientId = account.clientId))
        val http = object : ChatGptHttp {
            override fun getJson(url: String): JSONObject = error("Unexpected metadata request")
            override fun postForm(url: String, body: Map<String, String>): JSONObject = error("Unexpected token request")
            override fun getModels(accessToken: String): JSONObject = JSONObject().put("models", JSONArray().put(
                JSONObject().put("slug", "fixture-model").put("display_name", "Fixture model").put("visibility", "list")
                    .put("supported_reasoning_levels", JSONArray().put(JSONObject().put("effort", "high")))))
        }
        return ChatGptAuth(context, store, ChatGptOAuth(http)).also { auth ->
            await { !auth.state.value.loading }
            if (planAllowed) { auth.loadModels(); await { auth.modelSettings.value.loaded } }
        }
    }

    private fun await(predicate: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        fail("Work callback did not complete")
    }

    @Test fun settingsDoNotRunInferenceAndExplicitWorkUsesTheSelectedModelExactlyOnce() {
        val auth = fixture()
        val calls = AtomicInteger()
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation,
                onDelta: (String) -> Unit): ChatGptResponseResult {
                calls.incrementAndGet()
                assertEquals("fixture-model", body.getString("model"))
                assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
                assertFalse(body.getBoolean("store")); assertTrue(body.getBoolean("stream"))
                assertEquals("앱 지침", body.getString("instructions"))
                assertEquals("검증한 수치", body.getJSONArray("input").getJSONObject(0).getString("content"))
                onDelta("미완성")
                return ChatGptResponseResult("fixture-response", "fixture-model", "완료 답변")
            }
        }
        auth.chooseEffort("high"); await { !auth.state.value.busy }
        assertEquals(0, calls.get())
        var result: Result<ChatGptResponseResult>? = null
        val previews = mutableListOf<String>()
        auth.performWork("앱 지침", listOf(ChatGptWorkMessage("user", "검증한 수치")),
            onDelta = { previews += it }, onResult = { result = it }, transport = transport)
        await { result != null }
        assertEquals(1, calls.get())
        assertEquals(listOf("미완성"), previews)
        assertEquals("완료 답변", result!!.getOrThrow().text)
        assertFalse(auth.state.value.busy)
    }

    @Test fun identityOnlyCannotStartWork() {
        val auth = fixture(planAllowed = false)
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation,
                onDelta: (String) -> Unit): ChatGptResponseResult = error("No consent must never reach transport")
        }
        try {
            auth.performWork("지침", listOf(ChatGptWorkMessage("user", "질문")), onResult = {}, transport = transport)
            fail("Expected permission gate")
        } catch (_: ChatGptAuthError) { }
    }

    @Test fun frozenContextCannotBeSentAfterAccountOrModelSelectionChanges() {
        val auth = fixture()
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation,
                onDelta: (String) -> Unit): ChatGptResponseResult = error("Stale context must not reach transport")
        }
        for ((owner, model) in listOf("another-account" to "fixture-model", "fixture-client" to "another-model")) {
            try {
                auth.performWork("지침", listOf(ChatGptWorkMessage("user", "계정별 맥락")), onResult = {}, transport = transport,
                    expectedAccountId = owner, expectedOptions = JSONObject().put("model", model)
                        .put("reasoning", JSONObject().put("effort", "high")))
                fail("Stale context")
            } catch (e: ChatGptWorkError) { assertTrue(e.code in setOf("account_changed", "settings_changed")) }
        }
    }

    @Test fun cancellingAfterAQueuedPreviewSuppressesItAndCannotReturnSuccess() {
        val auth = fixture()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val previews = mutableListOf<String>()
        var result: Result<ChatGptResponseResult>? = null
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation,
                onDelta: (String) -> Unit): ChatGptResponseResult {
                onDelta("취소할 부분 답변")
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                cancellation.check()
                return ChatGptResponseResult("fixture-response", "fixture-model", "답변")
            }
        }
        val cancellation = auth.performWork("지침", listOf(ChatGptWorkMessage("user", "질문")),
            onDelta = { previews += it }, onResult = { result = it }, transport = transport)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        cancellation.cancel(); release.countDown()
        await { result != null }
        assertTrue(previews.isEmpty())
        assertEquals("cancelled", (result!!.exceptionOrNull() as ChatGptWorkError).code)
        assertFalse(auth.state.value.busy)
    }

    @Test fun aFailedStreamIsNotRetriedAndUnexpectedErrorsAreNotExposed() {
        val auth = fixture()
        val calls = AtomicInteger()
        var result: Result<ChatGptResponseResult>? = null
        val transport = object : ChatGptWorkHttp {
            override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation,
                onDelta: (String) -> Unit): ChatGptResponseResult {
                calls.incrementAndGet()
                throw IllegalStateException("sensitive fixture upstream detail")
            }
        }
        auth.performWork("지침", listOf(ChatGptWorkMessage("user", "질문")), onResult = { result = it }, transport = transport)
        await { result != null }
        assertEquals(1, calls.get())
        assertEquals("work_failed", (result!!.exceptionOrNull() as ChatGptWorkError).code)
        assertFalse(result!!.exceptionOrNull()!!.message.orEmpty().contains("sensitive"))
        assertFalse(auth.state.value.busy)
    }
}
