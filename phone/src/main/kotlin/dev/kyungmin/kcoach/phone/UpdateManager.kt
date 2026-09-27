package dev.kyungmin.kcoach.phone

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

data class UpdateState(
    val message: String,
    val busy: Boolean = false,
    val readyToInstall: Boolean = false,
)

private data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
)

class UpdateManager(
    private val activity: Activity,
    private val manifestUrl: String,
) {
    private val updateDirectory = File(activity.cacheDir, "updates")
    private val updateFile = File(updateDirectory, "kcoach-update.apk")
    private val manifestUrls = listOf(
        "http://192.168.200.105:8765/update.json",
        "http://192.168.200.106:8765/update.json",
        manifestUrl,
        "http://gyeongmin-ui-MacBook-Neo.local:8765/update.json",
        "http://10.0.2.2:8765/update.json",
    ).distinct()
    private var pendingInstall = false

    fun checkAndDownload(onState: (UpdateState) -> Unit) {
        Thread {
            runCatching {
                onState(UpdateState("최신 버전 확인 중…", busy = true))
                val manifest = fetchManifest()
                if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                    updateFile.delete()
                    return@runCatching UpdateState("이미 최신 버전 ${BuildConfig.VERSION_NAME}이야")
                }

                onState(UpdateState("${manifest.versionName} 다운로드 중…", busy = true))
                downloadAndVerify(manifest)
                UpdateState(
                    message = "${manifest.versionName} 다운로드 완료",
                    readyToInstall = true,
                )
            }.getOrElse { error ->
                updateFile.delete()
                UpdateState("업데이트 실패: ${error.message ?: "알 수 없는 오류"}")
            }.also(onState)
        }.start()
    }

    fun requestInstall(): String? {
        if (!updateFile.isFile) return "다운로드된 업데이트가 없어"
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pendingInstall = true
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${activity.packageName}".toUri(),
                ),
            )
            return "KCoach의 앱 설치 권한을 허용해"
        }
        launchInstaller()
        return null
    }

    fun resumePendingInstall(): String? {
        if (!pendingInstall) return null
        if (!activity.packageManager.canRequestPackageInstalls()) {
            return "설치 권한이 아직 허용되지 않았어"
        }
        pendingInstall = false
        launchInstaller()
        return "안드로이드 설치 화면을 열었어"
    }

    private fun fetchManifest(): UpdateManifest {
        var lastError: Throwable? = null
        for (location in manifestUrls) {
            try {
                val connection = openConnection(location)
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val apkUrl = json.getString("apkUrl")
                requireSameHost(location, apkUrl)
                return UpdateManifest(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.getString("versionName"),
                    apkUrl = apkUrl,
                    sha256 = json.getString("sha256").lowercase(),
                )
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("업데이트 서버를 찾지 못했어")
    }

    private fun downloadAndVerify(manifest: UpdateManifest) {
        updateDirectory.mkdirs()
        val temporaryFile = File(updateDirectory, "kcoach-update.part")
        temporaryFile.delete()
        val connection = openConnection(manifest.apkUrl)
        connection.inputStream.use { input ->
            temporaryFile.outputStream().use { output -> input.copyTo(output) }
        }
        val actualHash = temporaryFile.sha256()
        require(actualHash == manifest.sha256) {
            "파일 검증 실패"
        }
        updateFile.delete()
        check(temporaryFile.renameTo(updateFile)) { "업데이트 파일 저장 실패" }
    }

    private fun launchInstaller() {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.updates",
            updateFile,
        )
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            connect()
            check(responseCode == HttpURLConnection.HTTP_OK) {
                "서버 응답 $responseCode"
            }
        }
    }

    private fun requireSameHost(manifestLocation: String, apkLocation: String) {
        val manifestUri = URI(manifestLocation)
        val apkUri = URI(apkLocation)
        require(apkUri.scheme == "https" || apkUri.scheme == "http") {
            "허용되지 않은 다운로드 주소"
        }
        require(
            manifestUri.host.equals(apkUri.host, ignoreCase = true) &&
                manifestUri.port == apkUri.port,
        ) {
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
