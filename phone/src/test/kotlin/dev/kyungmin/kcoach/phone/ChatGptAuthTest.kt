package dev.kyungmin.kcoach.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChatGptAuthTest {
    private val key: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
    private val now = System.currentTimeMillis()

    private fun token(client: String = "oaiapp_test", nonce: String = "nonce", signingKey: RSAKey = key,
        issuer: String = CHATGPT_ISSUER, expires: Long = now + 60_000, subject: String = "test-subject"): String {
        val claims = JWTClaimsSet.Builder().issuer(issuer).audience(client).subject(subject)
            .issueTime(Date(now)).expirationTime(Date(expires)).claim("nonce", nonce)
            .claim("email", "test@example.invalid").claim("name", "Test account").build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims)
            .apply { sign(RSASSASigner(signingKey)) }.serialize()
    }

    private fun jwks() = JSONObject(JWKSet(key.toPublicJWK()).toString())
    private fun expectFailure(action: () -> Unit) {
        try { action(); fail("Untrusted login must be rejected") } catch (_: ChatGptAuthError) { }
    }

    @Test fun authorizationUsesFreshPkceAndExactLoopbackContract() {
        val first = ChatGptSignInAttempt("http://127.0.0.1:12345/auth/callback", null, null)
        val second = ChatGptSignInAttempt(first.redirectUri, null, null)
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.nonce, second.nonce)
        assertNotEquals(first.verifier, second.verifier)
        val query = decodeQuery(URI(first.authorizationUrl("urn:uuid:test", true, null)).rawQuery)
        assertEquals("dynamic_agent_client", query["client_id"])
        assertEquals("KCoach", query["agent_name_hint"])
        assertEquals(first.redirectUri, query["redirect_uri"])
        assertEquals("S256", query["code_challenge_method"])
        assertNotEquals(first.verifier, query["code_challenge"])
        assertTrue(query["scope"]!!.split(' ').contains(CHATGPT_PLAN_SCOPE))
        assertEquals(CHATGPT_RESOURCE, query["resource"])
        val returning = ChatGptSignInAttempt(first.redirectUri, "oaiapp_existing", "test-subject")
        val requery = decodeQuery(URI(returning.authorizationUrl("urn:uuid:test", false, null)).rawQuery)
        assertEquals("oaiapp_existing", requery["client_id"])
        assertFalse(requery.containsKey("agent_name_hint"))
        assertFalse(requery["scope"]!!.contains(CHATGPT_PLAN_SCOPE))
        val previous = ChatGptAccount("oaiapp_existing", "test-subject", "test@example.invalid", "", "access", "refresh", "id", now + 60_000,
            setOf("openid", "email", "profile"))
        val permissionUpgrade = decodeQuery(URI(returning.authorizationUrl("urn:uuid:test", true, previous)).rawQuery)
        assertEquals("consent", permissionUpgrade["prompt"])
        val regular = decodeQuery(URI(returning.authorizationUrl("urn:uuid:test", true,
            previous.copy(scopes = previous.scopes + CHATGPT_PLAN_SCOPE))).rawQuery)
        assertFalse(regular.containsKey("prompt"))
    }

    @Test fun callbackRejectsWrongStateMissingRegistrationDuplicateParametersExpiredAndSwappedClient() {
        val attempt = ChatGptSignInAttempt("http://127.0.0.1:1/auth/callback", null, null, createdAtMs = now)
        expectFailure { attempt.callback("state=wrong&code=code&client_id=oaiapp_test", now) }
        expectFailure { attempt.callback(encodeForm(mapOf("state" to attempt.state, "code" to "code")), now) }
        expectFailure { attempt.callback("state=${attempt.state}&state=${attempt.state}&code=code&client_id=oaiapp_test", now) }
        expectFailure { attempt.callback("state=${attempt.state}&code=code&client_id=oaiapp_test", now + SIGN_IN_TIMEOUT_MS) }
        expectFailure { attempt.callback("state=${attempt.state}&error=access_denied", now) }
        val returning = ChatGptSignInAttempt(attempt.redirectUri, "oaiapp_existing", "test-subject", createdAtMs = now)
        expectFailure { returning.callback("state=${returning.state}&code=code&client_id=oaiapp_other", now) }
        assertEquals("oaiapp_existing", returning.callback("state=${returning.state}&code=code", now).clientId)
    }

    @Test fun validatesRealSignatureIssuerAudienceExpiryNonceAndSubject() {
        val claims = ChatGptOAuth.verifyIdentity(token(), jwks(), "oaiapp_test", "nonce", now)
        assertEquals("test-subject", claims.subject)
        expectFailure { ChatGptOAuth.verifyIdentity(token(signingKey = RSAKeyGenerator(2048).keyID("test-key").generate()), jwks(), "oaiapp_test", "nonce", now) }
        expectFailure { ChatGptOAuth.verifyIdentity(token(issuer = "https://attacker.invalid"), jwks(), "oaiapp_test", "nonce", now) }
        expectFailure { ChatGptOAuth.verifyIdentity(token(client = "oaiapp_other"), jwks(), "oaiapp_test", "nonce", now) }
        expectFailure { ChatGptOAuth.verifyIdentity(token(expires = now - 6_000), jwks(), "oaiapp_test", "nonce", now) }
        expectFailure { ChatGptOAuth.verifyIdentity(token(nonce = "other"), jwks(), "oaiapp_test", "nonce", now) }
        expectFailure { ChatGptOAuth.verifyIdentity(token(subject = ""), jwks(), "oaiapp_test", "nonce", now) }
    }

    private inner class FakeOpenAi : ChatGptHttp {
        var nonce = "nonce"
        var grantedScope = "openid email profile $CHATGPT_PLAN_SCOPE"
        var subject = "test-subject"
        var tokenPosts = 0
        var revokePosts = 0
        var postedForm: Map<String, String> = emptyMap()
        var failRevoke = false
        override fun getJson(url: String): JSONObject = when {
            url.endsWith("openid-configuration") -> JSONObject().put("issuer", CHATGPT_ISSUER)
                .put("authorization_endpoint", "$CHATGPT_ISSUER/api/accounts/authorize")
                .put("token_endpoint", "$CHATGPT_ISSUER/api/accounts/oauth/token")
                .put("jwks_uri", "$CHATGPT_ISSUER/.well-known/jwks.json")
                .put("revocation_endpoint", "$CHATGPT_ISSUER/api/accounts/oauth/revoke")
            url.endsWith("jwks.json") -> jwks()
            else -> error("Unexpected endpoint")
        }
        override fun postForm(url: String, body: Map<String, String>): JSONObject {
            postedForm = body
            if (url.endsWith("revoke")) {
                revokePosts++
                if (failRevoke) throw java.io.IOException("offline")
                return JSONObject()
            }
            tokenPosts++
            return JSONObject().put("id_token", token(client = body.getValue("client_id"), nonce = nonce, subject = subject))
                .put("access_token", "test-access-secret").put("refresh_token", "test-refresh-secret")
                .put("token_type", "Bearer").put("expires_in", 3600).put("scope", grantedScope)
        }
    }

    @Test fun usesGrantedTokenScopesRatherThanRequestedOrCallbackScopes() {
        val http = FakeOpenAi().apply { grantedScope = "openid email profile" }
        val attempt = ChatGptSignInAttempt("http://127.0.0.1:12345/auth/callback", null, null, nonce = "nonce")
        val account = ChatGptOAuth(http).exchange(attempt, ChatGptAuthorization("oaiapp_test", "test-code"), now)
        assertTrue(account.connected)
        assertFalse(account.planAllowed)
        assertEquals("oaiapp_test", http.postedForm["client_id"])
        assertEquals(attempt.redirectUri, http.postedForm["redirect_uri"])
        assertEquals(attempt.verifier, http.postedForm["code_verifier"])
        assertFalse(http.postedForm.containsKey("client_secret"))
        http.subject = "wrong-account"
        expectFailure {
            ChatGptOAuth(http).exchange(ChatGptSignInAttempt(attempt.redirectUri, "oaiapp_test", "test-subject", nonce = "nonce"),
                ChatGptAuthorization("oaiapp_test", "test-code"), now)
        }
    }

    private fun storage(): ChatGptAccountStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        File(context.noBackupFilesDir, "chatgpt-accounts.enc").delete()
        return ChatGptAccountStore(context) { key }
    }

    @Test fun credentialsAreEncryptedAndPersistHostAndRegistrationsAcrossReload() {
        val store = storage()
        val account = ChatGptOAuth(FakeOpenAi()).exchange(
            ChatGptSignInAttempt("http://127.0.0.1:1/auth/callback", null, null, nonce = "nonce"), ChatGptAuthorization("oaiapp_test", "code"), now)
        val saved = ChatGptSavedAccounts(accounts = mutableListOf(account), activeClientId = account.clientId)
        store.write(saved)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = File(context.noBackupFilesDir, "chatgpt-accounts.enc").readText()
        assertFalse(raw.contains("test-access-secret"))
        assertFalse(raw.contains("test-refresh-secret"))
        assertFalse(raw.contains("test@example.invalid"))
        val restored = store.read()
        assertEquals(saved.hostId, restored.hostId)
        assertEquals("test-access-secret", restored.active!!.accessToken)
        assertFalse(account.toString().contains("secret"))
        // Android's legacy AtomicFile can have only its recovery file after an interrupted write.
        val path = File(context.noBackupFilesDir, "chatgpt-accounts.enc")
        assertTrue(path.renameTo(File(path.path + ".bak")))
        assertEquals(saved.hostId, store.read().hostId)
        assertEquals("test-refresh-secret", store.read().active!!.refreshToken)
    }

    private fun awaitState(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        }
        fail("Auth operation did not finish")
    }

    private fun callback(url: String): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        try {
            val status = connection.responseCode
            (if (status < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
            return status
        } finally { connection.disconnect() }
    }

    @Test fun realLoopbackCompletesOnceForgedCallbackDoesNotConsumeAndLogoutKeepsWorkoutIndependent() {
        val store = storage()
        val http = FakeOpenAi()
        val auth = ChatGptAuth(ApplicationProvider.getApplicationContext(), store, ChatGptOAuth(http))
        awaitState { !auth.state.value.loading }
        val browser = AtomicReference<String>()
        auth.begin(usePlan = true) { browser.set(it) }
        awaitState { browser.get() != null }
        val query = decodeQuery(URI(browser.get()).rawQuery)
        http.nonce = query.getValue("nonce")
        val redirect = query.getValue("redirect_uri")
        assertEquals(400, callback("$redirect?state=forged&code=code&client_id=oaiapp_test"))
        assertEquals(0, http.tokenPosts)
        assertTrue(auth.state.value.signingIn)
        assertEquals(200, callback("$redirect?${encodeForm(mapOf("state" to query.getValue("state"), "code" to "test-code", "client_id" to "oaiapp_test"))}"))
        awaitState { auth.state.value.connected && !auth.state.value.busy }
        assertTrue(auth.state.value.planAllowed)
        assertEquals(1, http.tokenPosts)
        assertEquals("test-access-secret", store.read().active!!.accessToken)
        val host = store.read().hostId
        auth.signOut()
        awaitState { !auth.state.value.busy && !auth.state.value.connected }
        assertEquals(1, http.revokePosts)
        assertEquals(host, store.read().hostId)
        assertEquals("oaiapp_test", store.read().activeClientId)
        assertEquals("", store.read().active!!.accessToken)
        assertEquals("", store.read().active!!.refreshToken)
        assertEquals("", store.read().active!!.idToken)
        // A later sign-in reuses the issued registration instead of creating another one.
        browser.set(null)
        auth.begin(usePlan = true) { browser.set(it) }
        awaitState { browser.get() != null }
        assertEquals("oaiapp_test", decodeQuery(URI(browser.get()).rawQuery)["client_id"])
        auth.cancel()
    }

    @Test fun cancellationAndDeniedNewConnectionKeepExistingAccount() {
        val store = storage()
        val http = FakeOpenAi()
        val account = ChatGptOAuth(http).exchange(ChatGptSignInAttempt("http://127.0.0.1:1/auth/callback", null, null, nonce = "nonce"),
            ChatGptAuthorization("oaiapp_existing", "code"), now)
        store.write(ChatGptSavedAccounts(accounts = mutableListOf(account), activeClientId = account.clientId))
        val auth = ChatGptAuth(ApplicationProvider.getApplicationContext(), store, ChatGptOAuth(http))
        awaitState { !auth.state.value.loading }
        val browser = AtomicReference<String>()
        auth.begin(true, newAccount = true) { browser.set(it) }
        awaitState { browser.get() != null }
        val query = decodeQuery(URI(browser.get()).rawQuery)
        assertEquals(400, callback("${query.getValue("redirect_uri")}?${encodeForm(mapOf("state" to query.getValue("state"), "error" to "access_denied"))}"))
        awaitState { !auth.state.value.busy }
        assertTrue(auth.state.value.connected)
        assertEquals("oaiapp_existing", store.read().activeClientId)
        browser.set(null)
        auth.begin(true, newAccount = true) { browser.set(it) }
        awaitState { browser.get() != null }
        auth.cancel()
        awaitState { !auth.state.value.busy }
        assertTrue(auth.state.value.connected)
        assertEquals(account.accessToken, store.read().active!!.accessToken)
    }

    @Test fun revocationFailureStillClearsLocalCredentialsAndReportsUnconfirmedRemoteLogout() {
        val store = storage()
        val http = FakeOpenAi()
        val account = ChatGptOAuth(http).exchange(ChatGptSignInAttempt("http://127.0.0.1:1/auth/callback", null, null, nonce = "nonce"),
            ChatGptAuthorization("oaiapp_test", "code"), now)
        store.write(ChatGptSavedAccounts(accounts = mutableListOf(account), activeClientId = account.clientId))
        http.failRevoke = true
        val auth = ChatGptAuth(ApplicationProvider.getApplicationContext(), store, ChatGptOAuth(http))
        awaitState { !auth.state.value.loading }
        auth.signOut()
        awaitState { !auth.state.value.busy }
        assertFalse(auth.state.value.connected)
        assertEquals("", store.read().active!!.refreshToken)
        assertTrue(auth.state.value.message.contains("서버 연결 해제를 확인하지 못했"))
        assertEquals(3, http.revokePosts)
    }
}
