package dev.kyungmin.kcoach.phone

import android.content.Context
import android.content.SharedPreferences
import dev.kyungmin.kcoach.core.CaptureState
import dev.kyungmin.kcoach.core.CaptureValidator
import dev.kyungmin.kcoach.core.ValidatedCapture
import dev.kyungmin.kcoach.core.WireCodec
import dev.kyungmin.kcoach.core.WireEnvelope
import dev.kyungmin.kcoach.core.WirePayloadKeys
import java.io.File
import java.io.FileOutputStream

data class ReceivedCapture(
    val manifest: WireEnvelope,
    val validated: ValidatedCapture,
    val rawFile: File,
)

/**
 * Durable phone-side inbox. Raw bytes are atomically committed before parsing
 * and analysis, so a parser/analyzer failure never destroys the original set.
 */
class CaptureInbox(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    @Synchronized
    fun receive(manifest: WireEnvelope, bytes: ByteArray): Result<ReceivedCapture> = runCatching {
        val captureId = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty()
        require(captureId.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "invalid capture id" }
        require(bytes.size <= CaptureValidator.MAX_CAPTURE_BYTES) { "capture exceeds size limit" }

        val directory = File(context.filesDir, "captures").also { check(it.mkdirs() || it.isDirectory) }
        val rawFile = File(directory, "$captureId.kcs")
        atomicWrite(rawFile, bytes)
        check(
            prefs.edit()
                .putString(pathKey(captureId), rawFile.absolutePath)
                .putString(manifestKey(captureId), WireCodec.encode(manifest))
                .putString(stateKey(captureId), CaptureState.RECEIVED.name)
                .putLong(receivedAtKey(captureId), System.currentTimeMillis())
                .commit(),
        ) { "failed to persist raw capture inbox metadata" }

        val validated = CaptureValidator.validate(manifest, bytes)
        ReceivedCapture(manifest, validated, rawFile)
    }.onFailure { error ->
        val captureId = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty()
        if (captureId.isNotBlank()) {
            prefs.edit()
                .putString(stateKey(captureId), CaptureState.ANALYSIS_FAILED.name)
                .putString(errorKey(captureId), "validation:${error.message}")
                .commit()
        }
    }

    @Synchronized
    fun markState(captureId: String, state: CaptureState, error: String? = null): Boolean {
        val edit = prefs.edit().putString(stateKey(captureId), state.name)
        if (error == null) edit.remove(errorKey(captureId)) else edit.putString(errorKey(captureId), error)
        return edit.commit()
    }

    @Synchronized
    fun markAnalysisAttempted(captureId: String, succeeded: Boolean): Boolean =
        prefs.edit()
            .putBoolean(analysisAttemptedKey(captureId), true)
            .putBoolean(analysisSucceededKey(captureId), succeeded)
            .commit()

    fun analysisAttempted(captureId: String): Boolean =
        prefs.getBoolean(analysisAttemptedKey(captureId), false)

    fun analysisSucceeded(captureId: String): Boolean =
        prefs.getBoolean(analysisSucceededKey(captureId), false)

    /**
     * Reconstructs raw captures that were durably committed but whose analysis
     * did not finish before the listener service stopped.
     */
    @Synchronized
    fun pendingAnalysis(): List<ReceivedCapture> = prefs.all.keys
        .asSequence()
        .filter { it.startsWith("capture:") && it.endsWith(":path") }
        .map { it.removePrefix("capture:").removeSuffix(":path") }
        .filterNot(::analysisAttempted)
        .mapNotNull { captureId ->
            runCatching {
                val path = prefs.getString(pathKey(captureId), null) ?: return@runCatching null
                val encodedManifest = prefs.getString(manifestKey(captureId), null) ?: return@runCatching null
                val rawFile = File(path)
                if (!rawFile.isFile) return@runCatching null
                val manifest = WireCodec.decode(encodedManifest)
                val validated = CaptureValidator.validate(manifest, rawFile.readBytes())
                ReceivedCapture(manifest, validated, rawFile)
            }.getOrNull()
        }
        .toList()

    fun state(captureId: String): CaptureState? =
        prefs.getString(stateKey(captureId), null)?.let { name ->
            CaptureState.entries.firstOrNull { it.name == name }
        }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val temp = File(destination.parentFile, "${destination.name}.incoming")
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (destination.exists() && !destination.delete()) {
            error("failed to replace existing capture")
        }
        check(temp.renameTo(destination)) { "failed to commit raw capture" }
    }

    companion object {
        fun pathKey(id: String) = "capture:$id:path"
        fun manifestKey(id: String) = "capture:$id:manifest"
        fun stateKey(id: String) = "capture:$id:state"
        fun receivedAtKey(id: String) = "capture:$id:receivedAt"
        fun errorKey(id: String) = "capture:$id:error"
        fun analysisAttemptedKey(id: String) = "capture:$id:analysisAttempted"
        fun analysisSucceededKey(id: String) = "capture:$id:analysisSucceeded"
    }
}
