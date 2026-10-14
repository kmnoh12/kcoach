package dev.kyungmin.kcoach.updates

import org.json.JSONObject

enum class OtaRole { PHONE, WATCH }
enum class OtaPhase { QUEUED, CHECKING, DOWNLOADING, READY, UP_TO_DATE, USER_ACTION_REQUIRED, INSTALLING, INSTALLED, FAILED }
enum class OtaOperation { CHECK, INSTALL, STATUS }

data class OtaCommand(val id: String, val senderInstance: String, val sequence: Long,
    val operation: OtaOperation, val targetInstance: String = "", val versionCode: Long = 0L, val sha256: String = "") {
    init {
        require(id.matches(OTA_ID) && senderInstance.matches(OTA_ID) && sequence > 0)
        require(targetInstance.isEmpty() || targetInstance.matches(OTA_ID))
        if (operation == OtaOperation.INSTALL) require(targetInstance.isNotEmpty() && versionCode > 0 && sha256.matches(OTA_HASH))
    }
    fun encode() = JSONObject().put("format", 1).put("id", id).put("sender", senderInstance).put("sequence", sequence)
        .put("operation", operation.name).put("target", targetInstance).put("versionCode", versionCode).put("sha256", sha256).toString()
    companion object {
        fun decode(text: String): OtaCommand {
            require(text.length <= 4096)
            val j = JSONObject(text); require(j.getInt("format") == 1)
            return OtaCommand(j.getString("id"), j.getString("sender"), j.getLong("sequence"),
                OtaOperation.valueOf(j.getString("operation")), j.optString("target"), j.optLong("versionCode"), j.optString("sha256"))
        }
    }
}

data class OtaDeviceState(val id: String, val instance: String, val revision: Long, val commandSequence: Long,
    val phase: OtaPhase, val installedCode: Long, val installedName: String, val targetCode: Long = 0,
    val targetName: String = "", val sha256: String = "", val progress: Int = 0, val message: String = "") {
    init {
        require(id.matches(OTA_ID) && instance.matches(OTA_ID) && revision > 0 && commandSequence > 0)
        require(installedCode >= 0 && targetCode >= 0 && progress in 0..100)
        require(installedName.length <= 100 && targetName.length <= 100 && message.length <= 500)
        require(sha256.isEmpty() || sha256.matches(OTA_HASH))
        if (phase in setOf(OtaPhase.READY, OtaPhase.USER_ACTION_REQUIRED, OtaPhase.INSTALLING, OtaPhase.INSTALLED)) {
            require(targetCode > 0 && sha256.matches(OTA_HASH))
        }
        if (phase == OtaPhase.INSTALLED) require(installedCode >= targetCode)
        if (phase == OtaPhase.UP_TO_DATE) require(targetCode > 0 && installedCode >= targetCode)
    }
    fun encode() = JSONObject().put("format", 1).put("id", id).put("instance", instance).put("revision", revision)
        .put("commandSequence", commandSequence).put("phase", phase.name).put("installedCode", installedCode)
        .put("installedName", installedName).put("targetCode", targetCode).put("targetName", targetName)
        .put("sha256", sha256).put("progress", progress).put("message", message).toString()
    companion object {
        fun decode(text: String): OtaDeviceState {
            require(text.length <= 8192)
            val j = JSONObject(text); require(j.getInt("format") == 1)
            return OtaDeviceState(j.getString("id"), j.getString("instance"), j.getLong("revision"), j.getLong("commandSequence"),
                OtaPhase.valueOf(j.getString("phase")), j.getLong("installedCode"), j.getString("installedName"),
                j.optLong("targetCode"), j.optString("targetName"), j.optString("sha256"), j.optInt("progress"), j.optString("message"))
        }
    }
}

internal val OTA_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
internal val OTA_HASH = Regex("[a-f0-9]{64}")

data class OtaUi(val message: String, val busy: Boolean, val readyToInstall: Boolean)

object OtaPresentation {
    fun summarize(local: OtaDeviceState?, peer: OtaDeviceState?, peerExpected: Boolean, awaitingAcceptance: Boolean): OtaUi {
        fun label(s: OtaDeviceState?) = when (s?.phase) {
            null -> "응답 대기 · 요청 보관 중"
            OtaPhase.READY -> "${s.targetName} 설치 준비됨"
            OtaPhase.UP_TO_DATE -> "최신 (${s.installedName})"
            OtaPhase.INSTALLED -> "설치 확인 (${s.installedName})"
            OtaPhase.USER_ACTION_REQUIRED -> "설치 확인 필요"
            OtaPhase.INSTALLING -> "설치 진행 · 버전 확인 대기"
            OtaPhase.DOWNLOADING -> "다운로드 ${s.progress}%"
            OtaPhase.QUEUED -> "네트워크·작업 시작 대기"
            OtaPhase.CHECKING -> "최신 버전 확인 중"
            OtaPhase.FAILED -> "실패 · ${s.message}"
        }
        val message = if (peerExpected) "폰: ${label(local)}\n워치: ${label(peer)}" else label(local)
        val working = local?.phase in setOf(OtaPhase.QUEUED, OtaPhase.CHECKING, OtaPhase.DOWNLOADING) ||
            peer?.phase in setOf(OtaPhase.QUEUED, OtaPhase.CHECKING, OtaPhase.DOWNLOADING)
        val ready = !awaitingAcceptance && (local?.phase == OtaPhase.READY || peer?.phase == OtaPhase.READY ||
            local?.phase == OtaPhase.USER_ACTION_REQUIRED || peer?.phase == OtaPhase.USER_ACTION_REQUIRED)
        return OtaUi(if (awaitingAcceptance) "$message\n워치 설치 요청 접수 대기" else message, awaitingAcceptance || (working && !ready), ready)
    }
}
