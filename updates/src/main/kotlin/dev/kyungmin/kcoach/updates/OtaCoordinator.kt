package dev.kyungmin.kcoach.updates

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.work.*
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap

data class OtaConfig(val role: OtaRole, val manifestUrl: String)

/** App-scoped and backed by preferences/WorkManager/DataItems, rather than an Activity-owned thread. */
class OtaCoordinator private constructor(private val context: Context, val config: OtaConfig, private val link: OtaLink,
    private val schedule: ((String) -> Unit)?) {
    val store = OtaStore(context)
    private val main = Handler(Looper.getMainLooper())
    private val observers = linkedSetOf<(OtaUi) -> Unit>()
    private var foreground = WeakReference<Activity>(null)
    private var openingInstaller = false
    private val poll = object : Runnable {
        override fun run() {
            recover()
            synchronized(observers) { if (observers.isNotEmpty()) main.postDelayed(this, 5_000L) }
        }
    }
    fun installed(): Pair<Long, String> {
        @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.longVersionCode to info.versionName.orEmpty()
    }
    fun ui(): OtaUi {
        if (store.local() == null) return OtaUi(if (config.role == OtaRole.WATCH) "업데이트는 폰 앱에서 확인해 주세요" else "폰·워치 업데이트를 확인할 수 있어요", false, false)
        return OtaPresentation.summarize(store.local(), store.peer(), config.role == OtaRole.PHONE && store.command() != null, store.waitingForPeer())
    }
    fun observe(observer: (OtaUi) -> Unit): () -> Unit {
        synchronized(observers) { observers.add(observer) }
        main.post { observer(ui()) }
        main.removeCallbacks(poll); main.postDelayed(poll, 5_000L)
        return { synchronized(observers) { observers.remove(observer); if (observers.isEmpty()) main.removeCallbacks(poll) } }
    }
    fun attach(activity: Activity) {
        foreground = WeakReference(activity)
        recover()
        if (store.local()?.phase == OtaPhase.USER_ACTION_REQUIRED && store.sessionId() < 0 && config.role == OtaRole.WATCH) openInstaller()
    }
    fun detach(activity: Activity) { if (foreground.get() === activity) foreground.clear() }
    private fun emit() = main.post { synchronized(observers) { observers.toList() }.forEach { it(ui()) } }
    fun checkBoth() {
        require(config.role == OtaRole.PHONE)
        if (ui().busy) return
        val id = UUID.randomUUID().toString()
        val command = OtaCommand(id, store.instance(), store.nextSequence(), OtaOperation.CHECK)
        val (code, name) = installed()
        store.begin(command, OtaDeviceState(id, store.instance(), 1, command.sequence, OtaPhase.QUEUED, code, name))
        link.command(command); enqueue(id); emit()
    }
    fun installBoth(activity: Activity): String? {
        require(config.role == OtaRole.PHONE)
        foreground = WeakReference(activity)
        val peer = store.peer()
        val selfReady = store.local()?.phase in setOf(OtaPhase.READY, OtaPhase.USER_ACTION_REQUIRED)
        if (peer?.phase in setOf(OtaPhase.READY, OtaPhase.USER_ACTION_REQUIRED)) {
            val old = store.command() ?: return "업데이트를 먼저 확인해 주세요"
            val command = OtaCommand(old.id, store.instance(), store.nextSequence(), OtaOperation.INSTALL,
                peer!!.instance, peer.targetCode, peer.sha256)
            store.saveCommand(command); store.waitForPeer(selfReady)
            link.command(command); emit()
            return "워치 설치 요청을 보관했어요. 워치의 설치 확인을 진행해 주세요"
        }
        if (selfReady) { openInstaller(); return "폰 설치 확인을 진행해 주세요" }
        return "설치할 새 버전이 없어요"
    }
    fun receive(path: String, text: String, node: String) {
        if (path == OtaDataLayer.COMMAND_PATH && config.role == OtaRole.WATCH) {
            val command = OtaCommand.decode(text)
            if (command.targetInstance.isNotEmpty() && command.targetInstance != store.instance()) return
            if (!store.receiveCommand(command)) {
                store.local()?.let(link::state)
                return
            }
            when (command.operation) {
                OtaOperation.CHECK -> {
                    val (code, name) = installed()
                    store.begin(command, OtaDeviceState(command.id, store.instance(), 1, command.sequence, OtaPhase.QUEUED, code, name))
                    link.state(requireNotNull(store.local())); enqueue(command.id)
                }
                OtaOperation.INSTALL -> {
                    val current = store.local() ?: return
                    require(current.id == command.id && current.targetCode == command.versionCode && current.sha256 == command.sha256)
                    if (current.phase == OtaPhase.INSTALLED) { link.state(current); return }
                    require(current.phase in setOf(OtaPhase.READY, OtaPhase.USER_ACTION_REQUIRED, OtaPhase.INSTALLING))
                    store.saveCommand(command)
                    change(command.id) { it.copy(commandSequence = command.sequence, phase = OtaPhase.USER_ACTION_REQUIRED,
                        message = "폰에서 설치 요청됨 · 워치에서 설치 확인") }
                    main.post { openInstaller(allowExistingSession = true) }
                }
                OtaOperation.STATUS -> recover()
            }
            emit()
        } else if (path == OtaDataLayer.STATE_PATH && config.role == OtaRole.PHONE) {
            val state = OtaDeviceState.decode(text)
            if (!store.acceptPeer(state, node)) return
            val command = store.command()
            if (store.waitingForPeer() && command?.operation == OtaOperation.INSTALL && state.commandSequence == command.sequence &&
                state.phase in setOf(OtaPhase.USER_ACTION_REQUIRED, OtaPhase.INSTALLING, OtaPhase.INSTALLED)) {
                store.waitForPeer(false); main.post { openInstaller() }
            }
            if (store.waitingForPeer() && command?.sequence == state.commandSequence && state.phase == OtaPhase.FAILED) store.waitForPeer(false)
            emit()
        }
    }
    fun change(id: String, mutate: (OtaDeviceState) -> OtaDeviceState): OtaDeviceState? = synchronized(this) {
        val previous = store.local()?.takeIf { it.id == id } ?: return null
        val (code, name) = installed()
        val next = mutate(previous).copy(revision = previous.revision + 1, installedCode = code, installedName = name)
        if (!store.saveLocal(next)) return null
        if (config.role == OtaRole.WATCH) link.state(next)
        emit(); next
    }
    fun recover() {
        val local = store.local()
        if (local != null) {
            val (code, _) = installed()
            if (local.targetCode > 0 && code >= local.targetCode && local.phase in setOf(OtaPhase.READY, OtaPhase.USER_ACTION_REQUIRED, OtaPhase.INSTALLING)) {
                change(local.id) { it.copy(phase = OtaPhase.INSTALLED, installedCode = code, message = "실제 설치 버전 확인") }
                store.waitForPeer(false)
            }
            if (local.phase in setOf(OtaPhase.QUEUED, OtaPhase.CHECKING, OtaPhase.DOWNLOADING)) enqueue(local.id)
            if (config.role == OtaRole.WATCH) store.local()?.let(link::state)
        }
        if (config.role == OtaRole.PHONE) store.command()?.let(link::command)
        link.recover(::receive)
    }
    private fun enqueue(id: String) {
        if (schedule != null) { schedule.invoke(id); return }
        val request = OneTimeWorkRequestBuilder<OtaDownloadWorker>().setInputData(workDataOf("id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        WorkManager.getInstance(context).enqueueUniqueWork("kcoach-ota-$id", ExistingWorkPolicy.KEEP, request)
    }
    fun confirmInstall(activity: Activity) { foreground = WeakReference(activity); openInstaller(allowExistingSession = true) }
    internal fun installerClosed() { openingInstaller = false }
    internal fun openInstaller(allowExistingSession: Boolean = false) {
        val state = store.local() ?: return
        if (openingInstaller || state.phase !in setOf(OtaPhase.READY, OtaPhase.USER_ACTION_REQUIRED) || (!allowExistingSession && store.sessionId() >= 0)) return
        val activity = foreground.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (activity == null) { OtaNotifications.offerInstall(context, state); return }
        openingInstaller = true
        runCatching { activity.startActivity(Intent(activity, OtaInstallActivity::class.java).putExtra("id", state.id)) }
            .onFailure { openingInstaller = false; change(state.id) { s -> s.copy(phase = OtaPhase.FAILED, message = "설치 화면을 열 수 없어요") } }
    }
    companion object {
        private val instances = WeakHashMap<Context, OtaCoordinator>()
        fun initialize(context: Context, config: OtaConfig, link: OtaLink = OtaDataLayer(context.applicationContext),
            schedule: ((String) -> Unit)? = null): OtaCoordinator = synchronized(instances) {
            val app = context.applicationContext
            check(app.getSharedPreferences("companion-ota-config", Context.MODE_PRIVATE).edit()
                .putString("role", config.role.name).putString("manifest", config.manifestUrl).commit())
            instances[app] ?: OtaCoordinator(app, config, link, schedule).also { instances[app] = it }
        }
        fun get(context: Context): OtaCoordinator = synchronized(instances) {
            val app = context.applicationContext
            instances[app] ?: run {
                // WorkManager can recover persisted jobs during provider startup, before Application.onCreate.
                val prefs = app.getSharedPreferences("companion-ota-config", Context.MODE_PRIVATE)
                initialize(app, OtaConfig(OtaRole.valueOf(requireNotNull(prefs.getString("role", null))),
                    requireNotNull(prefs.getString("manifest", null))))
            }
        }
    }
}
