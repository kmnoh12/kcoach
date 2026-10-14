package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartDirectionReviewStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val store get() = StartDirectionReviewStore(app)
    private fun received(id: String, label: StartDirectionLabel, offset: Long, declared: Boolean = true,
        owner: String = PatternCandidateStore(app).localUserId()): ReceivedCapture {
        val records = (0..120).map { i ->
            val t = 1_000_000_000L + offset + i * 20_000_000L
            SensorCaptureRecord(SensorSample(t, 0f, 0f, if (label == StartDirectionLabel.CORRECT_DIRECTION) -9.80665f else 9.80665f,
                if (label == StartDirectionLabel.EVERYDAY_MOVEMENT) 1.1f else 0f, 0f, 0f, sensorAccuracy = 3),
                accelerationTimestampNs = t, gyroscopeTimestampNs = t, receivedElapsedNs = t + 1_000_000L, accelerationAccuracy = 3)
        }
        val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val context = StartWristContext("external_rotation", owner, "LEFT", "OUTSIDE", "ELBOW", "PALM_UP_HORIZONTAL")
        val manifest = WireMessages.captureManifest("session", "external_rotation", "set-$id", id, 2, "NONE", records.size,
            50.0, 50.0, records.first().sample.timestampNs, records.last().sample.timestampNs, SensorCaptureFormat.sha256(bytes),
            bytes.size.toLong(), 0, "external_rotation", "회전", 0, "attempt-$id").let {
            it.copy(sentAtWallMs = 1_790_000_000_000L + offset / 1_000_000L, payload = it.payload + mapOf(
                "startWristContext" to if (declared) StartWristContextCodec.encode(context) else "",
                "startWristDeviceId" to "watch-A", "watchDeviceId" to "watch-A"))
        }
        return CaptureInbox(app, app.getSharedPreferences("wear-sync", 0)).receive(manifest, bytes).getOrThrow()
    }
    private fun review(id: String, label: StartDirectionLabel, offset: Long): ReceivedCapture {
        val capture = received(id, label, offset)
        val document = capture.validated.document
        store.saveInPersonReview(id, listOf(StartDirectionMark(document.startMonotonicNs!!, document.endMonotonicNs!!, label)), 0).getOrThrow()
        return capture
    }
    private fun candidate() {
        review("fit-correct", StartDirectionLabel.CORRECT_DIRECTION, 0)
        review("fit-opposite", StartDirectionLabel.OPPOSITE_DIRECTION, 5_000_000_000L)
        store.buildCandidate("candidate-A", 1, listOf("fit-correct", "fit-opposite"), 0.1).getOrThrow()
    }
    private fun controls(): List<String> = StartDirectionLabel.entries.mapIndexed { i, label ->
        val id = "held-$i"; review(id, label, 20_000_000_000L + i * 5_000_000_000L); id
    }

    @Test fun candidateAndIndependentReportSurviveReloadWithoutDuplicatingTheRawStore() {
        candidate()
        assertTrue(store.loadValidatedCandidate("candidate-A", 1).isFailure)
        val report = store.validateCandidate("candidate-A", 1, controls()).getOrThrow()
        assertTrue(report.passed)
        assertEquals(report.candidateFingerprint, store.loadValidatedCandidate("candidate-A", 1).getOrThrow().fingerprint)
        assertFalse(store.buildCandidate("candidate-A", 1, listOf("fit-correct", "fit-opposite"), 0.1).isSuccess)
        assertEquals(5, app.filesDir.resolve("captures").listFiles()!!.count { it.name.endsWith(".kcs") })
        assertFalse(app.noBackupFilesDir.listFiles().orEmpty().any { it.name.endsWith(".kcs") })
    }

    @Test fun activationWaitsForANewAttemptAndTheExactSnapshotReceiptRatherThanAnOldWatchAck() {
        candidate(); store.validateCandidate("candidate-A", 1, controls()).getOrThrow()
        val context = store.loadCandidate("candidate-A", 1).getOrThrow().key.context
        val wearing = WorkoutWearingStore(app)
        wearing.beginSession("workout", WorkoutWearingSelection(context.side, context.placement, context.deviceTop,
            mapOf(context.exerciseId to context.variant))).getOrThrow()
        fun start(set: String) = WearSync.richCommand("START", "workout", context.exerciseId, "회전", 0, 8, 5.0, 60,
            setId = set, attemptId = "attempt-$set")
        val first = wearing.bind(start("first")).getOrThrow()
        assertTrue(WearSync.parse(first).getOrNull(20).isNullOrEmpty())
        val profile = store.activate("candidate-A", 1).getOrThrow()
        assertEquals(first, WorkoutWearingStore(app).bind(start("first")).getOrThrow())
        val next = wearing.bind(start("second")).getOrThrow()
        assertEquals(profile, StartDirectionRuntimeCodec.decode(WearSync.parse(next)[20]))
        store.disable(context).getOrThrow()
        assertEquals(next, wearing.bind(start("second")).getOrThrow())
        assertTrue(WearSync.parse(wearing.bind(start("third")).getOrThrow()).getOrNull(20).isNullOrEmpty())
        val outbox = PhoneCommandOutbox(app)
        val queued = WearSync.parse(outbox.enqueue(next)); val revision = queued[10]
        assertFalse(outbox.acknowledge("1|workout|$revision|APPLIED|$revision"))
        assertNotNull(outbox.pending())
        assertFalse(outbox.directionStoredOnWatch(profile))
        assertFalse(outbox.acknowledge("1|workout|$revision|APPLIED|$revision|${"0".repeat(64)}"))
        assertTrue(outbox.acknowledge("1|workout|$revision|APPLIED|$revision|${StartDirectionRuntimeCodec.hash(queued[20])}"))
        assertNull(outbox.pending())
        assertTrue(PhoneCommandOutbox(app).directionStoredOnWatch(profile))
    }

    @Test fun staleReviewAndChangedSourceRevisionInvalidateThePreviouslyValidatedVersion() {
        candidate(); store.validateCandidate("candidate-A", 1, controls()).getOrThrow()
        val original = store.loadReview("fit-correct").getOrThrow()
        val interval = original.intervals.single()
        val mark = StartDirectionMark(interval.startNs, interval.endNs, interval.label)
        assertFalse(store.saveInPersonReview("fit-correct", listOf(mark), 0).isSuccess)
        assertEquals(1L, store.loadReview("fit-correct").getOrThrow().revision)
        store.saveInPersonReview("fit-correct", listOf(mark), 1).getOrThrow()
        assertTrue(store.loadCandidate("candidate-A", 1).isFailure)
        assertTrue(store.loadValidatedCandidate("candidate-A", 1).isFailure)
    }

    @Test fun oldCapturesAndAnotherUsersDeclaredConditionsCannotAcquireNewDirectionLabels() {
        val old = received("old", StartDirectionLabel.CORRECT_DIRECTION, 0, declared = false)
        val foreign = received("foreign", StartDirectionLabel.CORRECT_DIRECTION, 5_000_000_000L, owner = "another-user")
        for (capture in listOf(old, foreign)) {
            assertTrue(store.saveInPersonReview(capture.validated.captureId, listOf(StartDirectionMark(
                capture.validated.document.startMonotonicNs!!, capture.validated.document.endMonotonicNs!!, StartDirectionLabel.CORRECT_DIRECTION)), 0).isFailure)
        }
    }

    @Test fun rawCorruptionAndReusingTrainingCapturesDoNotPreserveASuccessFlag() {
        candidate()
        val ids = controls()
        val reused = store.validateCandidate("candidate-A", 1, listOf("fit-correct", "fit-opposite", ids.last())).getOrThrow()
        assertFalse(reused.passed)
        assertTrue(store.loadValidatedCandidate("candidate-A", 1).isFailure)
        store.validateCandidate("candidate-A", 1, ids).getOrThrow()
        app.filesDir.resolve("captures/${ids.first()}.kcs").writeText("corrupted synthetic fixture")
        assertTrue(store.loadValidatedCandidate("candidate-A", 1).isFailure)
        assertTrue(store.loadReview(ids.first()).isFailure)
    }
}
