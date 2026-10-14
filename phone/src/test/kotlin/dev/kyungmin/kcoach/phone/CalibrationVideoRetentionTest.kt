package dev.kyungmin.kcoach.phone

import android.media.MediaExtractor
import android.media.MediaFormat
import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.io.ByteArrayOutputStream

/** Only the platform decoder is a fixture; capture finish, review, persistence, and deletion are real paths. */
@Implements(MediaExtractor::class)
class RetentionFixtureExtractor {
    private var index = 0
    private var frameCount = 2
    private var stepUs = 33_333L
    @Implementation fun setDataSource(path: String) {
        val text = File(path).readText(); require(text.startsWith("synthetic-finalized-video")); index = 0
        frameCount = Regex("frames=([0-9]+)").find(text)?.groupValues?.get(1)?.toInt() ?: 2
        stepUs = Regex("step_us=([0-9]+)").find(text)?.groupValues?.get(1)?.toLong() ?: 33_333L
    }
    @Implementation fun getTrackCount() = 1
    @Implementation fun getTrackFormat(track: Int): MediaFormat = MediaFormat.createVideoFormat("video/avc", 640, 480)
    @Implementation fun selectTrack(track: Int) = Unit
    @Implementation fun getSampleTime(): Long = if (index in 0 until frameCount) index * stepUs else -1L
    @Implementation fun advance(): Boolean { index++; return index < frameCount }
    @Implementation fun release() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RetentionFixtureExtractor::class])
class CalibrationVideoRetentionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val captures get() = CalibrationCaptureStore(app)
    private fun clip(): CalibrationCapture {
        val capture = captures.begin("dumbbell_curl", "컬", true, "phone").getOrThrow()
        val points = List(33) { CameraLandmark(.5, .4, .01, .9, .9) }
        repeat(2) { index ->
            val ns = 1_000_000_000L + index * 33_333_000L
            captures.appendFrame(capture.id, CameraPoseFrame(index.toLong(), ns, ns + 1_000_000, ns / 1_000_000,
                ns + 2_000_000, index * 33_333L, 640, 480, 0, false, CameraTimestampSource.PHONE_ELAPSED_REALTIME,
                "phone", listOf(points), listOf(points), true)).getOrThrow()
        }
        captures.videoFile(capture.id).writeText("synthetic-finalized-video fixture; this is not hardware validation")
        return captures.finish(capture.id, true).getOrThrow().also { assertTrue(it.videoComplete) }
    }

    @Test fun anExplicitReviewedChoiceDeletesOnlyTheVideoAndKeepsItsOriginalObservation() {
        val capture = clip()
        val policy = CalibrationVideoRetention(app)
        assertFalse(policy.discardVideo(capture.id, 0, 0).isSuccess)
        assertTrue(captures.videoFile(capture.id).exists())
        val confirmed = CalibrationReviewStore(app).saveCount(capture.id, 0, 0).getOrThrow()
        val originalFrames = captures.frames(capture.id).getOrThrow()
        val originalObservation = policy.observation(capture.id).getOrThrow()
        // A second clip must survive selecting logs-only for the first one.
        val another = clip()
        assertTrue(policy.keepVideo(capture.id).isSuccess)
        assertFalse(policy.discardVideo(capture.id, 0, 0).isSuccess)
        policy.discardVideo(capture.id, confirmed.revision, 0).getOrThrow()
        assertFalse(captures.videoFile(capture.id).exists())
        assertTrue(captures.videoFile(another.id).exists())
        assertTrue(CalibrationVideoRetention(app).logsOnly(capture.id))
        assertEquals(originalObservation, CalibrationVideoRetention(app).observation(capture.id).getOrThrow())
        assertEquals(originalFrames, captures.frames(capture.id).getOrThrow())
        assertEquals(confirmed, CalibrationReviewStore(app).load(capture.id).getOrThrow())
        assertEquals(capture, captures.load(capture.id).getOrThrow())
        assertFalse(policy.keepVideo(capture.id).isSuccess)
        assertTrue(policy.discardVideo(capture.id, confirmed.revision, 0).isSuccess)
        val journal = File(app.filesDir, "calibration-captures/${capture.id}/video-retention.json")
        val interruptedCommit = org.json.JSONObject(journal.readText()).put("choice", "LOGS_ONLY_REQUESTED")
        journal.writeText(interruptedCommit.toString())
        assertTrue(CalibrationVideoRetention(app).logsOnly(capture.id))
        assertEquals(originalObservation, CalibrationVideoRetention(app).observation(capture.id).getOrThrow())
    }

    @Test fun anUnexplainedMissingImageAndLaterFrameEditsCannotAcquireArchivedVideoEvidence() {
        val missing = clip()
        captures.videoFile(missing.id).delete()
        assertFalse(CalibrationVideoRetention(app).logsOnly(missing.id))
        assertTrue(CalibrationVideoRetention(app).observation(missing.id).isFailure)
        val capture = clip()
        CalibrationReviewStore(app).saveCount(capture.id, 0, 0).getOrThrow()
        val policy = CalibrationVideoRetention(app)
        policy.discardVideo(capture.id, 1, 0).getOrThrow()
        val frames = File(app.filesDir, "calibration-captures/${capture.id}/frames.jsonl")
        val rows = frames.readLines().toMutableList()
        rows[0] = CameraFrameCodec.encode(captures.frames(capture.id).getOrThrow().first().copy(modelTimestampMs = 999)).toString()
        frames.writeText(rows.joinToString("\n", postfix = "\n"))
        assertTrue(policy.observation(capture.id).isFailure)
    }

    @Test fun previouslyReviewedVideoDirectionsRemainEvidenceButDeletedImagesCannotSupplyNewLabels() {
        val declared = StartWristContext("external_rotation", PatternCandidateStore(app).localUserId(), "LEFT", "OUTSIDE", "ELBOW", "PALM_UP_HORIZONTAL")
        val capture = captures.begin(declared.exerciseId, "회전", true, "phone", "OUTSIDE", declared).getOrThrow()
        val points = List(33) { CameraLandmark(.5, .4, .01, .9, .9) }
        repeat(122) { i ->
            val ns = 2_020_000_000L + i * 20_000_000L
            captures.appendFrame(capture.id, CameraPoseFrame(i.toLong(), ns, ns + 1_000_000, ns / 1_000_000,
                ns + 2_000_000, i * 20_000L, 640, 480, 0, false, CameraTimestampSource.PHONE_ELAPSED_REALTIME,
                "phone", listOf(points), listOf(points), true)).getOrThrow()
        }
        captures.videoFile(capture.id).writeText("synthetic-finalized-video frames=122 step_us=20000")
        captures.finish(capture.id, true).getOrThrow()
        val records = (0..150).map { i ->
            val ns = 1_000_000_000L + i * 20_000_000L
            SensorCaptureRecord(SensorSample(ns, 0f, 0f, -9.80665f, 0f, 0f, 0f, sensorAccuracy = 3),
                accelerationTimestampNs = ns, gyroscopeTimestampNs = ns, receivedElapsedNs = ns + 1_000_000, accelerationAccuracy = 3)
        }
        val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val manifest = WireMessages.captureManifest(capture.id, declared.exerciseId, capture.setId, "raw-video-direction", 2, "NONE",
            records.size, 50.0, 50.0, records.first().sample.timestampNs, records.last().sample.timestampNs,
            SensorCaptureFormat.sha256(bytes), bytes.size.toLong(), 0, declared.exerciseId, "회전", 0, capture.attemptId).let {
            it.copy(payload = it.payload + mapOf("capturePurpose" to "PAIRED_REFERENCE", "watchClockId" to "watch", "watchDeviceId" to "watch-A",
                "watchEpochVerified" to "true", "startWristDeviceId" to "watch-A", "startWristContext" to StartWristContextCodec.encode(declared)))
        }
        val received = CaptureInbox(app, app.getSharedPreferences("wear-sync", 0)).receive(manifest, bytes).getOrThrow()
        captures.attachSensor(received).getOrThrow()
        val clocks = CaptureClockStore(app)
        listOf("START" to 2_000_000_000L, "END" to 6_000_000_000L).forEach { (window, sent) ->
            val request = clocks.prepareProbe(capture.id, window, sent, "phone", true).getOrThrow()
            val reply = ClockProbeProtocol.reply(request, sent - 990_000_000, sent - 988_000_000, "watch", true)
            clocks.acceptReply(reply, sent + 22_000_000, "phone").getOrThrow()
        }
        val marks = listOf(StartDirectionMark(2_020_000_000L, 4_440_000_000L, StartDirectionLabel.CORRECT_DIRECTION))
        val reviews = StartDirectionReviewStore(app)
        val reviewed = reviews.saveVideoReview(capture.id, marks, true, 0).getOrThrow()
        assertEquals(StartDirectionReviewSource.HUMAN_VIDEO, reviewed.intervals.single().reviewSource)
        assertTrue(reviewed.intervals.single().endNs - reviewed.intervals.single().startNs < marks.single().endNs - marks.single().startNs)
        CalibrationVideoRetention(app).discardVideo(capture.id, 0, 1).getOrThrow()
        assertEquals(reviewed, StartDirectionReviewStore(app).loadReview(capture.id).getOrThrow())
        assertTrue(reviews.saveVideoReview(capture.id, marks, true, 1).isFailure)
        assertEquals(1L, reviews.reviewRevision(capture.id).getOrThrow())
        assertArrayEquals(bytes, received.rawFile.readBytes())
    }
}
