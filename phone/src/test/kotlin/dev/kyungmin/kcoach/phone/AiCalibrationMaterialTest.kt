package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic persisted contracts; this deliberately does not claim native camera/video/device validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiCalibrationMaterialTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun source(): Pair<CalibrationCapture, PatternCandidate> {
        val captures = CalibrationCaptureStore(app)
        val capture = captures.begin("dumbbell_curl", "컬", true, "phone", "OUTSIDE").getOrThrow()
        val records = (0..450).map { i ->
            val time = 1_000_000_000L + i * 20_000_000L; val p = (i % 150) / 150.0
            SensorCaptureRecord(SensorSample(time, (4 * (1 - cos(2 * PI * p))).toFloat(), 0f, 9.8f,
                (1.2 * sin(2 * PI * p)).toFloat(), 0f, 0f), accelerationTimestampNs = time,
                gyroscopeTimestampNs = time, receivedElapsedNs = time + 1_000_000)
        }
        val raw = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val manifest = WireMessages.captureManifest(capture.id, capture.exerciseId, capture.setId, "source-raw", 2, "NONE", records.size,
            50.0, 50.0, records.first().sample.timestampNs, records.last().sample.timestampNs, SensorCaptureFormat.sha256(raw), raw.size.toLong(), 0,
            capture.exerciseId, capture.exerciseName, attemptId = capture.attemptId).let { it.copy(payload = it.payload + mapOf(
                "capturePurpose" to "PAIRED_REFERENCE", "watchClockId" to "watch", "watchEpochVerified" to "true", "watchDeviceId" to "watch-device",
                WirePayloadKeys.TRACKING_PROFILE_SNAPSHOT to TrackingProfileSnapshot.encode(ExerciseTrackingProfiles.resolve(capture.exerciseId)))) }
        val received = CaptureInbox(app, app.getSharedPreferences("wear-sync", Context.MODE_PRIVATE)).receive(manifest, raw).getOrThrow()
        captures.attachSensor(received).getOrThrow()
        val points = List(33) { CameraLandmark(.5, .4, .01, .9, .9) }
        val frames = records.mapIndexed { i, record ->
            val time = record.sample.timestampNs + 2_000_000_000
            CameraPoseFrame(i.toLong(), time, time + 1_000_000, i * 20_000L, time + 2_000_000, 3_000_000_000,
                640, 480, 0, true, CameraTimestampSource.PHONE_ELAPSED_REALTIME, "phone", listOf(points), listOf(points), true)
        }
        val video = captures.videoFile(capture.id)
        video.writeText("synthetic reference bytes; not a video decode test")
        captures.finish(capture.id, false, "synthetic contract fixture").getOrThrow()
        val metadata = File(video.parentFile, "capture.json")
        metadata.writeText(JSONObject(metadata.readText()).put("status", "REVIEW_PENDING").put("videoComplete", true).toString())
        File(video.parentFile, "frames.jsonl").writeText(frames.joinToString("\n", postfix = "\n") { CameraFrameCodec.encode(it).toString() })
        val clock = CaptureClockStore(app)
        for ((window, offset) in listOf("START" to 0L, "END" to 9_000_000_000L)) {
            val request = clock.prepareProbe(capture.id, window, 3_000_000_000 + offset, "phone", true).getOrThrow()
            val reply = ClockProbeProtocol.reply(request, 1_005_000_000 + offset, 1_006_000_000 + offset, "watch", true)
            clock.acceptReply(reply, 3_011_000_000 + offset, "phone").getOrThrow()
        }
        val review = JSONObject().put("schemaVersion", 1).put("captureId", capture.id).put("revision", 1)
            .put("count", JSONObject().put("reps", 3).put("reviewedAtMs", 100)).put("cameraCycles", JSONArray())
            .put("references", JSONArray((0..2).map { i -> JSONObject()
                .put("cycle", JSONObject().put("startNs", 1_000_000_000L + i * 3_000_000_000L)
                    .put("turnNs", 2_500_000_000L + i * 3_000_000_000L).put("endNs", 4_000_000_000L + i * 3_000_000_000L)
                    .put("phase", "CONCENTRIC").put("pauses", JSONArray()))
                .put("uncertaintyNs", 5_500_000).put("alignmentVerified", true).put("sensorCovered", true).put("reviewedAtMs", 100)
                .put("exclusion", JSONObject.NULL) }))
        File(video.parentFile, "review-1.json").writeText(review.toString())
        val store = PatternCandidateStore(app)
        val refs = CalibrationReviewStore(app).load(capture.id).getOrThrow().references
        val key = PatternCompatibility(store.localUserId(), MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch-device", "watch-native-si-v1")
        val set = ReviewedPatternSet(capture.id, SensorCaptureFormat.sha256(raw), capture.exerciseId, key,
            java.time.Instant.ofEpochMilli(capture.createdAtMs).atOffset(java.time.ZoneOffset.UTC).toLocalDate().toString(), SensorCaptureDocument(2, 50.0, records), refs)
        return capture to store.saveCandidate(ReviewedPatternBuilder.build(listOf(set), "base-real-store", 1).getOrThrow()).getOrThrow()
    }
    @Test fun inputReadsVerifiedTrainingOriginalsAndAlignedPointsWithoutAccessingLaterValidationClips() {
        val (capture, candidate) = source()
        val later = CalibrationCaptureStore(app).begin("dumbbell_curl", "DO_NOT_READ_HELD_OUT", true, "phone", "OUTSIDE").getOrThrow()
        CalibrationCaptureStore(app).finish(later.id, false, "held-out fixture").getOrThrow()
        val input = AiCalibrationInputBuilder(app).build(candidate.profile.id)
        val root = JSONObject(input.json)
        assertEquals(1, root.getJSONArray("captures").length()); assertFalse(input.json.contains(later.id)); assertFalse(input.json.contains("DO_NOT_READ_HELD_OUT"))
        assertEquals("FROZEN_TRAINING_SOURCES_ONLY", root.getString("input_scope"))
        assertEquals(capture.id, input.sets.single().captureId)
        val points = root.getJSONArray("rep_features").getJSONObject(0).getJSONArray("points")
        assertEquals(12, points.length()); assertFalse(points.getJSONObject(0).isNull("image_landmarks"))
        assertEquals(points.getJSONObject(0).getLong("watch_time_ns") + 2_000_000_000, points.getJSONObject(0).getLong("aligned_phone_time_ns"))
        assertTrue(input.json.length <= 60_000)
        assertEquals(input.hash, AiCalibrationInputBuilder(app).build(candidate.profile.id).hash)
        CalibrationCaptureStore(app).videoFile(capture.id).appendText("changed")
        assertNotEquals(input.hash, AiCalibrationInputBuilder(app).build(candidate.profile.id).hash)
    }
    @Test fun calibratedVariantsReuseTheExistingVersionedStoreAndRefuseChangedReviewSources() {
        val (capture, candidate) = source(); val store = PatternCandidateStore(app)
        val changes = listOf(PatternParameterChange("maximum_distance", .3, "normalized_dtw"))
        val variant = store.saveCalibrationVariant(candidate.profile.id, candidate.sha256, changes, "variant").getOrThrow()
        assertEquals(2L, variant.profile.version); assertEquals(candidate.profile.sources, variant.profile.sources)
        assertEquals(variant, store.saveCalibrationVariant(candidate.profile.id, candidate.sha256, changes, "variant").getOrThrow())
        assertFalse(store.saveCalibrationVariant(candidate.profile.id, "b".repeat(64), changes, "another").isSuccess)
        CalibrationReviewStore(app).saveCount(capture.id, 4, 1).getOrThrow()
        assertFalse(store.saveCalibrationVariant(candidate.profile.id, candidate.sha256, changes, "changed-review").isSuccess)
        assertTrue(PatternAdoptionController(app).activate(variant.profile.id).isFailure)
    }
}
