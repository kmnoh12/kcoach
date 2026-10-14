package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class CalibrationReview(val captureId: String, val revision: Long, val countReference: SetCountReference?,
    val references: List<RepReference>, val cameraMetrics: CameraObservedMetrics? = null)

/** Manual totals and reviewed video boundaries have different evidential meaning. */
class CalibrationReviewStore(private val context: Context) {
    private val captures = CalibrationCaptureStore(context)

    fun load(id: String): Result<CalibrationReview> = synchronized(lock) {
        runCatching {
            val capture = captures.load(id).getOrThrow()
            val latest = directory(id).listFiles().orEmpty().filter { it.name.matches(Regex("review-[0-9]+\\.json")) }
                .maxByOrNull { it.name.removePrefix("review-").removeSuffix(".json").toLong() }
            if (latest == null) CalibrationReview(id, 0, null, emptyList()) else decode(JSONObject(latest.readText()), capture)
        }
    }

    fun saveCount(id: String, actualReps: Int, expectedRevision: Long): Result<CalibrationReview> = synchronized(lock) {
        runCatching {
            require(actualReps in 0..9_999)
            val capture = captures.load(id).getOrThrow()
            require(capture.status != CalibrationCaptureStatus.RECORDING)
            val current = load(id).getOrThrow()
            require(current.revision == expectedRevision) { "Review changed; reload it" }
            require(current.references.size <= actualReps) { "Confirmed boundaries exceed the actual total" }
            val next = current.copy(revision = current.revision + 1,
                countReference = SetCountReference(capture.setId, capture.attemptId, actualReps, System.currentTimeMillis()))
            write(next)
            next
        }
    }

    fun alignment(id: String): Result<CaptureClockMapping> = runCatching {
        val capture = captures.load(id).getOrThrow()
        val sensor = captures.sensor(id).getOrThrow()
        require(sensor.manifest.payload["watchEpochVerified"] == "true")
        val clocks = CaptureClockStore(context)
        val start = clocks.alignment(id, "START").getOrThrow()
        val end = clocks.alignment(id, "END").getOrThrow()
        require(start.phoneClockId == capture.phoneClockId && start.watchClockId == sensor.manifest.payload["watchClockId"])
        val mapping = CaptureClockMapping(start, end)
        require(mapping.uncertaintyNs + 1_000_000L <= RepReference.MAX_LABEL_ALIGNMENT_UNCERTAINTY_NS)
        if (end.phoneOriginNs - start.phoneOriginNs >= 20_000_000_000L) {
            val middle = clocks.alignment(id, "MIDDLE").getOrThrow()
            val predicted = mapping.phoneTimeNs(middle.watchOriginNs, middle.phoneClockId, middle.watchClockId)
            require(kotlin.math.abs(predicted - middle.phoneOriginNs) <= mapping.uncertaintyNs + middle.uncertaintyNs)
        }
        mapping
    }

    fun saveCycles(id: String, cycles: List<RepCycle>, alignmentReviewed: Boolean, expectedRevision: Long, simultaneousArmsReviewed: Boolean = false): Result<CalibrationReview> = synchronized(lock) {
        runCatching {
            val capture = captures.load(id).getOrThrow()
            require(capture.status == CalibrationCaptureStatus.REVIEW_PENDING && capture.videoComplete)
            val video = ReferenceVideoEvidence.inspect(captures.videoFile(id)).getOrThrow()
            val current = load(id).getOrThrow()
            require(current.revision == expectedRevision && cycles.size <= 1_000)
            require(current.countReference == null || cycles.size <= current.countReference.reps)
            require(cycles.zipWithNext().all { (a, b) -> b.startNs >= a.endNs })
            val frames = captures.frames(id).getOrThrow()
            val written = frames.filter { it.videoFrameWritten }
            require(written.size == video.presentationTimesUs.size && written.zip(video.presentationTimesUs).all { (frame, pts) ->
                kotlin.math.abs(frame.videoPresentationUs - pts) <= 1_000
            })
            val frameTimes = written.map { it.cameraTimestampNs }.toSet()
            val contract = ExerciseMeasurementContracts.resolve(capture.exerciseId)
            require(cycles.all { it.departurePhase == contract.departurePhase &&
                it.startNs in frameTimes && it.turnaroundNs in frameTimes && it.endNs in frameTimes })
            val mapping = alignment(id).getOrNull()
            val sensor = captures.sensor(id).getOrNull()
            val reviewAt = System.currentTimeMillis()
            val references = cycles.map { cycle ->
                val visible = frames.filter { it.cameraTimestampNs in cycle.startNs..cycle.endNs }
                val cameraGood = visible.isNotEmpty() && visible.all { frame ->
                    frame.timestampSource == CameraTimestampSource.PHONE_ELAPSED_REALTIME && frame.phoneClockId == capture.phoneClockId &&
                        CameraObservationQuality.imageLandmarksVisible(frame, contract.recipe.referenceLandmarks(capture.watchOnLeft))
                } && visible.zipWithNext().all { (a, b) -> b.cameraTimestampNs - a.cameraTimestampNs <= 150_000_000L }
                val sensorCycle = if (mapping != null) runCatching { RepCycle(
                    mapping.watchTimeNs(cycle.startNs, capture.phoneClockId, mapping.start.watchClockId),
                    mapping.watchTimeNs(cycle.turnaroundNs, capture.phoneClockId, mapping.start.watchClockId),
                    mapping.watchTimeNs(cycle.endNs, capture.phoneClockId, mapping.start.watchClockId), cycle.departurePhase,
                    cycle.pauses.map { RepPause(mapping.watchTimeNs(it.startNs, capture.phoneClockId, mapping.start.watchClockId),
                        mapping.watchTimeNs(it.endNs, capture.phoneClockId, mapping.start.watchClockId)) }) }.getOrNull() else null
                val records = sensor?.validated?.document?.records.orEmpty().map { it.sample }
                val originalClocksGood = sensorCycle != null && sensor?.validated?.document?.records.orEmpty()
                    .filter { it.sample.timestampNs in sensorCycle.startNs..sensorCycle.endNs }.let { samples ->
                        samples.isNotEmpty() && samples.all { record ->
                            val accel = record.accelerationTimestampNs
                            val gyro = record.gyroscopeTimestampNs
                            val received = record.receivedElapsedNs
                            accel != null && gyro != null && gyro == record.sample.timestampNs && received != null && received >= gyro &&
                                gyro - accel in 0..40_000_000L
                        }
                    }
                val covered = sensorCycle != null && records.isNotEmpty() && records.first().timestampNs <= sensorCycle.startNs &&
                    records.last().timestampNs >= sensorCycle.endNs && records.zipWithNext().none { (a, b) ->
                        b.timestampNs > sensorCycle.startNs && a.timestampNs < sensorCycle.endNs && b.timestampNs - a.timestampNs > 60_000_000L
                    } && originalClocksGood
                RepReference(id, capture.setId, capture.attemptId, sensorCycle ?: cycle, ReferenceSource.REVIEWED_VIDEO,
                    mapping?.uncertaintyNs?.plus(1_000_000L), alignmentReviewed && mapping != null && cameraGood,
                    covered, reviewAt, when { !cameraGood -> "camera_quality"; sensorCycle == null -> "alignment_missing";
                        !alignmentReviewed -> "alignment_not_reviewed"; !covered -> "sensor_gap"; else -> null }, current.revision + 1)
            }
            val eligibleCameraCycles = cycles.filterIndexed { index, _ -> references[index].eligibleForLearning }
            val metrics = CameraObservedAnalyzer.analyze(frames, eligibleCameraCycles, capture.watchOnLeft, simultaneousArmsReviewed)
            val next = current.copy(revision = current.revision + 1, references = references, cameraMetrics = metrics)
            write(next, cycles)
            next
        }
    }

    fun cameraCycles(id: String): Result<List<RepCycle>> = synchronized(lock) {
        runCatching {
            val current = load(id).getOrThrow()
            if (current.revision == 0L) return@runCatching emptyList()
            val json = JSONObject(File(directory(id), "review-${current.revision}.json").readText())
            decodeCycles(json.optJSONArray("cameraCycles") ?: JSONArray())
        }
    }

    private fun write(review: CalibrationReview, cameraCycles: List<RepCycle>? = null) {
        val json = JSONObject().put("schemaVersion", 1).put("captureId", review.captureId).put("revision", review.revision)
            .put("count", review.countReference?.let { JSONObject().put("reps", it.reps).put("reviewedAtMs", it.reviewedAtMs) } ?: JSONObject.NULL)
            .put("cameraCycles", cycles(cameraCycles ?: cameraCycles(review.captureId).getOrThrow()))
            .put("cameraObservedMetrics", review.cameraMetrics?.let(::encodeMetrics) ?: JSONObject.NULL)
            .put("references", JSONArray(review.references.map { reference -> JSONObject()
                .put("cycle", cycles(listOf(reference.cycle)).getJSONObject(0)).put("uncertaintyNs", reference.alignmentUncertaintyNs ?: JSONObject.NULL)
                .put("alignmentVerified", reference.alignmentVerified).put("sensorCovered", reference.sensorCoverageComplete)
                .put("reviewedAtMs", reference.reviewedAtMs).put("exclusion", reference.exclusionReason ?: JSONObject.NULL)
            }))
        val target = File(directory(review.captureId), "review-${review.revision}.json")
        require(!target.exists())
        val temp = File(target.parentFile, target.name + ".incoming")
        FileOutputStream(temp).use { output -> output.write(json.toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
        check(temp.renameTo(target))
    }

    private fun decode(json: JSONObject, capture: CalibrationCapture): CalibrationReview {
        require(json.getInt("schemaVersion") == 1 && json.getString("captureId") == capture.id)
        val count = if (json.isNull("count")) null else json.getJSONObject("count").let {
            SetCountReference(capture.setId, capture.attemptId, it.getInt("reps"), it.getLong("reviewedAtMs"))
        }
        val revision = json.getLong("revision")
        val refs = json.getJSONArray("references")
        return CalibrationReview(capture.id, revision, count, (0 until refs.length()).map { index ->
            val ref = refs.getJSONObject(index)
            RepReference(capture.id, capture.setId, capture.attemptId, decodeCycles(JSONArray().put(ref.getJSONObject("cycle"))).single(),
                ReferenceSource.REVIEWED_VIDEO, if (ref.isNull("uncertaintyNs")) null else ref.getLong("uncertaintyNs"),
                ref.getBoolean("alignmentVerified"), ref.getBoolean("sensorCovered"), ref.getLong("reviewedAtMs"),
                if (ref.isNull("exclusion")) null else ref.getString("exclusion"), revision)
        }, if (json.isNull("cameraObservedMetrics") || !json.has("cameraObservedMetrics")) null else decodeMetrics(json.getJSONObject("cameraObservedMetrics")))
    }

    private fun encodeMetrics(metrics: CameraObservedMetrics) = JSONObject().put("schemaVersion", 1)
        .put("watchOnLeft", metrics.watchOnLeft).put("simultaneousArmsReviewed", metrics.simultaneousArmsReviewed)
        .put("coordinateBasis", "MEDIAPIPE_WORLD_ELBOW_ANGLE_AND_ASPECT_CORRECTED_IMAGE_TORSO")
        .put("cycles", JSONArray(metrics.cycles.map { row -> JSONObject().put("startNs", row.startNs).put("endNs", row.endNs)
            .put("frames", row.frames).put("elbowMinDegrees", row.minimumElbowAngleDegrees).put("elbowMaxDegrees", row.maximumElbowAngleDegrees)
            .put("torsoTiltVariationDegrees", row.torsoTiltVariationDegrees).put("elbowChangeShoulderWidths", row.maximumElbowPositionChangeShoulderWidths)
            .put("simultaneousAngleDifferenceDegrees", row.averageSimultaneousElbowAngleDifferenceDegrees ?: JSONObject.NULL) }))

    private fun decodeMetrics(json: JSONObject): CameraObservedMetrics {
        require(json.getInt("schemaVersion") == 1)
        val rows = json.getJSONArray("cycles"); require(rows.length() in 1..600)
        val simultaneous = json.getBoolean("simultaneousArmsReviewed")
        return CameraObservedMetrics(json.getBoolean("watchOnLeft"), simultaneous, (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val start = row.getLong("startNs"); val end = row.getLong("endNs"); val frames = row.getInt("frames")
            val min = row.getDouble("elbowMinDegrees"); val max = row.getDouble("elbowMaxDegrees")
            val tilt = row.getDouble("torsoTiltVariationDegrees"); val position = row.getDouble("elbowChangeShoulderWidths")
            val difference = if (row.isNull("simultaneousAngleDifferenceDegrees")) null else row.getDouble("simultaneousAngleDifferenceDegrees")
            require(start >= 0 && end > start && frames >= 3 && min in 0.0..180.0 && max in min..180.0 &&
                tilt.isFinite() && tilt >= 0 && position.isFinite() && position >= 0 &&
                (difference == null || simultaneous && difference in 0.0..180.0))
            CameraObservedCycle(start, end, frames, min, max, tilt, position, difference)
        })
    }

    private fun cycles(values: List<RepCycle>) = JSONArray(values.map { cycle ->
        JSONObject().put("startNs", cycle.startNs).put("turnNs", cycle.turnaroundNs).put("endNs", cycle.endNs)
            .put("phase", cycle.departurePhase.name).put("pauses", JSONArray(cycle.pauses.map { JSONArray().put(it.startNs).put(it.endNs) }))
    })
    private fun decodeCycles(json: JSONArray): List<RepCycle> = (0 until json.length()).map { index ->
        val value = json.getJSONObject(index); val pauses = value.getJSONArray("pauses")
        RepCycle(value.getLong("startNs"), value.getLong("turnNs"), value.getLong("endNs"), MusclePhase.valueOf(value.getString("phase")),
            (0 until pauses.length()).map { pause -> pauses.getJSONArray(pause).let { RepPause(it.getLong(0), it.getLong(1)) } })
    }
    private fun directory(id: String): File {
        require(id.matches(Regex("paired-[A-Za-z0-9-]{1,100}")))
        return File(context.filesDir, "calibration-captures/$id")
    }
    companion object { private val lock = Any() }
}
