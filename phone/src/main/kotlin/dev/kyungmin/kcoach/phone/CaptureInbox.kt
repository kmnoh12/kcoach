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
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

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
    fun receive(manifest: WireEnvelope, bytes: ByteArray): Result<ReceivedCapture> = synchronized(lock) {
        val captureId = manifest.payload[WirePayloadKeys.CAPTURE_ID].orEmpty()
        val existed = prefs.contains(pathKey(captureId))
        runCatching {
            require(captureId.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "invalid capture id" }
            require(bytes.size <= CaptureValidator.MAX_CAPTURE_BYTES) { "capture exceeds size limit" }
            if (existed) {
                val original = WireCodec.decode(prefs.getString(manifestKey(captureId), null) ?: error("missing original manifest"))
                require(original.sessionId == manifest.sessionId && original.setId == manifest.setId &&
                    original.exerciseSessionId == manifest.exerciseSessionId && original.payload == manifest.payload) { "capture id already belongs to another immutable record" }
                require(dev.kyungmin.kcoach.core.SensorCaptureFormat.sha256(bytes) == original.payload[WirePayloadKeys.SHA256]) { "repeated capture content changed" }
                val existing = captureFile(captureId)
                if (existing.isFile) {
                    val validated = runCatching { CaptureValidator.validate(manifest, readCaptureBytes(existing)) }.getOrNull()
                    if (validated != null) return@runCatching ReceivedCapture(manifest, validated, existing)
                    CaptureValidator.validate(manifest, bytes) // Repair only with the immutable original bytes.
                }
            }
            val directory = File(context.filesDir, "captures").also { check(it.mkdirs() || it.isDirectory) }
            val rawFile = File(directory, "$captureId.kcs")
            atomicWrite(rawFile, bytes)
            check(prefs.edit().putString(pathKey(captureId), rawFile.absolutePath)
                .putString(manifestKey(captureId), WireCodec.encode(manifest))
                .putString(stateKey(captureId), CaptureState.RECEIVED.name)
                .remove(errorKey(captureId))
                .putLong(receivedAtKey(captureId), System.currentTimeMillis()).commit()) { "failed to persist raw capture inbox metadata" }
            ReceivedCapture(manifest, CaptureValidator.validate(manifest, bytes), rawFile)
        }.onFailure { error ->
            if (!existed && captureId.matches(Regex("[A-Za-z0-9._-]{1,160}"))) prefs.edit()
                .putString(stateKey(captureId), CaptureState.ANALYSIS_FAILED.name)
                .putString(errorKey(captureId), "validation:${error.message}").commit()
        }
    }

    fun markState(captureId: String, state: CaptureState, error: String? = null): Boolean = synchronized(lock) {
        val edit = prefs.edit().putString(stateKey(captureId), state.name)
        if (error == null) edit.remove(errorKey(captureId)) else edit.putString(errorKey(captureId), error)
        edit.commit()
    }

    fun markAnalysisAttempted(captureId: String, succeeded: Boolean): Boolean = synchronized(lock) {
        prefs.edit()
            .putBoolean(analysisAttemptedKey(captureId), true)
            .putBoolean(analysisSucceededKey(captureId), succeeded)
            .commit()
    }

    fun analysisAttempted(captureId: String): Boolean =
        prefs.getBoolean(analysisAttemptedKey(captureId), false)

    fun analysisSucceeded(captureId: String): Boolean =
        prefs.getBoolean(analysisSucceededKey(captureId), false)

    /**
     * Reconstructs raw captures that were durably committed but whose analysis
     * did not finish before the listener service stopped.
     */
    fun pendingAnalysis(limit: Int = 1, excluded: Set<String> = emptySet()): List<ReceivedCapture> = synchronized(lock) { prefs.all.keys
        .asSequence()
        .filter { it.startsWith("capture:") && it.endsWith(":path") }
        .map { it.removePrefix("capture:").removeSuffix(":path") }
        .filterNot { it in excluded || analysisSucceeded(it) || prefs.getString(errorKey(it), "").orEmpty().startsWith("validation:") }
        .mapNotNull { captureId ->
            runCatching {
                val encodedManifest = prefs.getString(manifestKey(captureId), null) ?: return@runCatching null
                val rawFile = captureFile(captureId)
                if (!rawFile.isFile) return@runCatching null
                val manifest = WireCodec.decode(encodedManifest)
                val validated = CaptureValidator.validate(manifest, readCaptureBytes(rawFile))
                ReceivedCapture(manifest, validated, rawFile)
            }.getOrNull()
        }
        .take(limit.coerceIn(1, 2)).toList()
    }

    fun state(captureId: String): CaptureState? =
        prefs.getString(stateKey(captureId), null)?.let { name ->
            CaptureState.entries.firstOrNull { it.name == name }
        }

    private fun captureFile(id: String): File {
        val file = File(prefs.getString(pathKey(id), null) ?: error("missing capture path")).canonicalFile
        require(file.parentFile == File(context.filesDir, "captures").canonicalFile) { "capture path outside inbox" }
        require(file.name == "$id.kcs" || file.name == "$id.kcs.gz") { "invalid capture file name" }
        return file
    }

    private fun readCaptureBytes(file: File, compressed: Boolean = file.name.endsWith(".gz")): ByteArray {
        val stream = if (compressed) GZIPInputStream(file.inputStream()) else file.inputStream()
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= CaptureValidator.MAX_CAPTURE_BYTES) { "capture exceeds size limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    /** Keep every original sample; replace only a verified, completed and acknowledged copy. */
    fun compressSuccessfulCaptures(limit: Int = 1): Int = synchronized(lock) {
        var compressedCount = 0
        val candidates = prefs.all.keys.filter { it.startsWith("capture:") && it.endsWith(":path") }
            .map { it.removePrefix("capture:").removeSuffix(":path") }
            .filter { analysisSucceeded(it) && prefs.getBoolean("capture:$it:acked", false) && prefs.contains("analysis:$it") }
            .filter { !prefs.getBoolean("capture:$it:compressionSkipped", false) && System.currentTimeMillis() - prefs.getLong("capture:$it:compressionAttemptAt", 0L) >= 300_000L }
            .filter { prefs.getString(pathKey(it), "").orEmpty().endsWith(".kcs") }.take(limit.coerceIn(1, 2))
        candidates.forEach { id ->
            runCatching {
                val original = captureFile(id)
                val bytes = readCaptureBytes(original)
                val manifest = WireCodec.decode(prefs.getString(manifestKey(id), null) ?: error("missing manifest"))
                CaptureValidator.validate(manifest, bytes)
                val destination = File(original.parentFile, "${original.name}.gz")
                val temp = File(original.parentFile, "${original.name}.gz.incoming")
                FileOutputStream(temp).use { output -> GZIPOutputStream(output).use { gzip ->
                    gzip.write(bytes); gzip.finish(); gzip.flush(); output.fd.sync()
                } }
                if (temp.length() >= original.length()) { temp.delete(); prefs.edit().putBoolean("capture:$id:compressionSkipped", true).commit(); return@runCatching }
                check(bytes.contentEquals(readCaptureBytes(temp, true))) { "compressed capture content changed" }
                check(temp.renameTo(destination)) { "failed to commit compressed capture" }
                check(prefs.edit().putString(pathKey(id), destination.absolutePath).commit()) { "failed to save compressed capture path" }
                if (!original.delete()) android.util.Log.w("KCoachEvent", "verified compressed copy retained; duplicate raw file remains id=$id")
                compressedCount++
            }.onFailure { error -> prefs.edit().putLong("capture:$id:compressionAttemptAt", System.currentTimeMillis()).commit(); android.util.Log.w("KCoachEvent", "capture compression deferred id=$id", error) }
        }
        compressedCount
    }

    fun storageReport(): JSONObject = synchronized(lock) {
        val ids = prefs.all.keys.filter { it.startsWith("capture:") && it.endsWith(":path") }
            .map { it.removePrefix("capture:").removeSuffix(":path") }
        val files = File(context.filesDir, "captures").listFiles().orEmpty().filter { it.isFile }
        val referenced = ids.mapNotNull { runCatching { captureFile(it).name }.getOrNull() }.toSet()
        val snapshots = context.getSharedPreferences("finished-workouts", Context.MODE_PRIVATE).all.values
            .sumOf { it.toString().toByteArray(Charsets.UTF_8).size.toLong() }
        val report = JSONObject().put("generatedAtMs", System.currentTimeMillis()).put("captures", ids.size)
            .put("storedBytes", files.sumOf { it.length() }).put("orphanBytes", files.filter { it.name !in referenced }.sumOf { it.length() })
            .put("protectedCaptures", ids.count { !analysisSucceeded(it) || !prefs.getBoolean("capture:$it:acked", false) })
            .put("failedCaptures", ids.count { state(it) == CaptureState.ANALYSIS_FAILED })
            .put("missingFiles", ids.count { runCatching { !captureFile(it).isFile }.getOrDefault(true) })
            .put("finishedSnapshotBytesEstimate", snapshots)
        atomicWrite(File(context.filesDir, "capture-storage.json"), report.toString().toByteArray(Charsets.UTF_8))
        report
    }

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        val temp = File(destination.parentFile, "${destination.name}.incoming")
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(temp.renameTo(destination)) { "failed to commit raw capture" }
    }

    companion object {
        private val lock = Any()
        fun pathKey(id: String) = "capture:$id:path"
        fun manifestKey(id: String) = "capture:$id:manifest"
        fun stateKey(id: String) = "capture:$id:state"
        fun receivedAtKey(id: String) = "capture:$id:receivedAt"
        fun errorKey(id: String) = "capture:$id:error"
        fun analysisAttemptedKey(id: String) = "capture:$id:analysisAttempted"
        fun analysisSucceededKey(id: String) = "capture:$id:analysisSucceeded"
    }
}
