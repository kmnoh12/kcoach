package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class CalibrationReview(val captureId: String, val revision: Long, val countReference: SetCountReference?,
    val references: List<RepReference>, val cameraMetrics: CameraObservedMetrics? = null,
    val nonRepIntervals: List<NonRepInterval> = emptyList(), val squatMetrics: CameraSquatMetrics? = null)

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
            // Labels made from video windows follow the user's total, so a corrected total discards them for a rebuild.
            val rebuild = current.references.size > actualReps && confirmationSource(id) == SOURCE_VIDEO_WINDOWS
            require(rebuild || current.references.size <= actualReps) { "Confirmed boundaries exceed the actual total" }
            val count = SetCountReference(capture.setId, capture.attemptId, actualReps, System.currentTimeMillis())
            val next = if (rebuild) current.copy(revision = current.revision + 1, countReference = count, references = emptyList(),
                cameraMetrics = null, squatMetrics = null)
            else current.copy(revision = current.revision + 1, countReference = count)
            write(next, if (rebuild) emptyList() else null)
            runCatching { PhoneWorkoutService.send(context, WearSync.PATH_CALIBRATION_CAMERA_STATE,
                CalibrationCameraState(capture.id, capture.setId, capture.attemptId, next.revision + 2, false, actualReps).encode()) }
            next
        }
    }.onSuccess { CalibrationPatternSafetyMonitor(context).check(id) }

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

    fun saveCycles(id: String, cycles: List<RepCycle>, alignmentReviewed: Boolean, expectedRevision: Long,
        simultaneousArmsReviewed: Boolean = false, nonRepIntervals: List<NonRepInterval>? = null,
        confirmationSource: String = SOURCE_USER_REVIEWED): Result<CalibrationReview> = synchronized(lock) {
        runCatching {
            require(confirmationSource == SOURCE_USER_REVIEWED || confirmationSource == SOURCE_VIDEO_WINDOWS)
            val capture = captures.load(id).getOrThrow()
            require(capture.status == CalibrationCaptureStatus.REVIEW_PENDING && capture.videoComplete)
            val video = ReferenceVideoEvidence.inspect(captures.videoFile(id)).getOrThrow()
            val current = load(id).getOrThrow()
            require(current.revision == expectedRevision && cycles.size <= 1_000)
            require(current.countReference == null || cycles.size <= current.countReference.reps)
            require(cycles.zipWithNext().all { (a, b) -> b.startNs >= a.endNs })
            require(capture.motionEndCameraNs == null || cycles.all { it.endNs <= capture.motionEndCameraNs }) {
                "Repetition crosses the capture end hold"
            }
            val frames = captures.frames(id).getOrThrow()
            val written = frames.filter { it.videoFrameWritten }
            require(written.size == video.presentationTimesUs.size && written.zip(video.presentationTimesUs).all { (frame, pts) ->
                kotlin.math.abs(frame.videoPresentationUs - pts) <= 1_000
            })
            val frameTimes = written.map { it.cameraTimestampNs }.toSet()
            val excluded = nonRepIntervals ?: current.nonRepIntervals
            require(excluded.size <= 1_000 && excluded.zipWithNext().all { (a, b) -> a.endNs <= b.startNs })
            require(excluded.all { interval -> interval.startNs in frameTimes && interval.endNs in frameTimes &&
                cycles.none { it.startNs < interval.endNs && interval.startNs < it.endNs } })
            val contract = ExerciseMeasurementContracts.resolve(capture.exerciseId)
            require(cycles.all { it.departurePhase == contract.departurePhase &&
                it.startNs in frameTimes && it.turnaroundNs in frameTimes && it.endNs in frameTimes &&
                it.pauses.all { pause -> pause.startNs in frameTimes && pause.endNs in frameTimes } })
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
                val boundariesReviewed = confirmationSource == SOURCE_USER_REVIEWED
                RepReference(id, capture.setId, capture.attemptId, sensorCycle ?: cycle, ReferenceSource.REVIEWED_VIDEO,
                    mapping?.uncertaintyNs?.plus(1_000_000L), boundariesReviewed && alignmentReviewed && mapping != null && cameraGood,
                    covered, reviewAt, when { !cameraGood -> "camera_quality"; sensorCycle == null -> "alignment_missing";
                        !boundariesReviewed -> VIDEO_BOUNDARIES_NOT_REVIEWED;
                        !alignmentReviewed -> "alignment_not_reviewed"; !covered -> "sensor_gap";
                        cycle.pauses.isNotEmpty() -> "paused_template_not_supported"; else -> null }, current.revision + 1)
            }
            val eligibleCameraCycles = cycles.filterIndexed { index, _ -> references[index].let { ref ->
                ref.eligibleForLearning || ref.exclusionReason == "paused_template_not_supported" &&
                    ref.copy(exclusionReason = null).eligibleForLearning
            } }
            val squat = contract.recipe == MeasurementRecipe.SQUAT
            val metrics = if (squat) null else CameraObservedAnalyzer.analyze(frames, eligibleCameraCycles, capture.watchOnLeft, simultaneousArmsReviewed)
            val squatMetrics = if (squat) CameraSquatAnalyzer.analyze(frames, eligibleCameraCycles, capture.watchOnLeft) else null
            val next = current.copy(revision = current.revision + 1, references = references, cameraMetrics = metrics,
                nonRepIntervals = excluded, squatMetrics = squatMetrics)
            write(next, cycles, confirmationSource)
            next
        }
    }

    /** Who produced the saved boundaries. A confirmed total does not confirm individual video boundaries. */
    fun confirmationSource(id: String): String = synchronized(lock) {
        runCatching {
            val current = load(id).getOrThrow()
            if (current.revision == 0L) SOURCE_USER_REVIEWED
            else JSONObject(File(directory(id), "review-${current.revision}.json").readText()).optString("confirmationSource", SOURCE_USER_REVIEWED)
        }.getOrDefault(SOURCE_USER_REVIEWED)
    }

    /**
     * Saves the video's repetition windows as unreviewed candidates when they match the confirmed total
     * (after dropping rejected windows). Clock, camera and sensor checks still apply, but these candidates
     * cannot become learning labels until the user reviews their boundaries through [saveCycles].
     * Boundaries the user marked by hand are never replaced. Returns null when nothing was promoted.
     */
    fun promoteVideoProposals(id: String, rejected: Set<RepSpan>, proposals: List<RepCycle>? = null): Result<CalibrationReview?> = synchronized(lock) {
        runCatching {
            val current = load(id).getOrThrow()
            val count = current.countReference?.reps ?: return@runCatching null
            if (current.references.isNotEmpty() && confirmationSource(id) != SOURCE_VIDEO_WINDOWS) return@runCatching null
            val capture = captures.load(id).getOrThrow()
            if (capture.status != CalibrationCaptureStatus.REVIEW_PENDING || !capture.videoComplete) return@runCatching null
            val written = captures.motionFrames(id).getOrThrow().filter { it.videoFrameWritten }
            val found = proposals ?: CameraRepProposals.propose(written, capture.exerciseId, capture.watchOnLeft)
            val byNs = written.associateBy { it.cameraTimestampNs }
            val spans = found.map { cycle ->
                val start = byNs[cycle.startNs]?.videoPresentationUs; val end = byNs[cycle.endNs]?.videoPresentationUs
                if (start == null || end == null || end <= start) null else RepSpan((start / 1000).toInt(), (end / 1000).toInt())
            }
            val selected = RepPromotion.select(found, spans, rejected, count) ?: return@runCatching null
            if (alignment(id).getOrNull() == null) return@runCatching null
            if (current.references.isNotEmpty() && cameraCycles(id).getOrNull() == selected) return@runCatching null
            saveCycles(id, selected, true, current.revision, confirmationSource = SOURCE_VIDEO_WINDOWS).getOrThrow()
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

    private fun write(review: CalibrationReview, cameraCycles: List<RepCycle>? = null, source: String? = null) {
        val json = JSONObject().put("schemaVersion", 1).put("captureId", review.captureId).put("revision", review.revision)
            .put("count", review.countReference?.let { JSONObject().put("reps", it.reps).put("reviewedAtMs", it.reviewedAtMs) } ?: JSONObject.NULL)
            .put("cameraCycles", cycles(cameraCycles ?: cameraCycles(review.captureId).getOrThrow()))
            .put("nonRepIntervals", JSONArray(review.nonRepIntervals.map { interval -> JSONObject()
                .put("startNs", interval.startNs).put("endNs", interval.endNs).put("reason", interval.reason.name) }))
            .put("cameraObservedMetrics", review.cameraMetrics?.let(::encodeMetrics) ?: JSONObject.NULL)
            .put("cameraSquatMetrics", review.squatMetrics?.let(::encodeSquatMetrics) ?: JSONObject.NULL)
            .put("cameraProposalOrigin", context.getSharedPreferences("camera-rep-proposals", Context.MODE_PRIVATE).getString(review.captureId, null)?.let(::JSONArray) ?: JSONObject.NULL)
            .put("confirmationSource", source ?: confirmationSource(review.captureId))
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
        // Older versions treated a matching total as boundary review. Keep the candidates, but revoke that assumption.
        val boundariesReviewed = json.optString("confirmationSource", SOURCE_USER_REVIEWED) == SOURCE_USER_REVIEWED
        val refs = json.getJSONArray("references")
        return CalibrationReview(capture.id, revision, count, (0 until refs.length()).map { index ->
            val ref = refs.getJSONObject(index)
            RepReference(capture.id, capture.setId, capture.attemptId, decodeCycles(JSONArray().put(ref.getJSONObject("cycle"))).single(),
                ReferenceSource.REVIEWED_VIDEO, if (ref.isNull("uncertaintyNs")) null else ref.getLong("uncertaintyNs"),
                boundariesReviewed && ref.getBoolean("alignmentVerified"), ref.getBoolean("sensorCovered"), ref.getLong("reviewedAtMs"),
                if (!boundariesReviewed) VIDEO_BOUNDARIES_NOT_REVIEWED else if (ref.isNull("exclusion")) null else ref.getString("exclusion"), revision)
        }, if (!boundariesReviewed || json.isNull("cameraObservedMetrics") || !json.has("cameraObservedMetrics")) null else decodeMetrics(json.getJSONObject("cameraObservedMetrics")),
            (json.optJSONArray("nonRepIntervals") ?: JSONArray()).let { rows ->
                require(rows.length() <= 1_000)
                List(rows.length()) { index -> rows.getJSONObject(index).let {
                    NonRepInterval(it.getLong("startNs"), it.getLong("endNs"), NonRepReason.valueOf(it.getString("reason")))
                } }
            }, if (!boundariesReviewed || json.isNull("cameraSquatMetrics") || !json.has("cameraSquatMetrics")) null else decodeSquatMetrics(json.getJSONObject("cameraSquatMetrics")))
    }

    private fun encodeSquatMetrics(metrics: CameraSquatMetrics) = JSONObject().put("schemaVersion", 1)
        .put("source", "REVIEWED_SQUAT_CAMERA").put("watchOnLeft", metrics.watchOnLeft)
        .put("coordinateBasis", "RELATIVE_WORLD_JOINT_ANGLES_AND_FIXED_IMAGE_INITIAL_TORSO_LENGTH")
        .put("cycles", JSONArray(metrics.cycles.map { row -> JSONObject()
            .put("startNs", row.startNs).put("turnNs", row.turnaroundNs).put("endNs", row.endNs).put("frames", row.frames)
            .put("kneeMin", row.minimumKneeDegrees).put("kneeMax", row.maximumKneeDegrees)
            .put("hipMin", row.minimumHipDegrees).put("hipMax", row.maximumHipDegrees)
            .put("torsoTiltChange", row.torsoTiltVariationDegrees).put("pelvisSideways", row.pelvisSidewaysTorsoLengths)
            .put("pelvisDrop", row.pelvisDropTorsoLengths).put("returnOffset", row.returnOffsetTorsoLengths)
            .put("descendingMs", row.descendingMs).put("ascendingMs", row.ascendingMs)
            .put("markedPauseMs", row.markedPauseMs ?: JSONObject.NULL) }))

    private fun decodeSquatMetrics(json: JSONObject): CameraSquatMetrics {
        require(json.getInt("schemaVersion") == 1 && json.getString("source") == "REVIEWED_SQUAT_CAMERA" &&
            json.getString("coordinateBasis") == "RELATIVE_WORLD_JOINT_ANGLES_AND_FIXED_IMAGE_INITIAL_TORSO_LENGTH")
        val rows = json.getJSONArray("cycles"); require(rows.length() in 1..600)
        return CameraSquatMetrics(json.getBoolean("watchOnLeft"), List(rows.length()) { index -> rows.getJSONObject(index).let {
            CameraSquatCycle(it.getLong("startNs"), it.getLong("turnNs"), it.getLong("endNs"), it.getInt("frames"),
                it.getDouble("kneeMin"), it.getDouble("kneeMax"), it.getDouble("hipMin"), it.getDouble("hipMax"),
                it.getDouble("torsoTiltChange"), it.getDouble("pelvisSideways"), it.getDouble("pelvisDrop"), it.getDouble("returnOffset"),
                it.getLong("descendingMs"), it.getLong("ascendingMs"), if (it.isNull("markedPauseMs")) null else it.getLong("markedPauseMs"))
        } })
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
        require(id.matches(Regex("(?:calibration-)?paired-[A-Za-z0-9-]{1,100}")))
        return File(context.filesDir, "calibration-captures/$id")
    }
    companion object {
        private val lock = Any()
        const val SOURCE_USER_REVIEWED = "USER_REVIEWED_VIDEO"
        const val SOURCE_VIDEO_WINDOWS = "VIDEO_WINDOWS_MATCHING_CONFIRMED_COUNT"
        const val VIDEO_BOUNDARIES_NOT_REVIEWED = "video_boundaries_not_reviewed"
    }
}
