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
class CalibrationReviewPromotionTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun clip(withSensor: Boolean): CalibrationCapture {
        val captures = CalibrationCaptureStore(app)
        val clip = captures.begin("dumbbell_curl", "컬", true, "phone").getOrThrow()
        val points = List(33) { CameraLandmark(.5, .4, .01, .9, .9) }
        repeat(17) { index ->
            val ns = 1_000_000_000L + index * 33_333_000L
            captures.appendFrame(clip.id, CameraPoseFrame(index.toLong(), ns, ns + 1_000_000, ns / 1_000_000,
                ns + 2_000_000, index * 33_333L, 640, 480, 0, false, CameraTimestampSource.PHONE_ELAPSED_REALTIME,
                "phone", listOf(points), listOf(points), true)).getOrThrow()
        }
        captures.videoFile(clip.id).writeText("synthetic-finalized-video frames=17")
        val finished = captures.finish(clip.id, true).getOrThrow()
        if (withSensor) {
            val records = (0..60).map { i ->
                val ns = 900_000_000L + i * 20_000_000L
                SensorCaptureRecord(SensorSample(ns, 0f, 0f, 9.8f, 0f, 0f, 0f, sensorAccuracy = 3),
                    accelerationTimestampNs = ns, gyroscopeTimestampNs = ns, receivedElapsedNs = ns + 1_000_000, accelerationAccuracy = 3)
            }
            val raw = java.io.ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
            val manifest = WireMessages.captureManifest(clip.id, clip.exerciseId, clip.setId, "raw-promotion",
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
        }
        return finished
    }

    private fun cycles(id: String, count: Int): List<RepCycle> {
        val times = CalibrationCaptureStore(app).frames(id).getOrThrow().map { it.cameraTimestampNs }
        return (0 until count).map { RepCycle(times[it * 4], times[it * 4 + 2], times[it * 4 + 4], MusclePhase.CONCENTRIC) }
    }

    @Test fun matchingTotalsKeepVideoCandidatesWithoutApprovingLearningLabels() {
        val id = clip(true).id; val store = CalibrationReviewStore(app)
        val counted = store.saveCount(id, 3, 0).getOrThrow()
        val promoted = store.promoteVideoProposals(id, emptySet(), cycles(id, 3)).getOrThrow()!!
        assertEquals(counted.revision + 1, promoted.revision)
        assertEquals(3, promoted.references.size)
        assertTrue(promoted.references.none { it.eligibleForLearning })
        assertTrue(promoted.references.all { it.exclusionReason == CalibrationReviewStore.VIDEO_BOUNDARIES_NOT_REVIEWED })
        assertNull(promoted.cameraMetrics)
        assertNull(promoted.squatMetrics)
        assertTrue(store.load(id).getOrThrow().references.none { it.eligibleForLearning })
        assertEquals(CalibrationReviewStore.SOURCE_VIDEO_WINDOWS, store.confirmationSource(id))
        // Running it again with the same inputs does not pile up revisions.
        assertNull(store.promoteVideoProposals(id, emptySet(), cycles(id, 3)).getOrThrow())
        assertEquals(promoted.revision, store.load(id).getOrThrow().revision)
    }

    @Test fun manuallyReviewedBoundariesCanReplaceAutomaticCandidates() {
        val id = clip(true).id; val store = CalibrationReviewStore(app)
        store.saveCount(id, 3, 0).getOrThrow()
        val candidates = store.promoteVideoProposals(id, emptySet(), cycles(id, 3)).getOrThrow()!!
        val reviewed = store.saveCycles(id, cycles(id, 3), true, candidates.revision).getOrThrow()
        assertTrue(reviewed.references.all { it.eligibleForLearning })
        assertTrue(store.load(id).getOrThrow().references.all { it.eligibleForLearning })
        assertEquals(CalibrationReviewStore.SOURCE_USER_REVIEWED, store.confirmationSource(id))
    }

    @Test fun legacyAutomaticLabelsLoseLearningEligibilityOnLoad() {
        val id = clip(true).id; val store = CalibrationReviewStore(app)
        store.saveCount(id, 3, 0).getOrThrow()
        val saved = store.promoteVideoProposals(id, emptySet(), cycles(id, 3)).getOrThrow()!!
        val file = java.io.File(app.filesDir, "calibration-captures/$id/review-${saved.revision}.json")
        val json = org.json.JSONObject(file.readText())
        val references = json.getJSONArray("references")
        repeat(references.length()) { index ->
            references.getJSONObject(index).put("alignmentVerified", true).put("exclusion", org.json.JSONObject.NULL)
        }
        file.writeText(json.toString())
        assertTrue(store.load(id).getOrThrow().references.all {
            !it.eligibleForLearning && it.exclusionReason == CalibrationReviewStore.VIDEO_BOUNDARIES_NOT_REVIEWED
        })
    }

    @Test fun aTotalThatDoesNotMatchLeavesTheReviewUntouched() {
        val id = clip(true).id; val store = CalibrationReviewStore(app)
        val counted = store.saveCount(id, 4, 0).getOrThrow()
        assertNull(store.promoteVideoProposals(id, emptySet(), cycles(id, 3)).getOrThrow())
        assertEquals(counted.revision, store.load(id).getOrThrow().revision)
        assertTrue(store.load(id).getOrThrow().references.isEmpty())
    }

    @Test fun withoutAPassingClockAlignmentNothingIsPromoted() {
        val id = clip(false).id; val store = CalibrationReviewStore(app)
        store.saveCount(id, 3, 0).getOrThrow()
        assertNull(store.promoteVideoProposals(id, emptySet(), cycles(id, 3)).getOrThrow())
        assertTrue(store.load(id).getOrThrow().references.isEmpty())
    }

    @Test fun boundariesTheUserDrewByHandAreNeverReplaced() {
        val id = clip(true).id; val store = CalibrationReviewStore(app)
        val counted = store.saveCount(id, 3, 0).getOrThrow()
        val manual = store.saveCycles(id, cycles(id, 1), true, counted.revision).getOrThrow()
        assertNull(store.promoteVideoProposals(id, emptySet(), cycles(id, 3)).getOrThrow())
        assertEquals(manual.revision, store.load(id).getOrThrow().revision)
        assertEquals(CalibrationReviewStore.SOURCE_USER_REVIEWED, store.confirmationSource(id))
    }

    @Test fun correctingTheTotalDiscardsPromotedLabelsAndRebuildsThemFromTheNewTotal() {
        val id = clip(true).id; val store = CalibrationReviewStore(app)
        store.saveCount(id, 3, 0).getOrThrow()
        store.promoteVideoProposals(id, emptySet(), cycles(id, 3)).getOrThrow()!!
        val lowered = store.saveCount(id, 2, store.load(id).getOrThrow().revision).getOrThrow()
        assertEquals(2, lowered.countReference!!.reps)
        assertTrue(lowered.references.isEmpty())
        assertTrue(store.cameraCycles(id).getOrThrow().isEmpty())
        val rebuilt = store.promoteVideoProposals(id, emptySet(), cycles(id, 2)).getOrThrow()!!
        assertEquals(2, rebuilt.references.size)
    }

    @Test fun correctingTheTotalBelowHandDrawnBoundariesIsStillRefused() {
        val id = clip(true).id; val store = CalibrationReviewStore(app)
        val counted = store.saveCount(id, 3, 0).getOrThrow()
        val manual = store.saveCycles(id, cycles(id, 2), true, counted.revision).getOrThrow()
        assertTrue(store.saveCount(id, 1, manual.revision).isFailure)
    }
}
