package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class CalibrationCaptureStatus { RECORDING, REVIEW_PENDING, INTERRUPTED }
data class CalibrationCapture(val id: String, val exerciseId: String, val exerciseName: String,
    val watchOnLeft: Boolean, val watchOrientation: String, val phoneClockId: String,
    val setId: String, val attemptId: String, val createdAtMs: Long, val status: CalibrationCaptureStatus,
    val videoComplete: Boolean = false, val interruptionReason: String? = null, val ownerProcessId: String = "UNKNOWN",
    val startWristContext: StartWristContext? = null,
    /** Last useful frame, before the observed end hold. Originals and original clocks are retained. */
    val motionEndCameraNs: Long? = null)

/** A stopped/interrupted clip remains its own immutable attempt, with original frame clocks. */
class CalibrationCaptureStore(private val context: Context) {
    private val root = File(context.filesDir, "calibration-captures")
    private val prefs = context.getSharedPreferences("calibration-captures", Context.MODE_PRIVATE)

    fun begin(exerciseId: String, name: String, watchOnLeft: Boolean, phoneClockId: String,
              watchOrientation: String = "UNKNOWN", startWristContext: StartWristContext? = null): Result<CalibrationCapture> {
        // PatternCandidateStore reads captures while holding its own lock. Resolve the immutable
        // local owner first, so camera creation never takes these locks in the opposite order.
        val owner = if (startWristContext == null) null else runCatching { PatternCandidateStore(context).localUserId() }
            .getOrElse { return Result.failure(it) }
        return synchronized(lock) { runCatching {
            require(exerciseId.isNotBlank() && name.isNotBlank() && phoneClockId.isNotBlank())
            require(watchOrientation in setOf("OUTSIDE", "INSIDE", "UNKNOWN"))
            startWristContext?.let { declared ->
                require(declared.exerciseId == ExerciseMotionCatalog.normalizeExerciseId(exerciseId) &&
                    declared.owner == owner &&
                    declared.side == if (watchOnLeft) "LEFT" else "RIGHT")
                require(declared.placement == watchOrientation)
            }
            check(SessionStore(context).loadActive().getOrThrow() == null) { "An active workout owns the watch" }
            check(prefs.getString("active", null) == null) { "Another camera capture is recording" }
            val capture = CalibrationCapture("paired-${UUID.randomUUID()}", exerciseId, name, watchOnLeft,
                watchOrientation, phoneClockId, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                System.currentTimeMillis(), CalibrationCaptureStatus.RECORDING, ownerProcessId = processOwner,
                startWristContext = startWristContext)
            val directory = directory(capture.id).also { check(it.mkdirs() || it.isDirectory) }
            writeMetadata(capture)
            check(File(directory, "frames.jsonl").createNewFile())
            check(prefs.edit().putString("active", capture.id).commit())
            capture
        } }
    }

    fun load(id: String): Result<CalibrationCapture> = synchronized(lock) {
        runCatching { decodeMetadata(JSONObject(File(directory(id), "capture.json").readText())) }
    }

    fun list(): List<CalibrationCapture> = synchronized(lock) {
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("paired-") }
            .mapNotNull { load(it.name).getOrNull() }.sortedByDescending { it.createdAtMs }
    }

    fun videoFile(id: String): File = File(directory(id), "reference.mp4")

    fun savePerformance(id: String, lines: List<String>): Result<Unit> = synchronized(lock) { runCatching {
        require(lines.size <= 180 && lines.all { it.length <= 1024 })
        val file = File(directory(id), "performance.json")
        val temp = File(file.parentFile, "performance.json.incoming")
        val json = JSONObject().put("schemaVersion", 1).put("windows", JSONArray(lines))
        FileOutputStream(temp).use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(temp.renameTo(file))
    } }
    fun performance(id: String): String? = synchronized(lock) {
        File(directory(id), "performance.json").takeIf { it.isFile && it.length() <= 200_000 }?.readText()
    }

    fun active(): Result<CalibrationCapture?> = synchronized(lock) {
        runCatching { prefs.getString("active", null)?.let { load(it).getOrThrow() }
            ?.takeIf { it.status == CalibrationCaptureStatus.RECORDING } }
    }

    fun recoverAfterProcessRestart(): Result<CalibrationCapture?> = synchronized(lock) {
        runCatching {
            val active = active().getOrThrow() ?: return@runCatching null
            if (active.ownerProcessId == processOwner) null else recoverInterrupted().getOrThrow()
        }
    }

    fun sensor(id: String): Result<ReceivedCapture> = synchronized(lock) {
        runCatching {
            val capture = load(id).getOrThrow()
            val link = JSONObject(File(directory(id), "sensor.json").readText())
            val received = CaptureInbox(context, context.getSharedPreferences("wear-sync", Context.MODE_PRIVATE))
                .readCapture(link.getString("captureId")).getOrThrow()
            val original = WireCodec.decode(link.getString("manifest"))
            require(original.payload == received.manifest.payload && received.manifest.sessionId == capture.id &&
                received.manifest.setId == capture.setId && received.manifest.payload[WirePayloadKeys.ATTEMPT_ID] == capture.attemptId)
            received
        }
    }

    fun attachSensor(received: ReceivedCapture): Result<Unit> = synchronized(lock) {
        runCatching {
            val manifest = received.manifest
            val capture = load(manifest.sessionId).getOrThrow()
            require(manifest.payload["capturePurpose"] == CapturePurpose.PAIRED_REFERENCE.name && manifest.setId == capture.setId &&
                manifest.payload[WirePayloadKeys.ATTEMPT_ID] == capture.attemptId && manifest.payload[WirePayloadKeys.EXERCISE_ID] == capture.exerciseId)
            require(manifest.payload["watchClockId"].orEmpty().isNotBlank())
            capture.startWristContext?.let { declared ->
                require(StartWristContextCodec.decode(manifest.payload["startWristContext"].orEmpty()) == declared)
                require(manifest.payload["startWristDeviceId"].orEmpty().isNotBlank() &&
                    manifest.payload["startWristDeviceId"] == manifest.payload["watchDeviceId"])
            }
            require(received.rawFile.canonicalFile.parentFile == File(context.filesDir, "captures").canonicalFile)
            val link = File(directory(capture.id), "sensor.json")
            val json = JSONObject().put("manifest", WireCodec.encode(manifest)).put("captureId", received.validated.captureId)
                .put("receivedAtMs", System.currentTimeMillis())
            if (link.exists()) {
                val original = WireCodec.decode(JSONObject(link.readText()).getString("manifest"))
                require(original.payload == manifest.payload && original.sessionId == manifest.sessionId && original.setId == manifest.setId)
            } else {
                val temp = File(link.parentFile, "sensor.json.incoming")
                FileOutputStream(temp).use { output -> output.write(json.toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
                check(temp.renameTo(link))
            }
        }
    }

    fun appendFrame(id: String, frame: CameraPoseFrame): Result<Unit> = synchronized(lock) {
        runCatching {
            val capture = load(id).getOrThrow()
            require(capture.status == CalibrationCaptureStatus.RECORDING && frame.phoneClockId == capture.phoneClockId)
            val file = File(directory(id), "frames.jsonl")
            require(file.length() < 120_000_000L && frame.frameIndex < 6_000) { "Reference capture limit reached" }
            val cache = cursors[file.absolutePath]?.takeIf { it.first == file.length() }
            val previous = if (cache != null) cache.second else frames(id).getOrThrow().lastOrNull()
            require(frame.frameIndex == (previous?.frameIndex?.plus(1) ?: 0L))
            if (previous != null) {
                require(frame.cameraTimestampNs > previous.cameraTimestampNs && frame.modelTimestampMs > previous.modelTimestampMs &&
                    frame.videoPresentationUs > previous.videoPresentationUs && frame.timestampSource == previous.timestampSource)
                require(frame.phoneReceivedNs >= previous.phoneReceivedNs)
            }
            val encoded = CameraFrameCodec.encode(frame).toString() + "\n"
            FileOutputStream(file, true).use { output -> output.write(encoded.toByteArray(Charsets.UTF_8)); output.fd.sync() }
            cursors[file.absolutePath] = file.length() to frame
        }
    }

    fun frames(id: String): Result<List<CameraPoseFrame>> = synchronized(lock) {
        runCatching {
            val file = File(directory(id), "frames.jsonl")
            require(file.length() <= 120_000_000L)
            file.useLines { lines -> lines.filter { it.isNotBlank() }.map { CameraFrameCodec.decode(JSONObject(it)) }.toList() }
        }
    }

    /** A view over immutable camera evidence; no renumbering or timestamp rewriting. */
    fun motionFrames(id: String): Result<List<CameraPoseFrame>> = runCatching {
        val end = load(id).getOrThrow().motionEndCameraNs
        frames(id).getOrThrow().filter { end == null || it.cameraTimestampNs <= end }
    }

    /** Never apply a camera cutoff to the watch clock without a verified mapping. */
    fun motionDocument(id: String): Result<SensorCaptureDocument> = runCatching {
        val capture = load(id).getOrThrow()
        val document = sensor(id).getOrThrow().validated.document
        val end = capture.motionEndCameraNs ?: return@runCatching document
        val boundary = frames(id).getOrThrow().single { it.cameraTimestampNs == end && it.videoFrameWritten }
        require(boundary.timestampSource == CameraTimestampSource.PHONE_ELAPSED_REALTIME && boundary.phoneClockId == capture.phoneClockId) {
            "Camera boundary is not in the verified phone clock"
        }
        val mapping = CalibrationReviewStore(context).alignment(id).getOrThrow()
        val watchEnd = mapping.watchTimeNs(end, capture.phoneClockId, mapping.start.watchClockId)
        // Keep the first bracketing sample for boundary interpolation/coverage. It belongs
        // to the observed stationary hold; all later hold/approach samples stay excluded.
        val after = document.records.indexOfFirst { it.sample.timestampNs > watchEnd }
        document.copy(records = if (after < 0) document.records else document.records.take(after + 1))
    }

    fun finish(id: String, videoComplete: Boolean, reason: String? = null, motionEndCameraNs: Long? = null): Result<CalibrationCapture> = synchronized(lock) {
        runCatching {
            val capture = load(id).getOrThrow()
            if (capture.status != CalibrationCaptureStatus.RECORDING) {
                // Metadata may have committed before clearing the active receipt failed.
                // Complete cleanup without changing the already-saved video status or reason.
                if (prefs.getString("active", null) == id) check(prefs.edit().remove("active").commit())
                cursors.remove(File(directory(id), "frames.jsonl").absolutePath)
                return@runCatching capture
            }
            val video = if (videoComplete && reason == null) ReferenceVideoEvidence.inspect(videoFile(id)).getOrNull() else null
            val capturedFrames = if (video != null) frames(id).getOrNull() else null
            val recorded = capturedFrames?.filter { it.videoFrameWritten }.orEmpty()
            val complete = video != null && recorded.isNotEmpty() && recorded.size == video.presentationTimesUs.size &&
                recorded.zip(video.presentationTimesUs).all { (frame, pts) ->
                    kotlin.math.abs(frame.videoPresentationUs - pts) <= 1_000 && frame.width == video.width && frame.height == video.height
                }
            if (complete && motionEndCameraNs != null) require(capturedFrames.orEmpty().any {
                it.cameraTimestampNs == motionEndCameraNs && it.videoFrameWritten
            }) { "End boundary must be an encoded camera frame" }
            val updated = capture.copy(status = if (complete) CalibrationCaptureStatus.REVIEW_PENDING else CalibrationCaptureStatus.INTERRUPTED,
                motionEndCameraNs = motionEndCameraNs.takeIf { complete },
                videoComplete = complete, interruptionReason = reason ?: if (!complete) "Reference video was not finalized" else null)
            writeMetadata(updated)
            if (prefs.getString("active", null) == id) check(prefs.edit().remove("active").commit())
            cursors.remove(File(directory(id), "frames.jsonl").absolutePath)
            updated
        }
    }

    /** Called after the previous capture owner has stopped or the process has restarted. */
    fun recoverInterrupted(): Result<CalibrationCapture?> = synchronized(lock) {
        runCatching {
            val id = prefs.getString("active", null) ?: return@runCatching null
            val capture = load(id).getOrThrow()
            if (capture.status == CalibrationCaptureStatus.RECORDING) finish(id, false, "Camera capture owner stopped").getOrThrow()
            else { check(prefs.edit().remove("active").commit()); capture }
        }
    }

    /** Removes one capture's folder (video, frames, reviews, sensor link). The raw watch file in the shared inbox is left alone. */
    fun delete(id: String): Result<Unit> = synchronized(lock) {
        runCatching {
            val directory = directory(id)
            if (directory.exists()) check(directory.deleteRecursively()) { "Could not delete $id" }
            cursors.keys.removeAll { it.startsWith(directory.absolutePath) }
            Unit
        }
    }

    private fun directory(id: String): File {
        require(id.matches(Regex("paired-[A-Za-z0-9-]{1,100}")))
        return File(root, id)
    }

    private fun writeMetadata(capture: CalibrationCapture) {
        val target = File(directory(capture.id), "capture.json")
        val temp = File(target.parentFile, "capture.json.incoming")
        val json = JSONObject().put("schemaVersion", 1).put("id", capture.id).put("exerciseId", capture.exerciseId)
            .put("exerciseName", capture.exerciseName).put("watchOnLeft", capture.watchOnLeft)
            .put("watchOrientation", capture.watchOrientation).put("phoneClockId", capture.phoneClockId)
            .put("setId", capture.setId).put("attemptId", capture.attemptId).put("createdAtMs", capture.createdAtMs)
            .put("status", capture.status.name).put("videoComplete", capture.videoComplete)
            .put("ownerProcessId", capture.ownerProcessId)
            .put("motionEndCameraNs", capture.motionEndCameraNs ?: JSONObject.NULL)
            .put("startWristContext", capture.startWristContext?.let(StartWristContextCodec::encode) ?: JSONObject.NULL)
            .put("interruptionReason", capture.interruptionReason ?: JSONObject.NULL)
            .put("model", "pose_landmarker_lite-float16-1").put("purpose", "PAIRED_REFERENCE")
        FileOutputStream(temp).use { output -> output.write(json.toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
        check(temp.renameTo(target))
    }

    private fun decodeMetadata(json: JSONObject): CalibrationCapture {
        require(json.getInt("schemaVersion") == 1)
        return CalibrationCapture(json.getString("id"), json.getString("exerciseId"), json.getString("exerciseName"),
            json.getBoolean("watchOnLeft"), json.getString("watchOrientation"), json.getString("phoneClockId"),
            json.getString("setId"), json.getString("attemptId"), json.getLong("createdAtMs"),
            CalibrationCaptureStatus.valueOf(json.getString("status")), json.getBoolean("videoComplete"),
            if (json.isNull("interruptionReason")) null else json.getString("interruptionReason"), json.optString("ownerProcessId", "UNKNOWN"),
            json.optString("startWristContext").takeIf { json.has("startWristContext") && !json.isNull("startWristContext") }
                ?.let(StartWristContextCodec::decode),
            if (json.has("motionEndCameraNs") && !json.isNull("motionEndCameraNs")) json.getLong("motionEndCameraNs") else null)
    }

    companion object {
        private val lock = WorkoutRuntimeLock.monitor
        private val processOwner = UUID.randomUUID().toString()
        private val cursors = mutableMapOf<String, Pair<Long, CameraPoseFrame?>>()
    }
}

internal object CameraFrameCodec {
    fun encode(frame: CameraPoseFrame): JSONObject = JSONObject().put("index", frame.frameIndex)
        .put("cameraNs", frame.cameraTimestampNs).put("receivedNs", frame.phoneReceivedNs).put("modelMs", frame.modelTimestampMs)
        .put("completedNs", frame.modelCompletedNs).put("videoUs", frame.videoPresentationUs)
        .put("width", frame.width).put("height", frame.height).put("rotation", frame.rotationDegrees)
        .put("previewMirrored", frame.previewMirrored).put("source", frame.timestampSource.name).put("clockId", frame.phoneClockId)
        .put("image", poses(frame.imageLandmarks)).put("world", poses(frame.worldLandmarks))
        .put("videoFrameWritten", frame.videoFrameWritten).put("error", frame.error ?: JSONObject.NULL)

    fun decode(json: JSONObject): CameraPoseFrame = CameraPoseFrame(json.getLong("index"), json.getLong("cameraNs"),
        json.getLong("receivedNs"), json.getLong("modelMs"), json.getLong("completedNs"), json.getLong("videoUs"),
        json.getInt("width"), json.getInt("height"), json.getInt("rotation"), json.getBoolean("previewMirrored"),
        CameraTimestampSource.valueOf(json.getString("source")), json.getString("clockId"),
        poses(json.getJSONArray("image")), poses(json.getJSONArray("world")), json.getBoolean("videoFrameWritten"),
        if (json.isNull("error")) null else json.getString("error"))

    private fun poses(poses: List<List<CameraLandmark>>) = JSONArray(poses.map { points -> JSONArray(points.map { point ->
        JSONArray().put(point.x).put(point.y).put(point.z).put(point.visibility ?: JSONObject.NULL).put(point.presence ?: JSONObject.NULL)
    }) })

    private fun poses(json: JSONArray): List<List<CameraLandmark>> = (0 until json.length()).map { pose ->
        val points = json.getJSONArray(pose)
        (0 until points.length()).map { index -> val point = points.getJSONArray(index)
            CameraLandmark(point.getDouble(0), point.getDouble(1), point.getDouble(2),
                if (point.isNull(3)) null else point.getDouble(3), if (point.isNull(4)) null else point.getDouble(4)) }
    }
}
