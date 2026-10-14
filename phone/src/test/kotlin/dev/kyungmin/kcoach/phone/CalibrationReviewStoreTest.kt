package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RetentionFixtureExtractor::class])
class CalibrationReviewStoreTest {
    @Test fun pauseBoundariesMustBeObservedVideoFramesAndOneHeldRepetitionRemainsOne() {
        val app = RuntimeEnvironment.getApplication()
        val clip = finalizedClip()
        val frames = CalibrationCaptureStore(app).frames(clip.id).getOrThrow()
        val times = frames.map { it.cameraTimestampNs }
        val pause = RepPause(times[1], times[3])
        val cycle = RepCycle(times[0], times[2], times[4], MusclePhase.CONCENTRIC, listOf(pause))
        val store = CalibrationReviewStore(app)
        val saved = store.saveCycles(clip.id, listOf(cycle), false, 0).getOrThrow()
        assertEquals(listOf(cycle), store.cameraCycles(clip.id).getOrThrow())
        assertEquals(1, saved.references.size)
        val invalid = cycle.copy(pauses = listOf(pause.copy(startNs = times[1] + 1)))
        assertTrue(store.saveCycles(clip.id, listOf(invalid), false, saved.revision).isFailure)
        assertEquals(saved, store.load(clip.id).getOrThrow())
        assertEquals(frames, CalibrationCaptureStore(app).frames(clip.id).getOrThrow())
    }

    private fun finalizedClip(frameCount: Int = 5): CalibrationCapture {
        val captures = CalibrationCaptureStore(RuntimeEnvironment.getApplication())
        val clip = captures.begin("dumbbell_curl", "컬", true, "phone").getOrThrow()
        val points = List(33) { CameraLandmark(.5, .4, .01, .9, .9) }
        repeat(frameCount) { index ->
            val ns = 1_000_000_000L + index * 33_333_000L
            captures.appendFrame(clip.id, CameraPoseFrame(index.toLong(), ns, ns + 1_000_000, ns / 1_000_000,
                ns + 2_000_000, index * 33_333L, 640, 480, 0, false, CameraTimestampSource.PHONE_ELAPSED_REALTIME,
                "phone", listOf(points), listOf(points), true)).getOrThrow()
        }
        captures.videoFile(clip.id).writeText("synthetic-finalized-video frames=$frameCount")
        return captures.finish(clip.id, true).getOrThrow()
    }

    @Test fun aFullyAlignedHumanPauseKeepsItsCountAndClockButIsNotASupportedPositivePatternTemplate() {
        val app = RuntimeEnvironment.getApplication()
        val captures = CalibrationCaptureStore(app)
        val clip = finalizedClip(17)
        val records = (0..60).map { i ->
            val ns = 900_000_000L + i * 20_000_000L
            SensorCaptureRecord(SensorSample(ns, 0f, 0f, 9.8f, 0f, 0f, 0f, sensorAccuracy = 3),
                accelerationTimestampNs = ns, gyroscopeTimestampNs = ns, receivedElapsedNs = ns + 1_000_000, accelerationAccuracy = 3)
        }
        val raw = java.io.ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val manifest = WireMessages.captureManifest(clip.id, clip.exerciseId, clip.setId, "raw-pause-reference",
            SensorCaptureFormat.FORMAT_VERSION, "NONE", records.size, 50.0, 50.0, records.first().sample.timestampNs,
            records.last().sample.timestampNs, SensorCaptureFormat.sha256(raw), raw.size.toLong(), 0,
            clip.exerciseId, clip.exerciseName, attemptId = clip.attemptId).let {
                it.copy(payload = it.payload + mapOf("capturePurpose" to "PAIRED_REFERENCE", "watchClockId" to "watch", "watchEpochVerified" to "true"))
            }
        captures.attachSensor(CaptureInbox(app, app.getSharedPreferences("wear-sync", 0)).receive(manifest, raw).getOrThrow()).getOrThrow()
        val clocks = CaptureClockStore(app)
        listOf("START" to 900_000_000L, "END" to 2_000_000_000L).forEach { (window, sent) ->
            val request = clocks.prepareProbe(clip.id, window, sent, "phone", true).getOrThrow()
            clocks.acceptReply(ClockProbeProtocol.reply(request, sent + 10_000_000, sent + 12_000_000, "watch", true),
                sent + 22_000_000, "phone").getOrThrow()
        }
        val times = captures.frames(clip.id).getOrThrow().map { it.cameraTimestampNs }
        val cycle = RepCycle(times[0], times[8], times[16], MusclePhase.CONCENTRIC, listOf(RepPause(times[7], times[9])))
        val store = CalibrationReviewStore(app)
        val paused = store.saveCycles(clip.id, listOf(cycle), true, 0).getOrThrow()
        assertTrue(paused.references.single().alignmentVerified)
        assertTrue(paused.references.single().sensorCoverageComplete)
        assertEquals("paused_template_not_supported", paused.references.single().exclusionReason)
        assertFalse(paused.references.single().eligibleForLearning)
        assertEquals(cycle, paused.references.single().cycle)
        val unpaused = store.saveCycles(clip.id, listOf(cycle.copy(pauses = emptyList())), true, paused.revision).getOrThrow()
        assertTrue(unpaused.references.single().eligibleForLearning)
        val restored = store.saveCycles(clip.id, listOf(cycle), true, unpaused.revision).getOrThrow()
        assertFalse(restored.references.single().eligibleForLearning)
        val count = store.saveCount(clip.id, 1, restored.revision).getOrThrow()
        CalibrationVideoRetention(app).discardVideo(clip.id, count.revision, 0).getOrThrow()
        assertEquals(listOf(cycle), CalibrationReviewStore(app).cameraCycles(clip.id).getOrThrow())
        assertEquals(1, store.load(clip.id).getOrThrow().countReference!!.reps)
        assertArrayEquals(raw, captures.sensor(clip.id).getOrThrow().rawFile.readBytes())
    }

    @Test fun reviewedPreparationAndPartialMovementSurviveCountUpdatesAndVideoDeletionWithoutBecomingReps() {
        val app = RuntimeEnvironment.getApplication()
        val clip = finalizedClip()
        val frames = CalibrationCaptureStore(app).frames(clip.id).getOrThrow()
        val intervals = listOf(NonRepInterval(frames[0].cameraTimestampNs, frames[1].cameraTimestampNs, NonRepReason.PREPARATION),
            NonRepInterval(frames[2].cameraTimestampNs, frames[4].cameraTimestampNs, NonRepReason.PARTIAL_REPETITION))
        val store = CalibrationReviewStore(app)
        val saved = store.saveCycles(clip.id, emptyList(), false, 0, nonRepIntervals = intervals).getOrThrow()
        assertTrue(saved.references.isEmpty())
        assertNull(saved.cameraMetrics)
        assertTrue(store.cameraCycles(clip.id).getOrThrow().isEmpty())
        assertEquals(intervals, CalibrationReviewStore(app).load(clip.id).getOrThrow().nonRepIntervals)
        // Reviewing only non-rep movements is a real human review, not a fabricated zero-count label.
        CalibrationVideoRetention(app).discardVideo(clip.id, saved.revision, 0).getOrThrow()
        assertNull(store.load(clip.id).getOrThrow().countReference)
        val count = store.saveCount(clip.id, 0, saved.revision).getOrThrow()
        assertEquals(intervals, store.load(clip.id).getOrThrow().nonRepIntervals)
        val records = (0..5).map { SensorCaptureRecord(SensorSample(1_000_000_000L + it * 20_000_000L, 0f, 0f, 9.8f, 0f, 0f, 0f)) }
        val raw = java.io.ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val manifest = WireMessages.captureManifest(clip.id, clip.exerciseId, clip.setId, "raw-nonrep-export",
            SensorCaptureFormat.FORMAT_VERSION, "NONE", records.size, 50.0, 50.0, records.first().sample.timestampNs,
            records.last().sample.timestampNs, SensorCaptureFormat.sha256(raw), raw.size.toLong(), 0,
            clip.exerciseId, clip.exerciseName, attemptId = clip.attemptId).let {
                it.copy(payload = it.payload + mapOf("capturePurpose" to "PAIRED_REFERENCE", "watchClockId" to "watch", "watchEpochVerified" to "true"))
            }
        val received = CaptureInbox(app, app.getSharedPreferences("wear-sync", 0)).receive(manifest, raw).getOrThrow()
        CalibrationCaptureStore(app).attachSensor(received).getOrThrow()
        val zip = java.io.ByteArrayOutputStream()
        CalibrationEvidenceExport(app).write(clip.id, false, zip).getOrThrow()
        val entries = linkedMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(zip.toByteArray().inputStream()).use { input ->
            while (true) { val entry = input.nextEntry ?: break; entries[entry.name] = input.readBytes() }
        }
        val exported = org.json.JSONObject(entries.getValue("review.json").toString(Charsets.UTF_8))
        assertEquals(2, exported.getJSONArray("nonRepIntervals").length())
        assertEquals(0, exported.getJSONArray("references").length())
        assertEquals(0, exported.getJSONObject("count").getInt("reps"))
        assertArrayEquals(raw, entries.getValue("watch.kcs"))
        assertFalse(store.saveCycles(clip.id, emptyList(), false, count.revision, nonRepIntervals = emptyList()).isSuccess)
        assertEquals(count.revision, store.load(clip.id).getOrThrow().revision)
    }

    @Test fun rejectedExcludedIntervalsDoNotChangeExistingHumanReview() {
        val app = RuntimeEnvironment.getApplication()
        val clip = finalizedClip()
        val times = CalibrationCaptureStore(app).frames(clip.id).getOrThrow().map { it.cameraTimestampNs }
        val store = CalibrationReviewStore(app)
        val cycle = RepCycle(times[0], times[1], times[2], MusclePhase.CONCENTRIC)
        val excluded = NonRepInterval(times[2], times[4], NonRepReason.PUTTING_DOWN)
        val saved = store.saveCycles(clip.id, listOf(cycle), false, 0, nonRepIntervals = listOf(excluded)).getOrThrow()
        assertFalse(store.saveCycles(clip.id, listOf(cycle), false, saved.revision,
            nonRepIntervals = listOf(excluded.copy(startNs = times[1]))).isSuccess)
        assertFalse(store.saveCycles(clip.id, listOf(cycle), false, saved.revision,
            nonRepIntervals = listOf(excluded.copy(startNs = times[2] + 1))).isSuccess)
        assertFalse(store.saveCycles(clip.id, listOf(cycle), false, 0, nonRepIntervals = listOf(excluded)).isSuccess)
        val unchanged = store.saveCycles(clip.id, listOf(cycle), false, saved.revision).getOrThrow()
        assertEquals(listOf(excluded), unchanged.nonRepIntervals)
        assertEquals(1, unchanged.references.size)
    }

    @Test fun countOnlyReviewKeepsExplicitZeroButCannotInventCycleBoundariesOrTrainingLabels() {
        val app = RuntimeEnvironment.getApplication()
        val captures = CalibrationCaptureStore(app)
        val capture = captures.begin("dumbbell_curl", "컬", true, "phone").getOrThrow()
        captures.finish(capture.id, false, "Interrupted").getOrThrow()
        val store = CalibrationReviewStore(app)
        val saved = store.saveCount(capture.id, 0, 0).getOrThrow()
        assertEquals(0, saved.countReference!!.reps)
        assertEquals(1L, saved.revision)
        assertTrue(CalibrationReviewStore(app).load(capture.id).getOrThrow().references.isEmpty())
        assertFalse(store.saveCount(capture.id, 9, 0).isSuccess)
        assertFalse(store.saveCycles(capture.id, listOf(RepCycle(100, 200, 300, MusclePhase.CONCENTRIC)), true, 1).isSuccess)
        assertEquals(0, store.load(capture.id).getOrThrow().countReference!!.reps)
    }
}
