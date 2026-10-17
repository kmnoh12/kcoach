package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RetentionFixtureExtractor::class])
class CalibrationQualityStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun frontCapture(runtime: PatternRuntimeProfile? = null): CalibrationCapture {
        val captures = CalibrationCaptureStore(app)
        val clip = captures.begin("dumbbell_curl", "컬", true, "phone", "OUTSIDE").getOrThrow()
        val image = MutableList(33) { CameraLandmark(.5, .4, 0.0, 1.0, 1.0) }
        image[11] = image[11].copy(x = .6, y = .2); image[12] = image[12].copy(x = .4, y = .2)
        image[23] = image[23].copy(x = .6, y = .6); image[24] = image[24].copy(x = .4, y = .6)
        val world = MutableList(33) { CameraLandmark(0.0, 0.0, 0.0, 1.0, 1.0) }
        world[11] = world[11].copy(x = .2, y = -.5); world[12] = world[12].copy(x = -.2, y = -.5)
        world[23] = world[23].copy(x = .2); world[24] = world[24].copy(x = -.2)
        repeat(40) { i ->
            val ns = 1_000_000_000L + i * 33_333_000L
            captures.appendFrame(clip.id, CameraPoseFrame(i.toLong(), ns, ns, ns / 1_000_000, ns,
                i * 33_333L, 640, 480, 0, true, CameraTimestampSource.PHONE_ELAPSED_REALTIME, "phone",
                listOf(image), listOf(world), true)).getOrThrow()
        }
        captures.videoFile(clip.id).writeText("synthetic-finalized-video frames=40")
        val finished = captures.finish(clip.id, true).getOrThrow()
        assertTrue(finished.videoComplete)
        val records = (0..120).map { i ->
            val ns = 900_000_000L + i * 20_000_000L
            SensorCaptureRecord(SensorSample(ns, 0f, 0f, 9.8f, 0f, 0f, 0f, 3), accelerationTimestampNs = ns,
                gyroscopeTimestampNs = ns, receivedElapsedNs = ns, accelerationAccuracy = 3)
        }
        val raw = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val manifest = WireMessages.captureManifest(clip.id, clip.exerciseId, clip.setId, "raw-quality",
            SensorCaptureFormat.FORMAT_VERSION, "NONE", records.size, 50.0, 50.0, records.first().sample.timestampNs,
            records.last().sample.timestampNs, SensorCaptureFormat.sha256(raw), raw.size.toLong(), 10,
            clip.exerciseId, clip.exerciseName, attemptId = clip.attemptId).let { envelope ->
            envelope.copy(payload = envelope.payload + mapOf("capturePurpose" to "PAIRED_REFERENCE", "watchClockId" to "watch-clock",
                "watchEpochVerified" to "true", "watchDeviceId" to "watch-A", "patternActiveUsed" to (runtime != null).toString(),
                "patternActiveSnapshot" to (runtime?.let { Base64.getEncoder().encodeToString(PatternRuntimeCodec.encode(it)) } ?: "")))
        }
        captures.attachSensor(CaptureInbox(app, app.getSharedPreferences("wear-sync", 0)).receive(manifest, raw).getOrThrow()).getOrThrow()
        return finished
    }
    @Test fun frontFilmingCannotBuildAPersonalPatternEvenWithMatchingWatchAndUserTotals() {
        val clip = frontCapture()
        CalibrationReviewStore(app).saveCount(clip.id, 10, 0).getOrThrow()
        val quality = CalibrationCaptureStore(app).assessQuality(clip.id, 10).getOrThrow()
        assertFalse(quality.eligible); assertEquals(0.0, quality.orientationRatio, 0.0)
        assertTrue(quality.message.contains("방향"))
        val build = PatternCandidateStore(app).buildForCapture(clip.id)
        assertTrue(build.isFailure)
        assertTrue(build.exceptionOrNull()!!.message!!.contains("방향"))
        assertTrue(PatternCandidateStore(app).list().getOrThrow().isEmpty())
        val json = org.json.JSONObject(CalibrationCaptureStore(app).userFile(clip.id, "quality.json").readText())
        assertFalse(json.getBoolean("eligible"))
        assertEquals(10, json.getInt("watchReps")); assertEquals(10, json.getInt("confirmedReps"))
    }
    @Test fun confirmingACaptureAutomaticallyQueuesDisableWhenTheAppliedModelsOriginalsAreInvalid() {
        val candidates = PatternCandidateStore(app)
        val key = PatternCompatibility(candidates.localUserId(), MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch-A", "watch-native-si-v1")
        val profile = ReviewedPatternProfile("unsafe", 1, key,
            listOf(PatternSource("missing-original", "a".repeat(64), 1, "2026-10-14", "dumbbell_curl", "b".repeat(64))),
            setOf("dumbbell_curl"), 3, List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0),
            3_000_000_000, 1_800_000_000, 4_800_000_000, .2, .1)
        candidates.saveCandidate(profile).getOrThrow()
        val runtime = PatternRuntimeProfile.from(profile); val outbox = PatternTransferOutbox(app)
        val command = outbox.enqueueTransition(runtime, PatternProfileOperation.ACTIVATE,
            PatternValidationEvidence("c".repeat(64), "d".repeat(64))).getOrThrow().command
        outbox.acknowledge(PatternProfileAck(command.commandId, command.operation, runtime.id, runtime.version,
            runtime.candidateHash, command.runtimeHash, 1, "watch-A", PatternProfileStatus.APPLIED,
            application = PatternProfileApplication("previous", "set", "attempt", "watch-clock", 10, 11))).getOrThrow()
        val clip = frontCapture(runtime)
        CalibrationReviewStore(app).saveCount(clip.id, 10, 0).getOrThrow()
        val pending = outbox.latestTransition(key).getOrThrow()!!
        assertEquals(PatternProfileOperation.DISABLE, pending.command.operation)
        assertEquals(runtime, pending.command.runtime)
        assertTrue(CalibrationPatternSafetyMonitor(app).notice(clip.id)!!.contains("복귀를 요청"))
        assertNotNull(outbox.activeHistory(key).getOrThrow().firstOrNull()) // Not disabled until actual generic-use ACK.
    }
}
