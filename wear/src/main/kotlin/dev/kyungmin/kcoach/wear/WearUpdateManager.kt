package dev.kyungmin.kcoach.wear

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest

data class WearUpdateState(
    val message: String,
    val busy: Boolean = false,
    val readyToInstall: Boolean = false,
)

private data class WearUpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
)

class WearUpdateManager(
    private val activity: Activity,
    private val manifestUrl: String,
) {
    private val updateDirectory = File(activity.cacheDir, "updates")
    private val updateFile = File(updateDirectory, "kcoach-wear-update.apk")
    private val manifestUrls = listOf(
        manifestUrl,
        "http://gyeongmin-ui-MacBook-Neo.local:8765/update-watch.json",
        "http://192.168.200.105:8765/update-watch.json",
        "http://192.168.200.106:8765/update-watch.json",
        "http://10.0.2.2:8765/update-watch.json",
    ).distinct()
    private var pendingInstall = false

    fun checkAndDownload(onState: (WearUpdateState) -> Unit) {
        Thread {
            runCatching {
                onState(WearUpdateState("최신 워치 버전 확인 중…", busy = true))
                withDownloadLocks {
                    val manifest = fetchManifest()
                    if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                        updateFile.delete()
                        return@withDownloadLocks WearUpdateState("이미 최신 워치 버전 ${BuildConfig.VERSION_NAME}이야")
                    }
                    onState(WearUpdateState("${manifest.versionName} 워치 APK 다운로드 중…", busy = true))
                    downloadAndVerify(manifest)
                    WearUpdateState("${manifest.versionName} 다운로드 완료 · 설치 준비됨", readyToInstall = true)
                }
            }.getOrElse { error ->
                updateFile.delete()
                WearUpdateState("업데이트 실패: ${error.message ?: "알 수 없는 오류"}")
            }.also(onState)
        }.start()
    }

    fun requestInstall(): String? {
        if (!updateFile.isFile) return "다운로드된 워치 업데이트가 없어"
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pendingInstall = true
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${activity.packageName}".toUri(),
                ),
            )
            return "KCoach의 앱 설치 권한을 워치 설정에서 허용해"
        }
        launchInstaller()
        return null
    }

    fun resumePendingInstall(): String? {
        if (!pendingInstall) return null
        if (!activity.packageManager.canRequestPackageInstalls()) return "설치 권한이 아직 허용되지 않았어"
        pendingInstall = false
        launchInstaller()
        return "워치 설치 화면을 열었어"
    }

    private fun fetchManifest(): WearUpdateManifest {
        var lastError: Throwable? = null
        for (location in manifestUrls) {
            try {
                val connection = openConnection(location)
                val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                val apkUrl = json.getString("apkUrl")
                requireSameHost(location, apkUrl)
                return WearUpdateManifest(
                    json.getInt("versionCode"),
                    json.getString("versionName"),
                    apkUrl,
                    json.getString("sha256").lowercase(),
                )
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("업데이트 서버를 찾지 못했어")
    }

    private fun downloadAndVerify(manifest: WearUpdateManifest) {
        updateDirectory.mkdirs()
        val temporaryFile = File(updateDirectory, "kcoach-wear-update.part")
        var lastError: Throwable? = null
        for (attempt in 0 until 3) {
            try {
                temporaryFile.delete()
                val connection = openConnection(manifest.apkUrl)
                connection.inputStream.use { input ->
                    temporaryFile.outputStream().use { output -> input.copyTo(output) }
                }
                require(temporaryFile.sha256() == manifest.sha256) { "워치 APK 파일 검증 실패" }
                updateFile.delete()
                check(temporaryFile.renameTo(updateFile)) { "워치 업데이트 파일 저장 실패" }
                return
            } catch (error: Throwable) {
                lastError = error
                temporaryFile.delete()
                if (attempt < 2) Thread.sleep(1_500)
            }
        }
        throw lastError ?: IllegalStateException("워치 APK 다운로드 실패")
    }

    private fun <T> withDownloadLocks(block: () -> T): T {
        val context = activity.applicationContext
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KCoach:OTA")
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KCoach:OTA")
        wifiLock.setReferenceCounted(false)
        wakeLock.setReferenceCounted(false)
        wifiLock.acquire()
        // The Watch can download large debug APKs very slowly on weak Wi-Fi.
        // Keep the CPU awake for the scoped transfer instead of expiring after
        // three minutes and cutting the response stream short.
        wakeLock.acquire()
        return try {
            block()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
        }
    }

    private fun launchInstaller() {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", updateFile)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            connect()
            check(responseCode == HttpURLConnection.HTTP_OK) { "서버 응답 $responseCode" }
        }
    }

    private fun requireSameHost(manifestLocation: String, apkLocation: String) {
        val manifestUri = URI(manifestLocation)
        val apkUri = URI(apkLocation)
        require(apkUri.scheme == "https" || apkUri.scheme == "http") { "허용되지 않은 다운로드 주소" }
        val sameHost = manifestUri.host.equals(apkUri.host, ignoreCase = true)
        val localDiscoveryToLanAddress = manifestUri.host.endsWith(".local", ignoreCase = true) &&
            InetAddress.getByName(apkUri.host).isSiteLocalAddress
        require(manifestUri.port == apkUri.port && (sameHost || localDiscoveryToLanAddress)) {
            "업데이트 서버 주소 불일치"
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
