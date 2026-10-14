package dev.kyungmin.kcoach.phone

import android.content.Context
import android.util.AtomicFile
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.time.ZoneOffset

internal data class StartDirectionMark(val startNs: Long, val endNs: Long, val label: StartDirectionLabel)
internal data class StartDirectionReview(val revision: Long, val intervals: List<ReviewedStartDirectionInterval>)
internal data class StartDirectionCaptureInfo(val id: String, val name: String, val key: StartWristDirectionKey,
    val firstNs: Long, val lastNs: Long)
internal data class StartDirectionCandidateInfo(val id: String, val version: Long)

/** Stores only human labels and source pointers; the original inbox remains the only sensor store. */
internal class StartDirectionReviewStore(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.noBackupFilesDir, "start-direction-reviews.json"))
    private fun read(): JSONObject {
        val bytes = try { file.readFully() } catch (_: FileNotFoundException) {
            return JSONObject().put("version", 1).put("reviews", JSONObject()).put("candidates", JSONObject())
        }
        require(bytes.size <= 8_000_000)
        return JSONObject(String(bytes, Charsets.UTF_8)).also { require(it.getInt("version") == 1) }
    }
    private fun write(root: JSONObject) {
        val bytes = root.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= 8_000_000)
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (error: Exception) { file.failWrite(out); throw error }
    }
    private fun source(captureId: String): ReceivedCapture {
        val received = CaptureInbox(app, app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE)).readCapture(captureId).getOrThrow()
        val declared = StartWristContextCodec.decode(received.manifest.payload["startWristContext"].orEmpty())
        require(declared.owner == PatternCandidateStore(app).localUserId()) { "Direction capture belongs to another user" }
        require(declared.exerciseId == ExerciseMotionCatalog.normalizeExerciseId(received.manifest.payload[WirePayloadKeys.EXERCISE_ID].orEmpty()))
        require(received.manifest.payload["startWristDeviceId"].orEmpty().isNotBlank() &&
            received.manifest.payload["startWristDeviceId"] == received.manifest.payload["watchDeviceId"])
        StartWristDirectionKey(declared, received.manifest.payload.getValue("startWristDeviceId"))
        require(received.manifest.sentAtWallMs > 0)
        return received
    }
    private fun manifestHash(received: ReceivedCapture) = SensorCaptureFormat.sha256(WireCodec.encode(received.manifest).toByteArray())

    fun captureInfo(id: String): Result<StartDirectionCaptureInfo> = runCatching {
        val received = source(id)
        val document = received.validated.document
        StartDirectionCaptureInfo(id, received.manifest.payload["exerciseName"].orEmpty().ifBlank {
            received.manifest.payload.getValue(WirePayloadKeys.EXERCISE_ID)
        }, StartWristDirectionKey(StartWristContextCodec.decode(received.manifest.payload.getValue("startWristContext")),
            received.manifest.payload.getValue("startWristDeviceId")), requireNotNull(document.startMonotonicNs), requireNotNull(document.endMonotonicNs))
    }

    /** Enumerates received sources, including captures which have not yet acquired a human label. */
    fun captureIds(): List<String> {
        val owner = PatternCandidateStore(app).localUserId()
        val prefs = app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE)
        return prefs.all.entries.asSequence().filter { it.key.startsWith("capture:") && it.key.endsWith(":manifest") }
            .mapNotNull { entry -> runCatching {
                val envelope = WireCodec.decode(entry.value as String)
                val declared = StartWristContextCodec.decode(envelope.payload["startWristContext"].orEmpty())
                if (declared.owner != owner || !declared.completeForDirectionValidation) null
                else envelope.payload.getValue(WirePayloadKeys.CAPTURE_ID) to envelope.sentAtWallMs
            }.getOrNull() }.sortedByDescending { it.second }.map { it.first }.toList()
    }

    fun candidates(): Result<List<StartDirectionCandidateInfo>> = synchronized(lock) { runCatching {
        val rows = read().getJSONObject("candidates")
        rows.keys().asSequence().map { rows.getJSONObject(it) }.map {
            StartDirectionCandidateInfo(it.getString("id"), it.getLong("version"))
        }.toList()
    } }
    fun reviewRevision(id: String): Result<Long> = synchronized(lock) { runCatching {
        read().getJSONObject("reviews").optJSONObject(id)?.getLong("revision") ?: 0L
    } }

    fun saveInPersonReview(captureId: String, marks: List<StartDirectionMark>, expectedRevision: Long): Result<StartDirectionReview> =
        saveReview(captureId, marks, expectedRevision, StartDirectionReviewSource.HUMAN_IN_PERSON)

    /** Camera marks are original phone timestamps, never playback milliseconds substituted for sensor clocks. */
    fun saveVideoReview(captureId: String, cameraMarks: List<StartDirectionMark>, alignmentReviewed: Boolean,
        expectedRevision: Long): Result<StartDirectionReview> = synchronized(lock) { runCatching {
        require(alignmentReviewed) { "Review first, middle, and last motion alignment before labeling directions" }
        require(cameraMarks.isNotEmpty() && cameraMarks.size <= 100)
        val evidence = videoEvidence(captureId, cameraMarks)
        val capture = CalibrationCaptureStore(app).load(captureId).getOrThrow()
        val mapping = CalibrationReviewStore(app).alignment(captureId).getOrThrow()
        // Trim both edges by the clock uncertainty; only the unquestionably labeled hold is fitted.
        val uncertainty = Math.addExact(mapping.uncertaintyNs, 1_000_000L)
        val sensorMarks = cameraMarks.map { mark -> StartDirectionMark(
            Math.addExact(mapping.watchTimeNs(mark.startNs, capture.phoneClockId, mapping.start.watchClockId), uncertainty),
            Math.subtractExact(mapping.watchTimeNs(mark.endNs, capture.phoneClockId, mapping.start.watchClockId), uncertainty), mark.label) }
        saveReview(captureId, sensorMarks, expectedRevision, StartDirectionReviewSource.HUMAN_VIDEO,
            evidence, cameraMarks).getOrThrow()
    } }

    private fun videoEvidence(id: String, marks: List<StartDirectionMark>): String {
        val captures = CalibrationCaptureStore(app)
        val capture = captures.load(id).getOrThrow()
        require(capture.videoComplete && capture.status == CalibrationCaptureStatus.REVIEW_PENDING)
        val inspected = ReferenceVideoEvidence.inspect(captures.videoFile(id)).getOrThrow()
        val frames = captures.frames(id).getOrThrow().filter { it.videoFrameWritten }
        require(frames.size == inspected.presentationTimesUs.size && frames.zip(inspected.presentationTimesUs).all { (frame, pts) ->
            kotlin.math.abs(frame.videoPresentationUs - pts) <= 1_000 && frame.width == inspected.width && frame.height == inspected.height
        })
        val mapping = CalibrationReviewStore(app).alignment(id).getOrThrow()
        val frameTimes = frames.map { it.cameraTimestampNs }.toSet()
        marks.forEach { mark ->
            require(mark.endNs > mark.startNs && mark.startNs in frameTimes && mark.endNs in frameTimes)
            val selected = frames.filter { it.cameraTimestampNs in mark.startNs..mark.endNs }
            require(selected.isNotEmpty() && selected.all {
                it.timestampSource == CameraTimestampSource.PHONE_ELAPSED_REALTIME && it.phoneClockId == capture.phoneClockId
            } && selected.zipWithNext().all { (a, b) -> b.cameraTimestampNs - a.cameraTimestampNs in 1..150_000_000L })
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        captures.videoFile(id).inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        digest.update(frames.joinToString("\n") { CameraFrameCodec.encode(it).toString() }.toByteArray())
        digest.update(listOf(mapping.start, mapping.end, capture.phoneClockId, capture.startWristContext).joinToString("|").toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun saveReview(captureId: String, marks: List<StartDirectionMark>, expectedRevision: Long,
        reviewSource: StartDirectionReviewSource, videoDigest: String? = null,
        cameraMarks: List<StartDirectionMark> = emptyList()): Result<StartDirectionReview> = synchronized(lock) {
        runCatching {
            require(marks.isNotEmpty() && marks.size <= 100)
            val root = read(); val reviews = root.getJSONObject("reviews")
            require((reviews.optJSONObject(captureId)?.getLong("revision") ?: 0L) == expectedRevision) { "Direction review changed" }
            val received = source(captureId)
            val row = JSONObject().put("revision", Math.addExact(expectedRevision, 1)).put("reviewed_at_ms", System.currentTimeMillis())
                .put("manifest_hash", manifestHash(received)).put("raw_sha256", received.manifest.payload.getValue(WirePayloadKeys.SHA256))
                .put("source", reviewSource.name).put("video_digest", videoDigest ?: JSONObject.NULL)
                .put("camera_marks", JSONArray(cameraMarks.map { JSONObject().put("start_ns", it.startNs).put("end_ns", it.endNs).put("label", it.label.name) }))
                .put("marks", JSONArray(marks.map { JSONObject().put("start_ns", it.startNs).put("end_ns", it.endNs).put("label", it.label.name) }))
            val reviewed = decode(captureId, row, received)
            require(reviewed.intervals.sortedBy { it.startNs }.zipWithNext().all { (a, b) -> a.endNs < b.startNs })
            reviewed.intervals.forEach { it.records() }
            reviews.put(captureId, row); write(root); reviewed
        }
    }
    private fun decode(id: String, row: JSONObject, received: ReceivedCapture): StartDirectionReview {
        require(manifestHash(received) == row.getString("manifest_hash") &&
            received.manifest.payload[WirePayloadKeys.SHA256] == row.getString("raw_sha256")) { "Direction evidence changed" }
        val reviewSource = StartDirectionReviewSource.valueOf(row.optString("source", "HUMAN_IN_PERSON"))
        if (reviewSource == StartDirectionReviewSource.HUMAN_VIDEO) {
            val cameraMarks = row.getJSONArray("camera_marks")
            val marks = (0 until cameraMarks.length()).map { i -> cameraMarks.getJSONObject(i).let {
                StartDirectionMark(it.getLong("start_ns"), it.getLong("end_ns"), StartDirectionLabel.valueOf(it.getString("label")))
            } }
            require(marks.isNotEmpty() && videoEvidence(id, marks) == row.getString("video_digest")) { "Reviewed video or clock evidence changed" }
        }
        val key = StartWristDirectionKey(StartWristContextCodec.decode(received.manifest.payload.getValue("startWristContext")),
            received.manifest.payload.getValue("startWristDeviceId"))
        val marks = row.getJSONArray("marks")
        val day = Instant.ofEpochMilli(received.manifest.sentAtWallMs).atZone(ZoneOffset.UTC).toLocalDate().toString()
        return StartDirectionReview(row.getLong("revision"), (0 until marks.length()).map { i ->
            val mark = marks.getJSONObject(i)
            ReviewedStartDirectionInterval(id, row.getString("raw_sha256"), day, key, received.validated.document,
                mark.getLong("start_ns"), mark.getLong("end_ns"), StartDirectionLabel.valueOf(mark.getString("label")),
                reviewSource, row.getLong("revision"), row.getLong("reviewed_at_ms"))
        })
    }
    fun loadReview(id: String): Result<StartDirectionReview> = synchronized(lock) {
        runCatching { decode(id, read().getJSONObject("reviews").getJSONObject(id), source(id)) }
    }
    private fun references(root: JSONObject, ids: List<String>): Pair<List<ReviewedStartDirectionInterval>, JSONArray> {
        require(ids.isNotEmpty() && ids.size <= 100 && ids.distinct().size == ids.size)
        val refs = JSONArray()
        val intervals = ids.flatMap { id ->
            val row = root.getJSONObject("reviews").getJSONObject(id)
            val reviewed = decode(id, row, source(id))
            refs.put(JSONObject().put("capture_id", id).put("revision", reviewed.revision)); reviewed.intervals
        }
        return intervals to refs
    }
    private fun currentIntervals(root: JSONObject, refs: JSONArray): List<ReviewedStartDirectionInterval> {
        require(refs.length() in 1..100)
        return (0 until refs.length()).flatMap { i ->
            val ref = refs.getJSONObject(i); val id = ref.getString("capture_id")
            val row = root.getJSONObject("reviews").getJSONObject(id)
            require(row.getLong("revision") == ref.getLong("revision")) { "Reviewed source revision changed" }
            decode(id, row, source(id)).intervals
        }
    }
    fun buildCandidate(id: String, version: Long, trainingIds: List<String>, angularMarginRad: Double): Result<ReviewedStartDirectionCandidate> = synchronized(lock) {
        runCatching {
            val root = read(); val candidates = root.getJSONObject("candidates")
            val (intervals, refs) = references(root, trainingIds)
            val candidate = ReviewedStartDirectionBuilder.build(intervals, id, version, angularMarginRad).getOrThrow()
            val storageId = "$id:$version"
            require(!candidates.has(storageId)) { "Direction candidate versions are immutable" }
            candidates.put(storageId, JSONObject().put("id", id).put("version", version).put("training", refs)
                .put("angular_margin_rad", angularMarginRad).put("fingerprint", candidate.fingerprint))
            write(root); candidate
        }
    }
    private fun candidate(root: JSONObject, id: String, version: Long): ReviewedStartDirectionCandidate {
        val row = root.getJSONObject("candidates").getJSONObject("$id:$version")
        val candidate = ReviewedStartDirectionBuilder.build(currentIntervals(root, row.getJSONArray("training")),
            row.getString("id"), row.getLong("version"), row.getDouble("angular_margin_rad")).getOrThrow()
        require(candidate.id == id && candidate.version == version && candidate.fingerprint == row.getString("fingerprint"))
        return candidate
    }
    fun loadCandidate(id: String, version: Long): Result<ReviewedStartDirectionCandidate> = synchronized(lock) {
        runCatching { candidate(read(), id, version) }
    }
    fun validateCandidate(id: String, version: Long, validationIds: List<String>): Result<StartDirectionValidationReport> = synchronized(lock) {
        runCatching {
            val root = read(); val candidate = candidate(root, id, version)
            val (intervals, refs) = references(root, validationIds)
            val report = ReviewedStartDirectionValidation.evaluate(candidate, intervals).getOrThrow()
            root.getJSONObject("candidates").getJSONObject("$id:$version").put("validation", refs)
                .put("validation_passed", report.passed).put("validation_fingerprint", report.candidateFingerprint)
            write(root); report
        }
    }
    /** A persisted success flag alone never activates a target; all raw bytes and review revisions are replayed. */
    fun loadValidatedCandidate(id: String, version: Long): Result<ReviewedStartDirectionCandidate> = synchronized(lock) {
        runCatching {
            val root = read(); val candidate = candidate(root, id, version)
            val row = root.getJSONObject("candidates").getJSONObject("$id:$version")
            val report = ReviewedStartDirectionValidation.evaluate(candidate, currentIntervals(root, row.getJSONArray("validation"))).getOrThrow()
            require(report.passed && row.getBoolean("validation_passed") && report.candidateFingerprint == row.getString("validation_fingerprint")) {
                "Direction controls are incomplete, changed, or rejected"
            }
            candidate
        }
    }
    fun activate(id: String, version: Long): Result<StartDirectionRuntimeProfile> = synchronized(lock) { runCatching {
        val candidate = loadValidatedCandidate(id, version).getOrThrow()
        val root = read(); val row = root.getJSONObject("candidates").getJSONObject("$id:$version")
        val report = ReviewedStartDirectionValidation.evaluate(candidate, currentIntervals(root, row.getJSONArray("validation"))).getOrThrow()
        val profile = StartDirectionRuntimeProfile.from(candidate, report)
        val active = root.optJSONObject("active") ?: JSONObject().also { root.put("active", it) }
        active.put(StartWristContextCodec.encode(profile.key.context), JSONObject().put("id", id).put("version", version)
            .put("snapshot", StartDirectionRuntimeCodec.encode(profile)))
        write(root); profile
    } }
    fun selected(context: StartWristContext): Result<StartDirectionRuntimeProfile?> = synchronized(lock) { runCatching {
        val root = read()
        val selection = root.optJSONObject("active")?.optJSONObject(StartWristContextCodec.encode(context)) ?: return@runCatching null
        val candidate = loadValidatedCandidate(selection.getString("id"), selection.getLong("version")).getOrThrow()
        val profile = StartDirectionRuntimeCodec.decode(selection.getString("snapshot"))
        require(profile.key == candidate.key && profile.candidateFingerprint == candidate.fingerprint && profile.gateConfig == candidate.gateConfig)
        profile
    } }
    fun disable(context: StartWristContext): Result<Unit> = synchronized(lock) { runCatching {
        val root = read(); root.optJSONObject("active")?.remove(StartWristContextCodec.encode(context)); write(root)
    } }
    companion object { private val lock = Any() }
}
