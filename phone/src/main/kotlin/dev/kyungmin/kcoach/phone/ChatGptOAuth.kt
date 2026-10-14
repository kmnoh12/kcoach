package dev.kyungmin.kcoach.phone

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal const val CHATGPT_ISSUER = "https://auth.openai.com"
internal const val CHATGPT_RESOURCE = "https://api.openai.com/v1"
internal const val CHATGPT_PLAN_SCOPE = "chatgpt.tokens.use.direct"

/** Never expose tokens through UI models, logging, or generated data-class toString(). */
internal data class ChatGptAccount(
    val clientId: String,
    val subject: String,
    val email: String,
    val name: String,
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val expiresAtMs: Long,
    val scopes: Set<String>,
    val welcomeSeen: Boolean = false,
    val workModel: String = "",
    val workEffort: String? = null,
) {
    val connected get() = idToken.isNotBlank()
    val planAllowed get() = accessToken.isNotBlank() && CHATGPT_PLAN_SCOPE in scopes
    override fun toString() = "ChatGptAccount([redacted])"
}

internal class ChatGptAuthError(val userMessage: String, val httpStatus: Int? = null) : Exception(userMessage)

internal class ChatGptSignInAttempt(
    val redirectUri: String,
    val clientId: String?,
    val expectedSubject: String?,
    val state: String = randomSecret(),
    val nonce: String = randomSecret(),
    val verifier: String = randomSecret(),
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    val challenge: String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    fun authorizationUrl(hostId: String, usePlan: Boolean, previous: ChatGptAccount?): String {
        val params = linkedMapOf(
            "client_id" to (clientId ?: "dynamic_agent_client"),
            "ext_agent_host_id" to hostId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "scope" to ("openid profile email" + if (usePlan) " offline_access resource.invoke $CHATGPT_PLAN_SCOPE" else ""),
            "resource" to CHATGPT_RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
        )
        if (clientId == null) params["agent_name_hint"] = "KCoach"
        else if (previous != null) {
            if (previous.idToken.isNotBlank()) params["id_token_hint"] = previous.idToken
            if (previous.email.isNotBlank()) params["login_hint"] = previous.email
            // Explicitly enabling plan usage after identity-only login needs renewed consent.
            if (usePlan && CHATGPT_PLAN_SCOPE !in previous.scopes) params["prompt"] = "consent"
        }
        return "$CHATGPT_ISSUER/api/accounts/authorize?${encodeForm(params)}"
    }

    fun callback(query: String?, nowMs: Long): ChatGptAuthorization {
        if (nowMs - createdAtMs !in 0 until SIGN_IN_TIMEOUT_MS) authFail("로그인 시간이 만료됐어요. 다시 연결해 주세요")
        val values = decodeQuery(query)
        if (!constantEquals(state, values["state"].orEmpty())) authFail("로그인 응답을 확인할 수 없어요. 다시 연결해 주세요")
        if (values.containsKey("error")) {
            authFail(if (values["error"] == "access_denied") "로그인을 취소했어요" else "로그인을 완료하지 못했어요. 다시 연결해 주세요")
        }
        val issuedId = values["client_id"] ?: clientId
        if (issuedId.isNullOrBlank() || issuedId == "dynamic_agent_client" || !issuedId.matches(Regex("[A-Za-z0-9_-]{1,256}")))
            authFail("앱 연결 등록을 완료하지 못했어요. 다시 연결해 주세요")
        if (clientId != null && clientId != issuedId) authFail("다른 계정 연결 응답이에요. 다시 연결해 주세요")
        val code = values["code"]?.takeIf { it.isNotBlank() } ?: authFail("로그인 코드가 없어요. 다시 연결해 주세요")
        return ChatGptAuthorization(issuedId, code)
    }

    override fun toString() = "ChatGptSignInAttempt([redacted])"
}

internal class ChatGptAuthorization(val clientId: String, val code: String) {
    override fun toString() = "ChatGptAuthorization([redacted])"
}

internal const val SIGN_IN_TIMEOUT_MS = 10 * 60 * 1000L

internal fun randomSecret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
    ByteArray(32).also { SecureRandom().nextBytes(it) },
)

internal fun constantEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

internal fun encodeForm(values: Map<String, String>) = values.entries.joinToString("&") {
    "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8") }"
}

internal fun decodeQuery(query: String?): Map<String, String> {
    val result = linkedMapOf<String, String>()
    try {
        query.orEmpty().split('&').filter { it.isNotBlank() }.forEach {
            val pair = it.split('=', limit = 2)
            val key = URLDecoder.decode(pair[0], "UTF-8")
            if (result.containsKey(key)) authFail("로그인 응답을 확인할 수 없어요")
            result[key] = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
    } catch (_: IllegalArgumentException) { authFail("로그인 응답을 확인할 수 없어요") }
    return result
}

internal fun authFail(message: String): Nothing = throw ChatGptAuthError(message)

internal interface ChatGptHttp {
    fun getJson(url: String): JSONObject
    fun postForm(url: String, body: Map<String, String>): JSONObject
    fun getModels(accessToken: String): JSONObject = authFail("지원 모델 목록을 불러오지 못했어요")
}

internal class ChatGptHttps : ChatGptHttp {
    override fun getJson(url: String) = request(url, null)
    override fun postForm(url: String, body: Map<String, String>) = request(url, encodeForm(body))
    override fun getModels(accessToken: String) = request("$CHATGPT_RESOURCE/models", null, accessToken)

    private fun request(url: String, form: String?, accessToken: String? = null): JSONObject {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.port == -1 && uri.userInfo == null)
        require(if (accessToken == null) uri.host == "auth.openai.com" else url == "$CHATGPT_RESOURCE/models")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (accessToken != null) connection.setRequestProperty("Authorization", "Bearer $accessToken")
            if (form != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(form.toByteArray(StandardCharsets.UTF_8)) }
            }
            if (connection.responseCode !in 200..299) throw ChatGptAuthError(
                "OpenAI 연결을 완료하지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요", connection.responseCode)
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > (if (accessToken == null) 1_048_576 else 4_194_304)) authFail("OpenAI 응답을 확인할 수 없어요")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            return if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes, StandardCharsets.UTF_8))
        } finally { connection.disconnect() }
    }
}

/** OpenAI OIDC discovery and JWT signature verification; no backend-api or embedded client secret. */
internal class ChatGptOAuth(private val http: ChatGptHttp = ChatGptHttps()) {
    private fun discovery(): JSONObject = http.getJson("$CHATGPT_ISSUER/.well-known/openid-configuration").also { config ->
        if (config.getString("issuer") != CHATGPT_ISSUER) authFail("OpenAI 로그인 주소를 확인할 수 없어요")
        listOf("authorization_endpoint", "token_endpoint", "jwks_uri", "revocation_endpoint").forEach { field ->
            val uri = URI(config.getString(field))
            if (uri.scheme != "https" || uri.host != "auth.openai.com" || uri.port != -1 || uri.userInfo != null)
                authFail("OpenAI 로그인 주소를 확인할 수 없어요")
        }
        if (config.getString("authorization_endpoint") != "$CHATGPT_ISSUER/api/accounts/authorize")
            authFail("OpenAI 로그인 주소가 바뀌었어요. 앱 업데이트를 확인해 주세요")
    }

    fun exchange(attempt: ChatGptSignInAttempt, authorization: ChatGptAuthorization, nowMs: Long): ChatGptAccount {
        val config = discovery()
        val tokens = http.postForm(config.getString("token_endpoint"), mapOf(
            "grant_type" to "authorization_code", "client_id" to authorization.clientId,
            "code" to authorization.code, "code_verifier" to attempt.verifier,
            "redirect_uri" to attempt.redirectUri, "resource" to CHATGPT_RESOURCE,
        ))
        val idToken = tokens.optString("id_token")
        val claims = verifyIdentity(idToken, http.getJson(config.getString("jwks_uri")), authorization.clientId, attempt.nonce, nowMs)
        if (attempt.expectedSubject != null && claims.subject != attempt.expectedSubject)
            authFail("기존 연결과 다른 계정이에요. 다른 계정으로 연결해 주세요")
        val access = tokens.optString("access_token")
        if (access.isNotBlank() && !tokens.optString("token_type").equals("Bearer", ignoreCase = true))
            authFail("로그인 인증 정보를 확인할 수 없어요")
        val scopes = tokens.optString("scope").split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        return ChatGptAccount(
            authorization.clientId, claims.subject, claims.getStringClaim("email").orEmpty(), claims.getStringClaim("name").orEmpty(),
            access, tokens.optString("refresh_token"), idToken,
            nowMs + tokens.optLong("expires_in", 0).coerceIn(0, 365 * 24 * 3600L) * 1000, scopes,
        )
    }

    fun revoke(account: ChatGptAccount): Boolean {
        if (account.refreshToken.isBlank()) return true
        repeat(3) { index ->
            try {
                http.postForm(discovery().getString("revocation_endpoint"), mapOf(
                    "token" to account.refreshToken, "token_type_hint" to "refresh_token", "client_id" to account.clientId,
                ))
                return true
            } catch (_: Exception) { if (index < 2) Thread.sleep((index + 1) * 300L) }
        }
        return false
    }

    fun models(account: ChatGptAccount): List<ChatGptModelOption> {
        if (!account.connected || !account.planAllowed) authFail("ChatGPT 연결과 구독 한도 사용 동의가 필요해요")
        return parseChatGptModels(http.getModels(account.accessToken))
    }

    /** Caller serializes refresh and atomically stores the returned rotating token set. */
    fun refresh(account: ChatGptAccount, nowMs: Long): ChatGptAccount {
        if (account.refreshToken.isBlank()) authFail("ChatGPT 연결이 만료됐어요. 다시 연결해 주세요")
        val config = discovery()
        val tokens = http.postForm(config.getString("token_endpoint"), mapOf(
            "grant_type" to "refresh_token", "client_id" to account.clientId,
            "refresh_token" to account.refreshToken, "resource" to CHATGPT_RESOURCE,
        ))
        val access = tokens.optString("access_token")
        val refresh = tokens.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank() || !tokens.optString("token_type").equals("Bearer", ignoreCase = true))
            authFail("ChatGPT 인증 정보를 갱신하지 못했어요. 다시 연결해 주세요")
        val renewedId = tokens.optString("id_token").ifBlank { account.idToken }
        if (tokens.optString("id_token").isNotBlank()) {
            val claims = verifyIdentity(renewedId, http.getJson(config.getString("jwks_uri")), account.clientId, null, nowMs)
            if (claims.subject != account.subject) authFail("갱신된 계정 정보를 확인할 수 없어요. 다시 연결해 주세요")
        }
        val scopes = if (tokens.has("scope")) tokens.optString("scope").split(Regex("\\s+")).filter { it.isNotBlank() }.toSet() else account.scopes
        return account.copy(accessToken = access, refreshToken = refresh, idToken = renewedId,
            expiresAtMs = nowMs + tokens.optLong("expires_in").coerceIn(0, 365 * 24 * 3600L) * 1000, scopes = scopes)
    }

    companion object {
        internal fun verifyIdentity(token: String, jwks: JSONObject, clientId: String, nonce: String?, nowMs: Long): com.nimbusds.jwt.JWTClaimsSet {
            try {
                val jwt = SignedJWT.parse(token)
                if (jwt.header.algorithm != JWSAlgorithm.RS256) authFail("로그인 인증 서명을 확인할 수 없어요")
                val key = JWKSet.parse(jwks.toString()).keys.filterIsInstance<RSAKey>().firstOrNull {
                    it.keyID == jwt.header.keyID && it.keyID != null &&
                        (it.keyUse == null || it.keyUse == KeyUse.SIGNATURE) && (it.algorithm == null || it.algorithm == JWSAlgorithm.RS256)
                } ?: authFail("로그인 인증 서명을 확인할 수 없어요")
                if (!jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))) authFail("로그인 인증 서명을 확인할 수 없어요")
                val claims = jwt.jwtClaimsSet
                if (claims.issuer != CHATGPT_ISSUER || clientId !in claims.audience || claims.subject.isNullOrBlank() ||
                    claims.expirationTime == null || claims.expirationTime.time <= nowMs - 5_000 ||
                    claims.issueTime == null || claims.issueTime.time > nowMs + 5_000 ||
                    (claims.notBeforeTime != null && claims.notBeforeTime.time > nowMs + 5_000) ||
                    (nonce != null && !constantEquals(nonce, claims.getStringClaim("nonce").orEmpty())) ||
                    (claims.audience.size > 1 && claims.getStringClaim("azp") != clientId))
                    authFail("로그인 계정 정보를 확인할 수 없어요. 다시 연결해 주세요")
                return claims
            } catch (e: ChatGptAuthError) { throw e }
            catch (_: Exception) { authFail("로그인 인증 정보를 확인할 수 없어요. 다시 연결해 주세요") }
        }
    }
}
