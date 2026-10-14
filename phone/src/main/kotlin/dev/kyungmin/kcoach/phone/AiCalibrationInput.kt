package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

internal data class AiCalibrationInput(val candidate: PatternCandidate, val sets: List<ReviewedPatternSet>,
    val counts: Map<String, Int?>, val json: String, val hash: String, val requestId: String, val evidenceIds: Set<String>,
    val localReplay: PatternCalibrationReplay)

internal class AiCalibrationInputBuilder(private val context: Context) {
    fun build(candidateId: String, checkpoint: () -> Unit = {}): AiCalibrationInput {
        val candidates = PatternCandidateStore(context)
        val candidate = candidates.load(candidateId).getOrThrow()
        val sets = candidates.calibrationTrainingSets(candidateId).getOrThrow()
        val captures = CalibrationCaptureStore(context); val reviews = CalibrationReviewStore(context)
        val counts = sets.associate { it.captureId to reviews.load(it.captureId).getOrThrow().countReference?.reps }
        val local = PatternCalibrationReplayer.replay(candidate.profile, sets, counts, checkpoint)
        val evidence = linkedSetOf("candidate:${candidate.profile.id}:${candidate.sha256}")
        val clips = JSONArray(); val reps = JSONArray()
        var omitted = 0
        var representativeBudgetReached = false
        sets.forEach { set ->
            checkpoint()
            val capture = captures.load(set.captureId).getOrThrow(); val review = reviews.load(set.captureId).getOrThrow()
            val sensor = captures.sensor(set.captureId).getOrThrow(); val frames = captures.frames(set.captureId).getOrThrow()
            val mapping = reviews.alignment(set.captureId).getOrThrow()
            val clipEvidence = "capture:${set.captureId}:${set.rawSha256}"
            evidence.add(clipEvidence)
            clips.put(JSONObject().put("capture_id", set.captureId).put("raw_hash", set.rawSha256)
                .put("review_revision", review.revision).put("reference_digest", ReviewedPatternBuilder.referenceDigest(set.references.filter { it.eligibleForLearning }))
                .put("camera_frames_hash", hashFile(File(captures.videoFile(set.captureId).parentFile, "frames.jsonl"), checkpoint))
                .put("video_hash", hashFile(captures.videoFile(set.captureId), checkpoint))
                .put("actual_count", review.countReference?.reps ?: JSONObject.NULL).put("count_source", "HUMAN_REVIEW")
                .put("sensor_rate_requested_hz", set.document.sampleRateRequestedHz)
                .put("sensor_rate_observed_hz", set.document.records.let { records ->
                    (records.size - 1) * 1e9 / (records.last().sample.timestampNs - records.first().sample.timestampNs) })
                .put("alignment_uncertainty_ms", mapping.uncertaintyNs / 1e6)
                .put("preview_mirrored", frames.firstOrNull()?.previewMirrored ?: JSONObject.NULL)
                .put("camera_view", JSONObject.NULL).put("baseline_profile_snapshot", sensor.manifest.payload[WirePayloadKeys.TRACKING_PROFILE_SNAPSHOT] ?: JSONObject.NULL)
                .put("evidence_id", clipEvidence))
            set.references.filter { it.eligibleForLearning }.forEachIndexed { index, ref ->
                if (reps.length() >= 24 || representativeBudgetReached) { omitted++; return@forEachIndexed }
                checkpoint()
                val cycle = ref.cycle
                val times = (0..5).map { cycle.startNs + (cycle.turnaroundNs - cycle.startNs) * it / 5 } +
                    (1..6).map { cycle.turnaroundNs + (cycle.endNs - cycle.turnaroundNs) * it / 6 }
                val points = times.map { time ->
                    val record = set.document.records.minBy { abs(it.sample.timestampNs - time) }
                    require(abs(record.sample.timestampNs - time) <= 40_000_000)
                    val phoneTime = mapping.phoneTimeNs(time, capture.phoneClockId, mapping.start.watchClockId)
                    val frame = frames.minByOrNull { abs(it.cameraTimestampNs - phoneTime) }
                    val observed = frame?.takeIf { abs(it.cameraTimestampNs - phoneTime) <= 150_000_000 &&
                        CameraObservationQuality.imageLandmarksVisible(it, set.compatibility.recipe.referenceLandmarks(capture.watchOnLeft)) }
                    val sample = record.sample
                    JSONObject().put("watch_time_ns", sample.timestampNs).put("aligned_phone_time_ns", phoneTime)
                        .put("acceleration_m_s2", JSONArray(listOf(sample.accelerationX, sample.accelerationY, sample.accelerationZ)))
                        .put("angular_velocity_rad_s", JSONArray(listOf(sample.gyroscopeX, sample.gyroscopeY, sample.gyroscopeZ)))
                        .put("camera_frame_index", observed?.frameIndex ?: JSONObject.NULL)
                        .put("camera_time_ns", observed?.cameraTimestampNs ?: JSONObject.NULL)
                        .put("image_landmarks", observed?.let { current -> JSONArray(set.compatibility.recipe.referenceLandmarks(capture.watchOnLeft).sorted().map { landmark ->
                            val p = current.imageLandmarks.single()[landmark]
                            JSONObject().put("landmark", landmark).put("x", p.x).put("y", p.y).put("z", p.z)
                                .put("visibility", p.visibility ?: JSONObject.NULL).put("presence", p.presence ?: JSONObject.NULL)
                        }) } ?: JSONObject.NULL)
                }
                val id = "rep:${set.captureId}:$index:${ref.revision}"
                evidence.add(id)
                val row = JSONObject().put("evidence_id", id).put("capture_id", set.captureId).put("source", ref.source.name)
                    .put("start_watch_ns", cycle.startNs).put("turn_watch_ns", cycle.turnaroundNs).put("return_watch_ns", cycle.endNs)
                    .put("alignment_verified", ref.alignmentVerified).put("sensor_coverage_complete", ref.sensorCoverageComplete)
                    .put("points", JSONArray(points))
                val cameraStart = mapping.phoneTimeNs(cycle.startNs, capture.phoneClockId, mapping.start.watchClockId)
                val cameraEnd = mapping.phoneTimeNs(cycle.endNs, capture.phoneClockId, mapping.start.watchClockId)
                val camera = review.cameraMetrics?.cycles?.singleOrNull { abs(it.startNs - cameraStart) <= mapping.uncertaintyNs + 1_000_000 &&
                    abs(it.endNs - cameraEnd) <= mapping.uncertaintyNs + 1_000_000 }
                row.put("camera_observed_metrics", camera?.let { observed -> JSONObject().put("coordinate_scope", "OBSERVED_CAMERA_ESTIMATE")
                    .put("minimum_elbow_angle_deg", observed.minimumElbowAngleDegrees).put("maximum_elbow_angle_deg", observed.maximumElbowAngleDegrees)
                    .put("image_plane_torso_tilt_variation_deg", observed.torsoTiltVariationDegrees)
                    .put("elbow_position_change_shoulder_widths", observed.maximumElbowPositionChangeShoulderWidths)
                    .put("simultaneous_elbow_angle_difference_deg", observed.averageSimultaneousElbowAngleDifferenceDegrees ?: JSONObject.NULL)
                } ?: JSONObject.NULL)
                if (reps.toString().length + row.toString().length > 36_000) {
                    evidence.remove(id); omitted++; representativeBudgetReached = true
                } else reps.put(row)
            }
        }
        val root = JSONObject().put("prompt_version", "measurement_calibration-v1.1").put("local_user_id", candidate.profile.compatibility.localUserId)
            .put("context", JSONObject().put("recipe", candidate.profile.compatibility.recipe.name)
                .put("exercise_ids", JSONArray(candidate.profile.exerciseConditions.sorted())).put("watch_side", candidate.profile.compatibility.side)
                .put("watch_placement", candidate.profile.compatibility.placement).put("device_id", candidate.profile.compatibility.deviceId)
                .put("observability", "카메라 관측은 유효한 해당 프레임의 영상 좌표. 워치는 손목 관측이며 관절 ROM·자세·근육 활성·RIR 확정 불가."))
            .put("captures", clips).put("rep_features", reps).put("representative_reps_omitted", omitted)
            .put("comparison", replayJson(local)).put("training_sets_total", candidate.profile.sources.size)
            .put("training_sets_omitted", candidate.profile.sources.size - sets.size)
            .put("engine_options", JSONArray().put(JSONObject().put("base_candidate_id", candidate.profile.id).put("base_candidate_hash", candidate.sha256)
                .put("engine_id", candidate.profile.engineId).put("parameters", JSONArray(PatternCalibrationParameters.limits(candidate.profile).map { limit ->
                    JSONObject().put("key", limit.key).put("unit", limit.unit).put("min", limit.minimum).put("max", limit.maximum).put("current", limit.current)
                }))))
            .put("evidence_index", JSONArray(evidence.toList())).put("input_scope", "FROZEN_TRAINING_SOURCES_ONLY")
            .put("validation_note", "학습 원본 재생이며 독립 검증/실기 정확도/워치 적용 증거가 아님. 선택 후보의 고정된 학습 원본 중 capture ID 순 첫 6세트만 사용하며 이후 검증 촬영은 조회하지 않음. 원본은 축약/수정하지 않음.")
        val hash = aiCoachHash(root.toString()); val requestId = "calibration-${hash.take(32)}"
        root.put("request", JSONObject().put("request_id", requestId).put("input_hash", hash))
        val json = root.toString(); require(json.length <= 60_000) { "Calibration input exceeds supported size" }
        candidates.checkCurrentSources(candidate.profile); checkpoint()
        return AiCalibrationInput(candidate, sets, counts, json, hash, requestId, evidence, local)
    }
    private fun hashFile(file: File, checkpoint: () -> Unit): String {
        require(file.isFile && file.length() in 1..1_000_000_000)
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(64 * 1024); while (true) {
            checkpoint(); val size = input.read(bytes); if (size < 0) break; hash.update(bytes, 0, size)
        } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun replayJson(replay: PatternCalibrationReplay): JSONObject = JSONObject().put("profile_id", replay.profileId)
    .put("scope", "CAUSAL_PHONE_REPLAY_ON_TRAINING_ONLY").put("count_mae", replay.countMae ?: JSONObject.NULL)
    .put("rows", JSONArray(replay.rows.map { row -> JSONObject().put("capture_id", row.captureId).put("human_count", row.expected ?: JSONObject.NULL)
        .put("estimated_count", row.estimated).put("start_boundary_mae_ms", row.startBoundaryMaeMs ?: JSONObject.NULL)
        .put("turn_boundary_mae_ms", row.turnBoundaryMaeMs ?: JSONObject.NULL).put("return_boundary_mae_ms", row.endBoundaryMaeMs ?: JSONObject.NULL)
        .put("source_emission_delays_ms", JSONArray(row.sourceEmissionDelayMs)) }))

internal data class AiCalibrationProposal(val changes: List<PatternParameterChange>, val reason: String)
internal data class AiCalibrationAnswer(val json: String, val proposals: List<AiCalibrationProposal>) {
    companion object {
        fun parse(text: String, input: AiCalibrationInput): AiCalibrationAnswer {
            require(text.length <= 12_000)
            val json = JSONObject(text)
            json.exactKeys("request_id", "input_hash", "decision", "summary", "findings", "candidate_proposals", "review_suggestions", "limitations")
            require(json.getString("request_id") == input.requestId && json.getString("input_hash") == input.hash)
            val decision = json.getString("decision"); require(decision in setOf("propose", "keep_current", "need_review", "recapture"))
            fun text(row: JSONObject, key: String, limit: Int): String = row.getString(key).also { require(it.isNotBlank() && it.length <= limit) }
            fun refs(row: JSONObject) {
                val ids = row.stringList("evidence_ids", input.evidenceIds.size, 512)
                require(ids.isNotEmpty() && ids.distinct().size == ids.size && ids.all { it in input.evidenceIds })
            }
            text(json, "summary", 600)
            for (key in listOf("findings", "review_suggestions")) json.getJSONArray(key).also { rows ->
                require(rows.length() <= 6)
                for (i in 0 until rows.length()) rows.getJSONObject(i).also { it.exactKeys("text", "evidence_ids"); text(it, "text", 400); refs(it) }
            }
            json.stringList("limitations", 8, 300)
            val proposals = json.getJSONArray("candidate_proposals")
            require(if (decision == "propose") proposals.length() in 1..2 else proposals.length() == 0)
            if (decision == "propose") require(input.counts.values.all { it != null }) { "Review the actual set totals before tuning" }
            val parsed = (0 until proposals.length()).map { i ->
                val row = proposals.getJSONObject(i); row.exactKeys("base_candidate_id", "engine_id", "parameter_changes", "reason", "evidence_ids")
                require(row.getString("base_candidate_id") == input.candidate.profile.id && row.getString("engine_id") == input.candidate.profile.engineId)
                refs(row)
                val changes = row.getJSONArray("parameter_changes"); require(changes.length() in 1..3)
                val values = (0 until changes.length()).map { n -> changes.getJSONObject(n).let { value ->
                    value.exactKeys("key", "value", "unit")
                    val number = value.get("value") as? Number ?: error("Expected a numeric parameter")
                    PatternParameterChange(value.getString("key"), number.toDouble(), value.getString("unit"))
                } }
                PatternCalibrationParameters.apply(input.candidate.profile, values, "checked-proposal", input.candidate.profile.version + 1)
                AiCalibrationProposal(values, text(row, "reason", 400))
            }
            require(parsed.map { it.changes.sortedBy { change -> change.key } }.distinct().size == parsed.size)
            return AiCalibrationAnswer(json.toString(), parsed)
        }
    }
}
