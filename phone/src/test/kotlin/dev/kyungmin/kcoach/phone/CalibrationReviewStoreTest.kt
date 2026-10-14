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
    private fun finalizedClip(): CalibrationCapture {
        val captures = CalibrationCaptureStore(RuntimeEnvironment.getApplication())
        val clip = captures.begin("dumbbell_curl", "컬", true, "phone").getOrThrow()
        val points = List(33) { CameraLandmark(.5, .4, .01, .9, .9) }
        repeat(5) { index ->
            val ns = 1_000_000_000L + index * 33_333_000L
            captures.appendFrame(clip.id, CameraPoseFrame(index.toLong(), ns, ns + 1_000_000, ns / 1_000_000,
                ns + 2_000_000, index * 33_333L, 640, 480, 0, false, CameraTimestampSource.PHONE_ELAPSED_REALTIME,
                "phone", listOf(points), listOf(points), true)).getOrThrow()
        }
        captures.videoFile(clip.id).writeText("synthetic-finalized-video frames=5")
        return captures.finish(clip.id, true).getOrThrow()
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
