package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class CalibrationEvidenceExportReceipt(val captureId: String, val zipSha256: String, val videoIncluded: Boolean)

/** Writes only the selected capture to a user-selected output. No network, account preferences or cache copies. */
class CalibrationEvidenceExport(private val context: Context) {
    fun write(captureId: String, includeVideo: Boolean, destination: OutputStream): Result<CalibrationEvidenceExportReceipt> = runCatching {
        val captures = CalibrationCaptureStore(context)
        val reviews = CalibrationReviewStore(context)
        val clocks = CaptureClockStore(context)
        val capture = captures.load(captureId).getOrThrow()
        require(capture.status != CalibrationCaptureStatus.RECORDING) { "Stop recording before exporting" }
        val sensor = captures.sensor(captureId).getOrThrow() // validates immutable original SHA and sensor format
        val review = reviews.load(captureId).getOrThrow()
        val frames = captures.frames(captureId).getOrThrow()
        val clockSamples = listOf("START", "MIDDLE", "END").associateWith { clocks.samples(captureId, it) }
        val video = captures.videoFile(captureId)
        if (includeVideo) require(video.isFile && video.length() in 1..MAX_VIDEO_BYTES) { "Video missing or too large" }
        val exportManifest = sensor.manifest.copy(payload = sensor.manifest.payload.filterKeys { it in SENSOR_FIELDS })
        val zipDigest = MessageDigest.getInstance("SHA-256")
        val entries = JSONArray()
        var totalBytes = 0L
        val output = object : FilterOutputStream(destination) {
            override fun write(value: Int) { out.write(value); zipDigest.update(value.toByte()) }
            override fun write(bytes: ByteArray, offset: Int, count: Int) { out.write(bytes, offset, count); zipDigest.update(bytes, offset, count) }
        }
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, maximumBytes: Long, body: (OutputStream) -> Unit): String {
                zip.putNextEntry(ZipEntry(name))
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                val sink = object : OutputStream() {
                    override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        count += length; totalBytes += length
                        require(count <= maximumBytes && totalBytes <= MAX_TOTAL_BYTES) { "Export size limit exceeded" }
                        zip.write(bytes, offset, length); digest.update(bytes, offset, length)
                    }
                }
                body(sink); zip.closeEntry()
                val hash = hex(digest.digest())
                entries.put(JSONObject().put("path", name).put("bytes", count).put("sha256", hash))
                return hash
            }
            fun json(name: String, value: JSONObject) = entry(name, 4_000_000) { it.write(value.toString().toByteArray(Charsets.UTF_8)) }
            val rawHash = entry("watch.kcs", CaptureValidator.MAX_CAPTURE_BYTES.toLong()) { sink ->
                val source = if (sensor.rawFile.name.endsWith(".gz")) GZIPInputStream(sensor.rawFile.inputStream()) else sensor.rawFile.inputStream()
                source.use { it.copyTo(sink, 8192) }
            }
            require(rawHash == sensor.manifest.payload.getValue(WirePayloadKeys.SHA256)) { "Original changed while exporting" }
            entry("watch.manifest", 100_000) { it.write(WireCodec.encode(exportManifest).toByteArray(Charsets.UTF_8)) }
            json("capture.json", JSONObject().put("schemaVersion", 1).put("id", capture.id).put("exerciseId", capture.exerciseId)
                .put("exerciseName", capture.exerciseName).put("setId", capture.setId).put("attemptId", capture.attemptId)
                .put("createdAtMs", capture.createdAtMs).put("status", capture.status.name).put("videoComplete", capture.videoComplete)
                .put("interruptionReason", capture.interruptionReason ?: JSONObject.NULL).put("watchOnLeft", capture.watchOnLeft)
                .put("watchOrientation", capture.watchOrientation).put("phoneClockId", capture.phoneClockId))
            entry("camera-frames.jsonl", 120_000_000) { sink -> frames.forEach {
                sink.write((CameraFrameCodec.encode(it).toString() + "\n").toByteArray(Charsets.UTF_8))
            } }
            val reviewFile = File(context.filesDir, "calibration-captures/$captureId/review-${review.revision}.json")
            val reviewJson = if (review.revision > 0) JSONObject(reviewFile.readText()).also {
                require(it.getString("captureId") == captureId && it.getLong("revision") == review.revision)
            } else JSONObject().put("schemaVersion", 1).put("captureId", captureId).put("revision", 0)
                .put("count", JSONObject.NULL).put("cameraCycles", JSONArray()).put("references", JSONArray())
            json("review.json", reviewJson)
            val clockJson = JSONObject().put("schemaVersion", 1)
            clockSamples.forEach { (window, values) -> clockJson.put(window, JSONArray(values.map { sample ->
                JSONObject().put("phoneClockId", sample.phoneClockId).put("watchClockId", sample.watchClockId)
                    .put("epochsVerified", sample.epochsVerified).put("phoneSendNs", sample.exchange.phoneSendNs)
                    .put("watchReceiveNs", sample.exchange.watchReceiveNs).put("watchSendNs", sample.exchange.watchSendNs)
                    .put("phoneReceiveNs", sample.exchange.phoneReceiveNs)
            })) }
            json("clocks.json", clockJson)
            if (includeVideo) entry("reference.mp4", MAX_VIDEO_BYTES) { sink -> video.inputStream().use { it.copyTo(sink, 8192) } }
            require(captures.load(captureId).getOrThrow() == capture && reviews.load(captureId).getOrThrow() == review &&
                captures.sensor(captureId).getOrThrow().manifest == sensor.manifest &&
                clockSamples.all { (window, samples) -> clocks.samples(captureId, window) == samples }) { "Selected evidence changed; export again" }
            val index = JSONObject().put("schemaVersion", 1).put("captureId", captureId).put("createdAtMs", System.currentTimeMillis())
                .put("videoIncluded", includeVideo).put("videoComplete", capture.videoComplete).put("deviceAcceptanceVerified", false)
                .put("scope", "SELECTED_CAPTURE_DIAGNOSTIC_EVIDENCE").put("manifestPayloadFiltered", true)
                .put("originalWireManifestSha256", SensorCaptureFormat.sha256(WireCodec.encode(sensor.manifest).toByteArray(Charsets.UTF_8)))
                .put("rawSha256Verified", true).put("entries", entries)
                .put("note", "Export integrity is not proof of Watch8 accuracy, posture quality, or completed physical acceptance.")
            // The index hashes the preceding entries; it does not claim a self-referential hash.
            zip.putNextEntry(ZipEntry("evidence-manifest.json")); zip.write(index.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        CalibrationEvidenceExportReceipt(captureId, hex(zipDigest.digest()), includeVideo)
    }

    companion object {
        private const val MAX_VIDEO_BYTES = 200_000_000L
        private const val MAX_TOTAL_BYTES = 360_000_000L
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private val SENSOR_FIELDS = setOf(
            WirePayloadKeys.CAPTURE_ID, WirePayloadKeys.ATTEMPT_ID, WirePayloadKeys.EXERCISE_ID, WirePayloadKeys.EXERCISE_NAME,
            WirePayloadKeys.SET_INDEX, WirePayloadKeys.FORMAT_VERSION, WirePayloadKeys.COMPRESSION, WirePayloadKeys.SAMPLE_COUNT,
            WirePayloadKeys.SAMPLE_RATE_REQUESTED, WirePayloadKeys.SAMPLE_RATE_OBSERVED, WirePayloadKeys.START_MONOTONIC_NS,
            WirePayloadKeys.END_MONOTONIC_NS, WirePayloadKeys.SHA256, WirePayloadKeys.ASSET_SIZE_BYTES, WirePayloadKeys.WATCH_REPS,
            WirePayloadKeys.TRACKING_PROFILE_ID, WirePayloadKeys.TRACKING_PROFILE_SNAPSHOT,
            "capturePurpose", "watchClockId", "watchDeviceId", "watchEpochVerified", "watchOnLeft", "watchOrientation",
            "patternBinding", "patternWearing", "patternSelectionMode", "patternActiveCommandId", "patternActiveCandidateHash",
            "patternActiveSnapshot", "patternActiveUsed", "patternActiveInterrupted", "patternBaselineCount", "patternBaselineSampleCalls",
            "patternShadowSnapshot", "patternShadowCommandId", "patternShadowCount", "patternShadowUsed", "patternShadowInterrupted", "patternShadowTrace")
    }
}
