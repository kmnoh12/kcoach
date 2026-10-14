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
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChatGptModelSettingsTest {
    private fun model(slug: String, efforts: List<String>?, default: String? = null, visibility: String = "list") =
        JSONObject().put("slug", slug).put("display_name", "Name $slug").put("visibility", visibility).also { item ->
            if (efforts != null) item.put("supported_reasoning_levels", JSONArray().also { levels ->
                efforts.forEach { levels.put(JSONObject().put("effort", it).put("description", "Description $it")) }
            })
            if (default != null) item.put("default_reasoning_level", default)
        }

    private fun catalog(vararg models: JSONObject) = JSONObject().put("models", JSONArray(models.toList()))
    private fun expectedFailure(action: () -> Unit) {
        try { action(); fail("Invalid configuration must not be accepted") } catch (_: Exception) { }
    }

    @Test fun allVisibleModelsAndFutureEffortsAreKeptInServerOrderWithoutInventedLevels() {
        val models = parseChatGptModels(catalog(
            model("future-model", listOf("low", "medium", "high", "xhigh", "max", "ultra", "adaptive"), "high"),
            model("internal", listOf("high"), visibility = "hide"),
            model("plain", emptyList()), model("unknown-capabilities", null),
        ))
        assertEquals(listOf("future-model", "plain", "unknown-capabilities"), models.map { it.slug })
        assertEquals(listOf("low", "medium", "high", "xhigh", "max", "ultra", "adaptive"), models.first().reasoningOptions.map { it.effort })
        assertEquals("high", models.first().defaultReasoning)
        assertTrue(models[1].reasoningMetadataAvailable)
        assertFalse(models[2].reasoningMetadataAvailable)
        assertTrue(models[2].reasoningOptions.isEmpty())
        val camel = model("camel", null).put("supportedReasoningEfforts", JSONArray().put(JSONObject().put("reasoningEffort", "max")))
            .put("defaultReasoningEffort", "max")
        assertEquals("max", parseChatGptModels(catalog(camel)).single().defaultReasoning)
    }

    @Test fun malformedOauthCatalogAndUnsupportedRequestEffortAreRejected() {
        expectedFailure { parseChatGptModels(JSONObject().put("data", JSONArray())) }
        expectedFailure { parseChatGptModels(catalog(model("same", null), model("same", null))) }
        expectedFailure { parseChatGptModels(catalog(model("", null))) }
        expectedFailure { parseChatGptModels(catalog(model("model", null).put("slug", JSONObject.NULL))) }
        expectedFailure { parseChatGptModels(catalog(model("model", null).put("supported_reasoning_levels",
            JSONArray().put(JSONObject().put("effort", JSONObject.NULL))))) }
        val option = parseChatGptModels(catalog(model("model", listOf("none", "high")))).single()
        expectedFailure { workRequestOptions(option, "ultra") }
        val defaults = workRequestOptions(option, null)
        assertEquals("model", defaults.getString("model"))
        assertFalse(defaults.has("reasoning"))
        assertEquals("none", workRequestOptions(option, "none").getJSONObject("reasoning").getString("effort"))
        assertEquals("high", workRequestOptions(option, "high").getJSONObject("reasoning").getString("effort"))
        assertFalse(defaults.getBoolean("store"))
        assertTrue(defaults.getBoolean("stream"))
    }

    private inner class FakeHttp : ChatGptHttp {
        var body = catalog(model("model-a", listOf("low", "high", "ultra"), "low"), model("model-b", listOf("none", "minimal"), "none"))
        var modelRequests = 0
        var refreshRequests = 0
        var firstUnauthorized = false
        var alwaysUnauthorized = false
        var refreshDenied = false
        var requestedTokens = mutableListOf<String>()
        var refreshForm = emptyMap<String, String>()
        override fun getJson(url: String) = JSONObject().put("issuer", CHATGPT_ISSUER)
            .put("authorization_endpoint", "$CHATGPT_ISSUER/api/accounts/authorize")
            .put("token_endpoint", "$CHATGPT_ISSUER/api/accounts/oauth/token")
            .put("jwks_uri", "$CHATGPT_ISSUER/.well-known/jwks.json")
            .put("revocation_endpoint", "$CHATGPT_ISSUER/api/accounts/oauth/revoke")
        override fun postForm(url: String, body: Map<String, String>): JSONObject {
            refreshRequests++
            refreshForm = body
            if (refreshDenied) throw ChatGptAuthError("refresh denied", 400)
            return JSONObject().put("access_token", "rotated-access").put("refresh_token", "rotated-refresh")
                .put("token_type", "Bearer").put("expires_in", 3600)
        }
        override fun getModels(accessToken: String): JSONObject {
            modelRequests++
            requestedTokens.add(accessToken)
            if (alwaysUnauthorized || (firstUnauthorized && modelRequests == 1)) throw ChatGptAuthError("unauthorized", 401)
            return body
        }
    }

    private fun account(id: String = "oaiapp_a", expires: Long = System.currentTimeMillis() + 3_600_000, usePlan: Boolean = true) =
        ChatGptAccount(id, "subject-$id", "$id@example.invalid", "", "access-$id", "refresh-$id", "test-id", expires,
            if (usePlan) setOf("openid", CHATGPT_PLAN_SCOPE) else setOf("openid"), welcomeSeen = true)

    private class Fixture(val auth: ChatGptAuth, val store: ChatGptAccountStore, val http: ChatGptHttp)
    private fun fixture(accounts: List<ChatGptAccount>, http: FakeHttp = FakeHttp()): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "chatgpt-accounts.enc")
        file.delete(); File(file.path + ".bak").delete()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = ChatGptAccountStore(context) { key }
        store.write(ChatGptSavedAccounts(accounts = accounts.toMutableList(), activeClientId = accounts.first().clientId))
        val auth = ChatGptAuth(context, store, ChatGptOAuth(http))
        await { !auth.state.value.loading }
        return Fixture(auth, store, http)
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        fail("Settings operation did not finish")
    }

    @Test fun accountSelectionsPersistIndependentlyAcrossSwitchesAndReloads() {
        val f = fixture(listOf(account(), account("oaiapp_b")))
        f.auth.loadModels(); await { f.auth.modelSettings.value.loaded }
        f.auth.chooseModel("model-a"); await { f.auth.modelSettings.value.selectedSlug == "model-a" }
        f.auth.chooseEffort("ultra"); await { f.auth.modelSettings.value.selectedEffort == "ultra" }
        assertEquals("ultra", f.auth.selectedWorkRequestOptions().getJSONObject("reasoning").getString("effort"))
        f.auth.select("oaiapp_b"); await { f.auth.state.value.activeProfileId == "oaiapp_b" }
        assertFalse(f.auth.modelSettings.value.loaded)
        f.auth.loadModels(); await { f.auth.modelSettings.value.loaded }
        f.auth.chooseModel("model-b"); await { f.auth.modelSettings.value.selectedSlug == "model-b" }
        f.auth.chooseEffort("minimal"); await { f.auth.modelSettings.value.selectedEffort == "minimal" }
        val reloaded = f.store.read()
        assertEquals("model-a", reloaded.accounts[0].workModel)
        assertEquals("ultra", reloaded.accounts[0].workEffort)
        assertEquals("model-b", reloaded.accounts[1].workModel)
        assertEquals("minimal", reloaded.accounts[1].workEffort)
        f.auth.select("oaiapp_a"); await { f.auth.state.value.activeProfileId == "oaiapp_a" }
        f.auth.loadModels(); await { f.auth.modelSettings.value.loaded }
        assertEquals("ultra", f.auth.modelSettings.value.selectedEffort)
    }

    @Test fun changingModelResetsUnsupportedEffortAndRejectsUnlistedSelections() {
        val f = fixture(listOf(account().copy(workModel = "model-a", workEffort = "ultra")))
        f.auth.loadModels(); await { f.auth.modelSettings.value.loaded }
        f.auth.chooseModel("model-b"); await { f.auth.modelSettings.value.selectedSlug == "model-b" }
        assertNull(f.auth.modelSettings.value.selectedEffort)
        assertNull(f.store.read().active!!.workEffort)
        assertFalse(f.auth.selectedWorkRequestOptions().has("reasoning"))
        f.auth.chooseEffort("ultra")
        await { f.auth.state.value.message.contains("저장하지 못했") }
        assertNull(f.store.read().active!!.workEffort)
    }

    @Test fun disappearedModelBlocksWorkUntilExplicitReselectionWithoutReplacingSavedPreference() {
        val http = FakeHttp().apply { body = catalog(model("model-b", listOf("high"), "high")) }
        val f = fixture(listOf(account().copy(workModel = "model-a", workEffort = "high")), http)
        f.auth.loadModels(); await { f.auth.modelSettings.value.loaded }
        assertFalse(f.auth.modelSettings.value.selectionValid)
        assertEquals("model-a", f.store.read().active!!.workModel)
        expectedFailure { f.auth.selectedWorkRequestOptions() }
        assertTrue(f.auth.modelSettings.value.message.contains("다른 모델"))
    }

    @Test fun expiredSessionRotatesTokensBeforeCatalogWithoutChangingSavedModel() {
        val http = FakeHttp()
        val f = fixture(listOf(account(expires = 1).copy(workModel = "model-a", workEffort = "high")), http)
        f.auth.loadModels(); await { f.auth.modelSettings.value.loaded }
        assertEquals(1, http.refreshRequests)
        assertEquals(listOf("rotated-access"), http.requestedTokens)
        assertEquals("refresh_token", http.refreshForm["grant_type"])
        assertEquals("oaiapp_a", http.refreshForm["client_id"])
        assertEquals(CHATGPT_RESOURCE, http.refreshForm["resource"])
        assertFalse(http.refreshForm.containsKey("scope"))
        val active = f.store.read().active!!
        assertEquals("rotated-access", active.accessToken)
        assertEquals("rotated-refresh", active.refreshToken)
        assertEquals("high", active.workEffort)
        assertTrue(active.planAllowed)
    }

    @Test fun unauthorizedCatalogRefreshesOnlyOnceAndDenialRequiresSignIn() {
        val retryHttp = FakeHttp().apply { firstUnauthorized = true }
        val retry = fixture(listOf(account()), retryHttp)
        retry.auth.loadModels(); await { retry.auth.modelSettings.value.loaded }
        assertEquals(2, retryHttp.modelRequests)
        assertEquals(1, retryHttp.refreshRequests)
        val deniedHttp = FakeHttp().apply { alwaysUnauthorized = true }
        val denied = fixture(listOf(account()), deniedHttp)
        denied.auth.loadModels(); await { !denied.auth.state.value.busy }
        assertEquals(2, deniedHttp.modelRequests)
        assertEquals(1, deniedHttp.refreshRequests)
        assertFalse(denied.auth.state.value.connected)
        assertFalse(denied.auth.modelSettings.value.loaded)
        assertEquals("", denied.store.read().active!!.refreshToken)
    }

    @Test fun identityOnlyNeverFetchesModelsAndRejectedRefreshPreservesPreferences() {
        val identityHttp = FakeHttp()
        val identity = fixture(listOf(account(usePlan = false)), identityHttp)
        identity.auth.loadModels(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, identityHttp.modelRequests)
        assertFalse(identity.auth.modelSettings.value.loaded)
        val deniedHttp = FakeHttp().apply { refreshDenied = true }
        val denied = fixture(listOf(account(expires = 1).copy(workModel = "model-a", workEffort = "high")), deniedHttp)
        denied.auth.loadModels(); await { !denied.auth.state.value.busy }
        assertFalse(denied.auth.state.value.connected)
        assertEquals("model-a", denied.store.read().active!!.workModel)
        assertEquals("high", denied.store.read().active!!.workEffort)
    }
}
