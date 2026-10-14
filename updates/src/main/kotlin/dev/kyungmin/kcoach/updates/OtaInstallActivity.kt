package dev.kyungmin.kcoach.updates

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.Executors

/** The only extra screen prepares the OS installer; it offers no independent watch update check. */
class OtaInstallActivity : Activity() {
    private lateinit var coordinator: OtaCoordinator
    private var id = ""
    private var permissionOpened = false
    private var preparing = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = OtaCoordinator.get(this)
        id = intent.getStringExtra("id").orEmpty()
        permissionOpened = savedInstanceState?.getBoolean("permissionOpened") == true
        setContentView(TextView(this).apply { text = "업데이트 설치 준비 중…"; textSize = 18f; setPadding(24, 36, 24, 24) })
        if (coordinator.store.local()?.id != id) { finish(); return }
        current = WeakReference(this)
        if (!packageManager.canRequestPackageInstalls() && !permissionOpened) {
            permissionOpened = true
            coordinator.change(id) { it.copy(phase = OtaPhase.USER_ACTION_REQUIRED, message = "앱 설치 권한 확인 필요") }
            startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:$packageName")), 7310)
        }
    }
    override fun onResume() {
        super.onResume()
        current = WeakReference(this)
        if (::coordinator.isInitialized && packageManager.canRequestPackageInstalls() && !preparing) prepare()
    }
    @Deprecated("Permission confirmation returns through the platform Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7310 && !packageManager.canRequestPackageInstalls()) {
            coordinator.change(id) { it.copy(phase = OtaPhase.FAILED, message = "설치 권한이 허용되지 않았어요") }
            finish()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("permissionOpened", permissionOpened); super.onSaveInstanceState(outState) }
    override fun onDestroy() { if (current.get() === this) current.clear(); if (::coordinator.isInitialized) coordinator.installerClosed(); super.onDestroy() }
    private fun prepare() {
        preparing = true
        executor.execute {
            runCatching {
                val state = requireNotNull(coordinator.store.local()?.takeIf { it.id == id })
                val apk = coordinator.store.apk(id); require(apk.isFile) { "업데이트 파일이 없어 다시 확인해야 해요" }
                val digest = MessageDigest.getInstance("SHA-256")
                apk.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) {
                    val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
                } }
                require(digest.digest().joinToString("") { "%02x".format(it) } == state.sha256) { "APK 파일 검증 실패" }
                val installer = packageManager.packageInstaller
                var sessionId = coordinator.store.sessionId()
                if (sessionId < 0 || installer.getSessionInfo(sessionId) == null) {
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    params.setAppPackageName(packageName)
                    if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    sessionId = installer.createSession(params)
                    coordinator.store.setSessionId(sessionId)
                    installer.openSession(sessionId).use { session -> apk.inputStream().use { input ->
                        session.openWrite("base.apk", 0, apk.length()).use { output -> input.copyTo(output); session.fsync(output) }
                    } }
                }
                coordinator.change(id) { it.copy(phase = OtaPhase.INSTALLING, message = "운영체제 설치 확인 대기") }
                val callback = Intent(this, OtaInstallReceiver::class.java).setAction("dev.kyungmin.kcoach.OTA_RESULT").putExtra("id", id)
                val pending = PendingIntent.getBroadcast(this, sessionId, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                installer.openSession(sessionId).use { it.commit(pending.intentSender) }
            }.onFailure { error ->
                coordinator.store.setSessionId(-1)
                coordinator.change(id) { it.copy(phase = OtaPhase.FAILED, message = error.message.orEmpty().take(300)) }
                runOnUiThread { finish() }
            }
        }
    }
    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        private var current = WeakReference<OtaInstallActivity>(null)
        fun showConfirmation(id: String, intent: Intent): Boolean {
            val activity = current.get()?.takeUnless { it.id != id || it.isFinishing || it.isDestroyed } ?: return false
            activity.runOnUiThread { activity.startActivity(intent); activity.finish() }
            return true
        }
    }
}
