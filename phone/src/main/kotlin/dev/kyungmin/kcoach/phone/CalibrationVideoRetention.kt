package dev.kyungmin.kcoach.phone

import android.content.Context
import android.util.AtomicFile
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

internal data class ArchivedVideoObservation(val videoSha256: String, val videoBytes: Long,
    val evidence: ReferenceVideoEvidence, val frameDigest: String)

/** A user may remove the image after review. Original sensor/landmark/label files are never deleted here. */
internal class CalibrationVideoRetention(context: Context) {
    private val app = context.applicationContext
    private val captures = CalibrationCaptureStore(app)
    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("paired-[A-Za-z0-9-]{1,100}")))
        return AtomicFile(File(app.filesDir, "calibration-captures/$id/video-retention.json"))
    }
    private fun read(id: String): JSONObject? {
        val bytes = try { file(id).readFully() } catch (_: FileNotFoundException) { return null }
        require(bytes.size <= 1_000_000)
        return JSONObject(bytes.toString(Charsets.UTF_8)).also { require(it.getInt("version") == 1 && it.getString("capture_id") == id) }
    }
    private fun write(id: String, row: JSONObject) {
        val bytes = row.toString().toByteArray(); require(bytes.size <= 1_000_000)
        val target = file(id); val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) } catch (error: Exception) { target.failWrite(output); throw error }
    }
    fun logsOnly(id: String): Boolean = synchronized(lock) { runCatching {
        val row = read(id) ?: return@runCatching false
        row.getString("choice") in setOf("LOGS_ONLY", "LOGS_ONLY_REQUESTED") && !captures.videoFile(id).exists()
    }.getOrDefault(false) }

    fun keepVideo(id: String): Result<Unit> = synchronized(lock) { runCatching {
        require(captures.load(id).getOrThrow().status != CalibrationCaptureStatus.RECORDING)
        require(captures.videoFile(id).isFile) { "Deleted video cannot be restored from landmarks" }
        write(id, JSONObject().put("version", 1).put("capture_id", id).put("choice", "KEEP_VIDEO"))
    } }

    /** On reload, inspect live bytes or the immutable observation saved before an explicit logs-only choice. */
    fun observation(id: String): Result<ArchivedVideoObservation> = synchronized(lock) { runCatching {
        val video = captures.videoFile(id)
        val frames = captures.frames(id).getOrThrow().filter { it.videoFrameWritten }
        val observation = if (video.isFile) {
            val inspected = ReferenceVideoEvidence.inspect(video).getOrThrow()
            val digest = MessageDigest.getInstance("SHA-256")
            video.inputStream().use { input -> val buffer = ByteArray(8192)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            ArchivedVideoObservation(hex(digest.digest()), video.length(), inspected, frameDigest(frames))
        } else {
            val row = requireNotNull(read(id)) { "Missing video has no reviewed archive" }
            require(row.getString("choice") in setOf("LOGS_ONLY", "LOGS_ONLY_REQUESTED"))
            val pts = row.getJSONArray("presentation_us"); require(pts.length() in 1..6_000)
            val times = (0 until pts.length()).map { pts.getLong(it) }
            require(times.zipWithNext().all { (a, b) -> b > a })
            ArchivedVideoObservation(row.getString("video_sha256"), row.getLong("video_bytes"),
                ReferenceVideoEvidence(times, row.getInt("width"), row.getInt("height")), row.getString("frame_digest"))
        }
        require(observation.videoSha256.matches(Regex("[0-9a-f]{64}")) && observation.videoBytes in 1..100_000_000L)
        require(frames.size == observation.evidence.presentationTimesUs.size && frameDigest(frames) == observation.frameDigest &&
            frames.zip(observation.evidence.presentationTimesUs).all { (frame, pts) ->
                kotlin.math.abs(frame.videoPresentationUs - pts) <= 1_000 && frame.width == observation.evidence.width && frame.height == observation.evidence.height
            }) { "Original frame observation changed" }
        observation
    } }

    fun discardVideo(id: String, expectedReviewRevision: Long, expectedDirectionRevision: Long): Result<Unit> = runCatching {
        // Human review stores can read this archive while holding their own locks. Resolve them first.
        val capture = captures.load(id).getOrThrow()
        require(capture.status == CalibrationCaptureStatus.REVIEW_PENDING && capture.videoComplete)
        val reviewed = CalibrationReviewStore(app).load(id).getOrThrow()
        val directions = StartDirectionReviewStore(app)
        val directionRevision = directions.reviewRevision(id).getOrThrow()
        require(reviewed.revision == expectedReviewRevision && directionRevision == expectedDirectionRevision) { "Review changed; reopen the clip" }
        val directionReview = if (directionRevision > 0) directions.loadReview(id).getOrThrow() else null
        require(reviewed.countReference != null || reviewed.references.isNotEmpty() ||
            directionReview?.intervals?.any { it.reviewSource == StartDirectionReviewSource.HUMAN_VIDEO } == true) { "Review the clip before removing its image" }
        synchronized(lock) {
            if (logsOnly(id)) return@synchronized
            val original = observation(id).getOrThrow()
            val row = JSONObject().put("version", 1).put("capture_id", id).put("choice", "LOGS_ONLY_REQUESTED")
                .put("video_sha256", original.videoSha256).put("video_bytes", original.videoBytes).put("frame_digest", original.frameDigest)
                .put("width", original.evidence.width).put("height", original.evidence.height)
                .put("presentation_us", JSONArray(original.evidence.presentationTimesUs))
                .put("review_revision", reviewed.revision).put("direction_revision", directionRevision).put("chosen_at_ms", System.currentTimeMillis())
            write(id, row) // A process exit after deletion still leaves the reviewed observation and user's choice.
            val video = captures.videoFile(id)
            require(video.canonicalFile.parentFile == File(app.filesDir, "calibration-captures/$id").canonicalFile &&
                video.canonicalFile.name == "reference.mp4")
            check(video.delete()) { "Could not remove the selected video" }
            row.put("choice", "LOGS_ONLY")
            // The already-synced request plus absent image is also a completed, recoverable choice.
            runCatching { write(id, row) }.getOrElse { check(logsOnly(id)) { "Video removed but its durable observation is unavailable" } }
        }
    }
    companion object {
        private val lock = Any()
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        fun frameDigest(frames: List<CameraPoseFrame>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            frames.forEach { digest.update((CameraFrameCodec.encode(it).toString() + "\n").toByteArray()) }
            return hex(digest.digest())
        }
    }
}
