package dev.kyungmin.kcoach.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.Executors

data class ChatGptProfileUi(val id: String, val label: String, val connected: Boolean)

data class ChatGptConnectionUi(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val signingIn: Boolean = false,
    val connected: Boolean = false,
    val accountLabel: String = "",
    val planAllowed: Boolean = false,
    val message: String = "",
    val profiles: List<ChatGptProfileUi> = emptyList(),
    val activeProfileId: String? = null,
    val welcome: Boolean = false,
    val connectionRevision: Int = 0,
)

/** App-scoped: browser switches and Activity recreation don't replace a pending OAuth listener. */
internal class ChatGptAuth internal constructor(
    context: Context,
    private val store: ChatGptAccountStore = ChatGptAccountStore(context.applicationContext),
    private val oauth: ChatGptOAuth = ChatGptOAuth(),
) {
    val state = mutableStateOf(ChatGptConnectionUi(loading = true))
    val modelSettings = mutableStateOf(ChatGptModelSettingsUi())
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool { work -> Thread(work, "kcoach-chatgpt-auth").apply { isDaemon = true } }
    private val lock = Any()
    private var saved = ChatGptSavedAccounts()
    private var loaded = false
    private var generation = 0
    private var connectionRevision = 0
    private var listener: ServerSocket? = null
    private var busy = false
    private var signingIn = false
    private var message = ""
    private var modelCatalog = emptyList<ChatGptModelOption>()
    private var modelClientId: String? = null
    private var modelsLoaded = false
    private var modelsLoading = false
    private var modelsMessage = ""

    init {
        executor.execute {
            synchronized(lock) {
                try { saved = store.read(); store.write(saved); loaded = true }
                catch (_: Exception) { message = "저장된 연결 정보를 열지 못했어요. 앱을 다시 열어 주세요" }
            }
            publish()
        }
    }

    private fun publish() {
        handler.post {
            state.value = synchronized(lock) {
                val account = saved.active
                ChatGptConnectionUi(
                    loading = !loaded, busy = busy, signingIn = signingIn,
                    connected = account?.connected == true, accountLabel = account?.label().orEmpty(),
                    planAllowed = account?.planAllowed == true, message = message,
                    profiles = saved.accounts.map { ChatGptProfileUi(it.clientId, it.label(), it.connected) },
                    activeProfileId = saved.activeClientId,
                    welcome = account?.let { it.connected && it.planAllowed && !it.welcomeSeen } == true,
                    connectionRevision = connectionRevision,
                )
            }
            modelSettings.value = synchronized(lock) {
                val account = saved.active
                ChatGptModelSettingsUi(
                    loading = modelsLoading, loaded = modelsLoaded && modelClientId == account?.clientId,
                    models = if (modelClientId == account?.clientId) modelCatalog else emptyList(),
                    selectedSlug = account?.workModel.orEmpty(), selectedEffort = account?.workEffort, message = modelsMessage,
                )
            }
        }
    }

    fun begin(usePlan: Boolean, newAccount: Boolean = false, openBrowser: (String) -> Unit) {
        val ticket = synchronized(lock) {
            if (!loaded || busy) return
            generation += 1
            busy = true
            signingIn = true
            message = "브라우저에서 ChatGPT 연결을 진행해 주세요"
            generation
        }
        publish()
        executor.execute {
            try {
                val socket = ServerSocket().apply {
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 2)
                    soTimeout = 1_000
                }
                val previous: ChatGptAccount?
                val attempt: ChatGptSignInAttempt
                val url: String
                synchronized(lock) {
                    if (ticket != generation) { socket.close(); return@execute }
                    listener = socket
                    previous = if (newAccount) null else saved.active
                    attempt = ChatGptSignInAttempt(
                        "http://127.0.0.1:${socket.localPort}/auth/callback",
                        previous?.clientId ?: if (newAccount) null else saved.unfinishedClientId,
                        previous?.subject,
                    )
                    url = attempt.authorizationUrl(saved.hostId, usePlan, previous)
                }
                handler.postDelayed({
                    synchronized(lock) { if (generation == ticket && signingIn) cancel("로그인 시간이 만료됐어요. 다시 연결해 주세요") }
                }, SIGN_IN_TIMEOUT_MS)
                handler.post {
                    if (synchronized(lock) { generation == ticket && signingIn }) {
                        try { openBrowser(url) }
                        catch (_: Exception) { cancel("브라우저를 열지 못했어요. 기본 브라우저를 확인해 주세요") }
                    }
                }
                val authorization = waitForCallback(socket, attempt, ticket) ?: return@execute
                synchronized(lock) {
                    if (ticket != generation) return@execute
                    saved.unfinishedClientId = if (previous == null) authorization.clientId else saved.unfinishedClientId
                    store.write(saved)
                    message = "ChatGPT 계정 확인 중…"
                }
                publish()
                val account = oauth.exchange(attempt, authorization, System.currentTimeMillis())
                synchronized(lock) {
                    if (ticket != generation) return@execute
                    val existing = saved.accounts.firstOrNull { it.clientId == account.clientId }
                    val replacement = account.copy(welcomeSeen = existing?.welcomeSeen ?: false,
                        workModel = existing?.workModel.orEmpty(), workEffort = existing?.workEffort)
                    val updated = ChatGptSavedAccounts(saved.hostId,
                        saved.accounts.map { if (it.clientId == account.clientId) replacement else it }.toMutableList()
                            .apply { if (existing == null) add(replacement) }, account.clientId, null)
                    store.write(updated)
                    saved = updated
                    connectionRevision += 1
                    invalidateModels()
                    busy = false
                    signingIn = false
                    message = if (account.planAllowed) "ChatGPT 연결과 구독 한도 사용 동의를 확인했어요" else "ChatGPT에 로그인했어요. 구독 한도 사용은 허용되지 않았어요"
                    listener = null
                }
                publish()
            } catch (e: Exception) {
                synchronized(lock) {
                    if (ticket != generation) return@execute
                    listener?.runCatching { close() }
                    listener = null
                    busy = false
                    signingIn = false
                    message = (e as? ChatGptAuthError)?.userMessage ?: "로그인을 완료하지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요"
                }
                publish()
            }
        }
    }

    private fun waitForCallback(socket: ServerSocket, attempt: ChatGptSignInAttempt, ticket: Int): ChatGptAuthorization? {
        socket.use {
            while (synchronized(lock) { generation == ticket } && System.currentTimeMillis() - attempt.createdAtMs < SIGN_IN_TIMEOUT_MS) {
                val connection = try { socket.accept() } catch (_: SocketTimeoutException) { continue }
                connection.use clientUse@ { client ->
                    client.soTimeout = 5_000
                    val target = try {
                        val line = readRequestLine(client)
                        val parts = line.split(' ')
                        require(parts.size == 3 && parts[0] == "GET" && parts[1].startsWith('/'))
                        URI("http://127.0.0.1${parts[1]}")
                    } catch (_: Exception) { respond(client, 400, false); return@clientUse }
                    if (target.path != "/auth/callback") { respond(client, 404, false); return@clientUse }
                    // Unrelated/forged callbacks do not consume or cancel a real browser login.
                    val values = try { decodeQuery(target.rawQuery) } catch (_: Exception) { respond(client, 400, false); return@clientUse }
                    if (!constantEquals(attempt.state, values["state"].orEmpty())) { respond(client, 400, false); return@clientUse }
                    val authorization = try { attempt.callback(target.rawQuery, System.currentTimeMillis()) }
                    catch (e: ChatGptAuthError) { respond(client, 400, true); throw e }
                    respond(client, 200, true)
                    return authorization
                }
            }
        }
        return null
    }

    private fun readRequestLine(socket: Socket): String {
        val bytes = ArrayList<Byte>()
        val input = socket.getInputStream()
        repeat(8192) {
            val value = input.read()
            require(value >= 0)
            if (value == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
            bytes.add(value.toByte())
        }
        throw IllegalArgumentException("Request too long")
    }

    private fun respond(socket: Socket, status: Int, returnToApp: Boolean) {
        val link = if (returnToApp) "<p><a href=\"kcoach://chatgpt/return\">KCoach로 돌아가기</a></p>" else ""
        val body = "<!doctype html><html lang=\"ko\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>KCoach</title><body><h1>ChatGPT 연결</h1><p>${if (returnToApp) "앱으로 돌아가 연결 결과를 확인해 주세요" else "로그인 응답을 확인할 수 없어요"}</p>$link</body></html>".toByteArray(Charsets.UTF_8)
        runCatching {
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status ${if (status == 200) "OK" else "Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; base-uri 'none'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                write(body); flush()
            }
        }
    }

    fun cancel(reason: String = "로그인을 취소했어요") {
        synchronized(lock) {
            if (!signingIn) return
            generation += 1
            listener?.runCatching { close() }
            listener = null
            busy = false
            signingIn = false
            message = reason
        }
        publish()
    }

    fun select(clientId: String) = persistAction {
        require(saved.accounts.any { it.clientId == clientId })
        saved.activeClientId = clientId
        invalidateModels()
        message = "계정을 선택했어요"
    }

    fun acknowledgeWelcome() = persistAction {
        val account = saved.active ?: return@persistAction
        saved.accounts.replaceAll { if (it.clientId == account.clientId) it.copy(welcomeSeen = true) else it }
    }

    private fun persistAction(action: () -> Unit) {
        synchronized(lock) {
            if (!loaded || busy) return
            busy = true
        }
        publish()
        executor.execute {
            synchronized(lock) {
                val before = ChatGptSavedAccounts(saved.hostId, saved.accounts.toMutableList(), saved.activeClientId, saved.unfinishedClientId)
                try { action(); store.write(saved) }
                catch (_: Exception) {
                    saved = before
                    message = "연결 정보 변경을 저장하지 못했어요. 다시 시도해 주세요"
                    modelsMessage = message
                } finally { busy = false }
            }
            publish()
        }
    }

    fun signOut() {
        val account = synchronized(lock) {
            if (!loaded || busy) return
            val current = saved.active ?: return
            busy = true
            message = "로그아웃 중…"
            current
        }
        publish()
        executor.execute {
            val revoked = oauth.revoke(account)
            synchronized(lock) {
                val replacement = account.copy(accessToken = "", refreshToken = "", idToken = "", expiresAtMs = 0)
                val updated = ChatGptSavedAccounts(saved.hostId,
                    saved.accounts.map { if (it.clientId == account.clientId) replacement else it }.toMutableList(), saved.activeClientId, saved.unfinishedClientId)
                try {
                    store.write(updated)
                    saved = updated
                    invalidateModels()
                    message = if (revoked) "이 기기에서 로그아웃했어요" else "이 기기에서 로그아웃했어요. 서버 연결 해제를 확인하지 못했으니 ChatGPT 설정에서 앱 연결을 해제해 주세요"
                } catch (_: Exception) {
                    // Clear in-memory credentials even when a disk write fails, and don't claim durable logout.
                    saved = updated
                    invalidateModels()
                    message = "인증 정보 삭제를 저장하지 못했어요. ChatGPT 설정에서 앱 연결을 해제해 주세요"
                }
                busy = false
            }
            publish()
        }
    }

    private fun invalidateModels() {
        modelCatalog = emptyList()
        modelClientId = null
        modelsLoaded = false
        modelsLoading = false
        modelsMessage = ""
    }

    /** Account mutations are blocked while fetching/refreshing, so a rotating session has one owner. */
    fun loadModels(force: Boolean = false) {
        val account = synchronized(lock) {
            if (!loaded || busy) return
            val current = saved.active
            if (current?.connected != true || !current.planAllowed) {
                invalidateModels()
                modelsMessage = "ChatGPT 연결과 구독 한도 사용 동의가 필요해요"
                publish()
                return
            }
            if (!force && modelsLoaded && modelClientId == current.clientId) return
            busy = true
            modelsLoading = true
            modelsLoaded = false
            modelsMessage = "지원 모델 목록을 불러오는 중…"
            current
        }
        publish()
        executor.execute {
            var refreshing = false
            try {
                var current = account
                var refreshed = false
                if (current.expiresAtMs <= System.currentTimeMillis() + 60_000) {
                    refreshing = true
                    current = oauth.refresh(current, System.currentTimeMillis())
                    synchronized(lock) { saveAccount(current) }
                    refreshing = false
                    refreshed = true
                }
                val catalog = try { oauth.models(current) } catch (e: ChatGptAuthError) {
                    if (e.httpStatus != 401 || refreshed) throw e
                    refreshing = true
                    current = oauth.refresh(current, System.currentTimeMillis())
                    synchronized(lock) { saveAccount(current) }
                    refreshing = false
                    oauth.models(current)
                }
                synchronized(lock) {
                    modelClientId = current.clientId
                    modelCatalog = catalog
                    modelsLoaded = true
                    modelsMessage = when {
                        catalog.isEmpty() -> "이 계정에서 선택할 수 있는 모델이 없어요"
                        current.workModel.isNotBlank() && catalog.none { it.slug == current.workModel } -> "이전에 선택한 모델을 사용할 수 없어요. 다른 모델을 선택해 주세요"
                        current.workEffort != null && catalog.firstOrNull { it.slug == current.workModel }?.reasoningOptions?.none { it.effort == current.workEffort } == true ->
                            "이전에 선택한 추론 강도를 사용할 수 없어요. 다시 선택해 주세요"
                        else -> "지원 모델 ${catalog.size}개를 불러왔어요"
                    }
                }
            } catch (e: Exception) {
                synchronized(lock) {
                    modelsLoaded = false
                    if ((refreshing && e is ChatGptAuthError && e.httpStatus in listOf(400, 401)) ||
                        (e is ChatGptAuthError && e.httpStatus == 401)) {
                        runCatching { saveAccount(account.copy(accessToken = "", refreshToken = "", idToken = "", expiresAtMs = 0)) }
                        invalidateModels()
                        message = "ChatGPT 연결이 만료됐어요. 다시 연결해 주세요"
                        modelsMessage = message
                    } else modelsMessage = if (e is ChatGptAuthError && e.httpStatus == 403)
                        "이 계정으로 모델을 조회할 권한이 없어요. 계정 연결과 구독 한도 사용 동의를 확인해 주세요"
                    else (e as? ChatGptAuthError)?.userMessage ?: "지원 모델 목록을 불러오지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요"
                }
            } finally {
                synchronized(lock) { busy = false; modelsLoading = false }
                publish()
            }
        }
    }

    private fun saveAccount(account: ChatGptAccount) {
        val updated = ChatGptSavedAccounts(saved.hostId,
            saved.accounts.map { if (it.clientId == account.clientId) account else it }.toMutableList(), saved.activeClientId, saved.unfinishedClientId)
        store.write(updated)
        saved = updated
    }

    fun chooseModel(slug: String) = persistAction {
        val account = saved.active ?: return@persistAction
        require(modelsLoaded && modelClientId == account.clientId)
        val model = modelCatalog.first { it.slug == slug }
        val effort = account.workEffort?.takeIf { previous -> model.reasoningOptions.any { it.effort == previous } }
        saved.accounts.replaceAll { if (it.clientId == account.clientId) it.copy(workModel = slug, workEffort = effort) else it }
        modelsMessage = if (account.workEffort != null && effort == null)
            "모델을 변경했어요. 이전 추론 강도는 지원되지 않아 모델 기본값으로 바꿨어요" else "작업 모델 설정을 저장했어요"
    }

    fun chooseEffort(effort: String?) = persistAction {
        val account = saved.active ?: return@persistAction
        require(modelsLoaded && modelClientId == account.clientId)
        val model = modelCatalog.first { it.slug == account.workModel }
        require(effort == null || model.reasoningOptions.any { it.effort == effort })
        saved.accounts.replaceAll { if (it.clientId == account.clientId) it.copy(workEffort = effort) else it }
        modelsMessage = "추론 강도 설정을 저장했어요"
    }

    /** Use the account catalog's validated choice; never substitute another model. */
    fun selectedWorkRequestOptions(): org.json.JSONObject = synchronized(lock) {
        val account = saved.active ?: authFail("ChatGPT 계정을 연결해 주세요")
        if (!account.connected || !account.planAllowed || !modelsLoaded || modelClientId != account.clientId || busy)
            authFail("지원 모델 목록과 계정 연결을 확인해 주세요")
        val model = modelCatalog.firstOrNull { it.slug == account.workModel } ?: authFail("작업 모델을 선택해 주세요")
        workRequestOptions(model, account.workEffort)
    }

    /** Runs only when invoked by an app task; settings changes alone never consume a plan allowance. */
    fun performWork(instructions: String, messages: List<ChatGptWorkMessage>,
        onDelta: (String) -> Unit = {}, onResult: (Result<ChatGptResponseResult>) -> Unit,
        transport: ChatGptWorkHttp = ChatGptResponsesHttps()): ChatGptWorkCancellation {
        val cancellation = ChatGptWorkCancellation()
        val frozen = synchronized(lock) {
            val options = selectedWorkRequestOptions()
            val account = requireNotNull(saved.active)
            val body = ChatGptResponseRequest.build(options, instructions, messages)
            busy = true
            Triple(account, body, connectionRevision)
        }
        publish()
        executor.execute {
            val result = runCatching {
                cancellation.check()
                var account = frozen.first
                if (account.expiresAtMs <= System.currentTimeMillis() + 60_000) {
                    account = oauth.refresh(account, System.currentTimeMillis())
                    synchronized(lock) {
                        if (saved.active?.clientId != frozen.first.clientId || saved.active?.subject != frozen.first.subject ||
                            connectionRevision != frozen.third) authFail("계정 연결이 바뀌었어요. 다시 요청해 주세요")
                        saveAccount(account)
                    }
                }
                if (!account.connected || !account.planAllowed) authFail("ChatGPT 한도 사용 동의를 확인해 주세요")
                cancellation.check()
                // No automatic inference retries: a broken stream may already have consumed usage.
                transport.execute(account.accessToken, frozen.second, cancellation) { delta ->
                    handler.post {
                        if (runCatching { cancellation.check() }.isSuccess && synchronized(lock) {
                            saved.active?.clientId == account.clientId && saved.active?.subject == account.subject && connectionRevision == frozen.third
                        }) onDelta(delta)
                    }
                }
            }
            val safeResult = result.fold({ Result.success(it) }, { error ->
                Result.failure(if (error is ChatGptWorkError) error else
                    ChatGptWorkError("계정 연결과 모델 설정을 확인한 뒤 다시 시도해 주세요", "work_failed"))
            })
            synchronized(lock) { busy = false }
            publish()
            handler.post {
                val current = synchronized(lock) { saved.active?.clientId == frozen.first.clientId &&
                    saved.active?.subject == frozen.first.subject && connectionRevision == frozen.third }
                if (current) onResult(safeResult.mapCatching { cancellation.check(); it })
                else onResult(Result.failure(ChatGptWorkError("계정 연결이 바뀌었어요. 다시 요청해 주세요", "account_changed")))
            }
        }
        return cancellation
    }

    private fun ChatGptAccount.label() = email.ifBlank { name.ifBlank { "ChatGPT 계정" } }

    companion object {
        @Volatile private var instance: ChatGptAuth? = null
        fun get(context: Context): ChatGptAuth = instance ?: synchronized(this) {
            instance ?: ChatGptAuth(context.applicationContext).also { instance = it }
        }
    }
}
