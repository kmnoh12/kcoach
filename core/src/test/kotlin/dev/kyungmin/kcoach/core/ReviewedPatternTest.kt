package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReviewedPatternTest {
    private val key = PatternCompatibility("local-user", MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch-A", "watch-native-si-v1")

    private fun training(source: ReferenceSource = ReferenceSource.REVIEWED_VIDEO): ReviewedPatternSet {
        val records = (0..450).map { i ->
            val t = 1_000_000_000L + i * 20_000_000L
            val p = (i % 150) / 150.0
            val x = (4 * (1 - cos(2 * PI * p))).toFloat()
            SensorCaptureRecord(SensorSample(t, x, 0f, 9.8f, (1.2 * sin(2 * PI * p)).toFloat(), 0f, 0f),
                accelerationTimestampNs = t, gyroscopeTimestampNs = t, receivedElapsedNs = t + 1_000_000)
        }
        val document = SensorCaptureDocument(2, 50.0, records)
        val raw = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val references = (0..2).map { i -> RepReference("capture-A", "set-A", "attempt-A",
            RepCycle(1_000_000_000L + i * 3_000_000_000L, 2_500_000_000L + i * 3_000_000_000L,
                4_000_000_000L + i * 3_000_000_000L, MusclePhase.CONCENTRIC), source,
            5_000_000, true, true, 100, revision = 1) }
        return ReviewedPatternSet("capture-A", SensorCaptureFormat.sha256(raw), "dumbbell_curl", key,
            "2026-10-14", document, references)
    }

    @Test fun confirmedVideoCyclesTeachARecipePatternButAutomaticAgreementCannotTeachIt() {
        val result = ReviewedPatternBuilder.build(listOf(training()), "candidate-1", 1)
        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        val profile = result.getOrThrow()
        assertEquals(3, profile.trainingRepCount)
        assertEquals(setOf("dumbbell_curl"), profile.exerciseConditions)
        assertEquals(listOf("capture-A"), profile.sources.map { it.captureId })
        assertEquals(3_000_000_000L, profile.medianDurationNs)
        assertEquals(key, profile.compatibility)
        assertTrue(ReviewedPatternBuilder.build(listOf(training(ReferenceSource.WATCH_CANDIDATE)), "candidate-2", 1).isFailure)
    }

    @Test fun aLearnedPatternCountsNewUnevenTempoCyclesAndDoesNotInventHalfDurationPhases() {
        val profile = ReviewedPatternBuilder.build(listOf(training()), "candidate-1", 1).getOrThrow()
        val records = (0..480).map { i ->
            val time = i * 0.02
            val position = time - 1.0
            val p = position % 3.6
            val phase = if (position < 0 || position > 7.2) 0.0 else if (p <= 1.2) p / 1.2 * PI else PI + (p - 1.2) / 2.4 * PI
            SensorCaptureRecord(SensorSample(20_000_000_000L + i * 20_000_000L,
                (4 * (1 - cos(phase))).toFloat(), 0f, 9.8f, (1.2 * sin(phase)).toFloat(), 0f, 0f))
        }
        val result = ReviewedPatternAnalyzer.analyze(SensorCaptureDocument(2, 50.0, records), "barbell_biceps_curl", key, profile)
        assertEquals(2, result.estimatedCount)
        assertTrue(result.cycles.all { it.concentricMs!! < it.eccentricMs!! })
        assertTrue(result.cycles.zipWithNext().all { (a, b) -> a.endNs <= b.startNs })
        assertNull(ReviewedPatternAnalyzer.analyze(SensorCaptureDocument(2, 50.0, records), "barbell_biceps_curl",
            key.copy(side = "RIGHT"), profile).estimatedCount)
        val half = records.take(125)
        assertEquals(0, ReviewedPatternAnalyzer.analyze(SensorCaptureDocument(2, 50.0, half), "dumbbell_curl", key, profile).estimatedCount)
    }

    @Test fun trainingReplaysCannotPassAsIndependentValidationAndSmallNewSetsRemainPending() {
        val training = training()
        val profile = ReviewedPatternBuilder.build(listOf(training), "candidate-1", 1).getOrThrow()
        val count = SetCountReference("set-A", "attempt-A", 3, 100)
        val reused = ReviewedPatternValidation.evaluate(profile, listOf(HeldOutPatternSet(training, count))).getOrThrow()
        assertTrue("training_overlap" in reused.failures)
        assertFalse(reused.countGatePassed)
        val newRecords = training.document.records.map { r ->
            r.copy(sample = r.sample.copy(timestampNs = r.sample.timestampNs + 30_000_000_000),
                accelerationTimestampNs = r.accelerationTimestampNs!! + 30_000_000_000,
                gyroscopeTimestampNs = r.gyroscopeTimestampNs!! + 30_000_000_000,
                receivedElapsedNs = r.receivedElapsedNs!! + 30_000_000_000)
        }
        val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, newRecords, 50.0) }.toByteArray()
        val heldOut = training.copy(captureId = "new-capture", rawSha256 = SensorCaptureFormat.sha256(bytes),
            document = training.document.copy(records = newRecords), references = training.references.map { ref ->
                ref.copy(captureId = "new-capture", cycle = ref.cycle.copy(startNs = ref.cycle.startNs + 30_000_000_000,
                    turnaroundNs = ref.cycle.turnaroundNs + 30_000_000_000, endNs = ref.cycle.endNs + 30_000_000_000)) })
        val report = ReviewedPatternValidation.evaluate(profile, listOf(HeldOutPatternSet(heldOut, count))).getOrThrow()
        assertEquals(1, report.totalSets)
        assertEquals(3, report.totalReps)
        assertEquals(2, report.rows.single().causalCount,
            "Replay must not append imaginary rest samples to finish the last pending cycle")
        assertTrue("insufficient_sets" in report.failures && "insufficient_days" in report.failures && "insufficient_reps" in report.failures)
        assertFalse(report.countGatePassed)
        val missing = ReviewedPatternValidation.evaluate(profile, listOf(HeldOutPatternSet(heldOut, count)),
            listOf(UnscoredPatternCapture("missing-raw", "2026-10-15", "dumbbell_curl", null, "sensor_not_received"))).getOrThrow()
        assertEquals(2, missing.totalSets)
        assertEquals(1, missing.unknownReferenceSets)
        assertEquals(0.5, missing.coverage)
        assertFalse(missing.countGatePassed)
    }
    private fun newCountOnlySet(stationary: Boolean = false): ReviewedPatternSet {
        val set = training()
        val records = set.document.records.map { record ->
            record.copy(sample = record.sample.copy(timestampNs = record.sample.timestampNs + 30_000_000_000,
                accelerationX = if (stationary) 0f else record.sample.accelerationX, gyroscopeX = if (stationary) 0f else record.sample.gyroscopeX),
                accelerationTimestampNs = record.accelerationTimestampNs!! + 30_000_000_000,
                gyroscopeTimestampNs = record.gyroscopeTimestampNs!! + 30_000_000_000,
                receivedElapsedNs = record.receivedElapsedNs!! + 30_000_000_000)
        }
        val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        return set.copy(captureId = "count-only", rawSha256 = SensorCaptureFormat.sha256(bytes),
            document = set.document.copy(records = records), references = emptyList())
    }

    @Test fun countOnlyTruthAndActualZeroAreScoredWithoutInventingBoundaries() {
        val profile = ReviewedPatternBuilder.build(listOf(training()), "candidate-1", 1).getOrThrow()
        val countOnly = ReviewedPatternValidation.evaluate(profile, listOf(HeldOutPatternSet(newCountOnlySet(),
            SetCountReference("set-A", "attempt-A", 3, 100)))).getOrThrow()
        assertEquals(3, countOnly.rows.single().candidateCount)
        assertNull(countOnly.rows.single().exclusionReason)
        assertTrue(countOnly.rows.single().startErrorsMs.isEmpty())
        assertTrue(countOnly.rows.single().causalSourceEmissionDelaysMs.isEmpty())
        assertTrue("boundaries_not_validated" in countOnly.failures)
        val zero = ReviewedPatternValidation.evaluate(profile, listOf(HeldOutPatternSet(newCountOnlySet(true),
            SetCountReference("set-A", "attempt-A", 0, 100)))).getOrThrow()
        assertEquals(0, zero.rows.single().candidateCount)
        assertEquals(1, zero.exactSets)
        assertEquals(0.0, zero.rawMae)
        assertTrue(zero.rows.single().endErrorsMs.isEmpty())
        val missing = ReviewedPatternValidation.evaluate(profile, emptyList(),
            listOf(UnscoredPatternCapture("missing", "2026-10-15", "dumbbell_curl", null, "no_truth"))).getOrThrow()
        assertEquals(0, missing.exactSets)
        assertEquals(1, missing.unknownReferenceSets)
        assertThrows(IllegalArgumentException::class.java) { SetCountReference("set", "attempt", -1, 100) }
    }

    @Test fun previousActiveRuntimeIsComparedOnTheSameUnpaddedInputAndInvalidRawIsNotZero() {
        val profile = ReviewedPatternBuilder.build(listOf(training()), "candidate-1", 1).getOrThrow()
        val runtime = PatternRuntimeProfile.from(profile)
        val set = newCountOnlySet()
        val report = ReviewedPatternValidation.evaluate(profile, listOf(HeldOutPatternSet(set,
            SetCountReference("set-A", "attempt-A", 3, 100))), previousRuntime = runtime).getOrThrow()
        assertEquals(2, report.rows.single().baselineCount)
        assertEquals(runtime.candidateHash, report.previousHash)
        assertTrue(ReviewedPatternValidation.evaluate(profile, emptyList(), previousRuntime = runtime.copy(
            compatibility = key.copy(side = "RIGHT"))).isFailure)
        val records = set.document.records.map { it.copy(sample = it.sample.copy(sensorAccuracy = 0)) }
        val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val broken = set.copy(rawSha256 = SensorCaptureFormat.sha256(bytes), document = set.document.copy(records = records))
        val invalid = ReviewedPatternValidation.evaluate(profile, listOf(HeldOutPatternSet(broken,
            SetCountReference("set-A", "attempt-A", 0, 100)))).getOrThrow()
        assertNull(invalid.rows.single().candidateCount)
        assertEquals("invalid_or_incomplete_signal", invalid.rows.single().exclusionReason)
        assertEquals(0, invalid.exactSets)
    }

    @Test fun renamingTheSameOriginalDoesNotCreateAnotherIndependentValidationSet() {
        val profile = ReviewedPatternBuilder.build(listOf(training()), "candidate-1", 1).getOrThrow()
        val set = newCountOnlySet()
        val count = SetCountReference("set-A", "attempt-A", 3, 100)
        assertTrue(ReviewedPatternValidation.evaluate(profile, listOf(HeldOutPatternSet(set, count),
            HeldOutPatternSet(set.copy(captureId = "renamed-original"), count))).isFailure)
        assertTrue(ReviewedPatternValidation.evaluate(profile, listOf(HeldOutPatternSet(
            set.copy(exerciseId = "leg_press"), count))).isFailure)
    }

    @Test fun aFrozenCandidateRoundTripsWithItsSourcesAndRejectsUnknownSchemaOrTrailingBytes() {
        val profile = ReviewedPatternBuilder.build(listOf(training()), "candidate-1", 1).getOrThrow()
        val bytes = ReviewedPatternCodec.encode(profile)
        assertEquals(profile, ReviewedPatternCodec.decode(bytes))
        assertThrows(IllegalArgumentException::class.java) { ReviewedPatternCodec.decode(bytes + byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { ReviewedPatternCodec.decode(bytes.copyOf().apply { this[3] = 99 }) }
    }

    @Test fun theWatchCandidateUsesOnlyDeliveredSamplesAndReportsDelayedCompleteReturns() {
        val set = training()
        val profile = ReviewedPatternBuilder.build(listOf(set), "candidate-1", 1).getOrThrow()
        val detector = ReviewedPatternDetector("barbell_biceps_curl", key, profile)
        val stream = set.document.records.map { it.sample.copy(timestampNs = it.sample.timestampNs + 30_000_000_000) } +
            (1..20).map { i -> set.document.records.last().sample.copy(timestampNs = 40_000_000_000L + i * 20_000_000) }
        val emitted = stream.mapNotNull { sample -> detector.add(sample)?.also { detection ->
            assertEquals(sample.timestampNs, detection.emittedAtNs)
            assertTrue(detection.cycle.endNs <= sample.timestampNs)
            assertTrue(sample.timestampNs - detection.cycle.endNs <= 500_000_000)
        } }
        assertEquals(3, emitted.size)
        assertTrue(emitted.zipWithNext().all { (a, b) -> a.cycle.endNs <= b.cycle.startNs })
    }

    @Test fun theWatchSnapshotKeepsTheCandidateIdentityWithoutShippingItsTrainingArchive() {
        val set = training()
        val profile = ReviewedPatternBuilder.build(listOf(set), "candidate-1", 1).getOrThrow()
        val runtime = PatternRuntimeProfile.from(profile)
        val bytes = PatternRuntimeCodec.encode(runtime)
        assertTrue(bytes.size < 4096)
        assertEquals(SensorCaptureFormat.sha256(ReviewedPatternCodec.encode(profile)), runtime.candidateHash)
        assertEquals(runtime, PatternRuntimeCodec.decode(bytes))
        val detector = ReviewedPatternDetector("dumbbell_curl", key, PatternRuntimeCodec.decode(bytes))
        val stream = set.document.records.map { it.sample } + (1..20).map { i ->
            set.document.records.last().sample.copy(timestampNs = 10_000_000_000L + i * 20_000_000) }
        assertEquals(3, stream.mapNotNull { detector.add(it) }.size)
    }

    @Test fun phoneReanalysisUsesTheFrozenPersonalEngineAndObservedUnequalPhases() {
        val runtime = PatternRuntimeProfile.from(ReviewedPatternBuilder.build(listOf(training()), "candidate-1", 1).getOrThrow())
        val records = (0..480).map { index ->
            val t = index * .02; val position = t - 1.0; val p = position % 3.6
            val phase = if (position < 0 || position > 7.2) 0.0 else if (p <= 1.2) p / 1.2 * PI else PI + (p - 1.2) / 2.4 * PI
            SensorCaptureRecord(SensorSample(20_000_000_000L + index * 20_000_000L,
                (4 * (1 - cos(phase))).toFloat(), 0f, 9.8f, (1.2 * sin(phase)).toFloat(), 0f, 0f))
        }
        val document = SensorCaptureDocument(2, 50.0, records)
        val result = PhoneRepAnalyzer.analyzePersonal(document, "dumbbell_curl", key, runtime, 2, used = true, interrupted = false)
        assertEquals(2, result.result.phoneReanalyzedReps)
        assertEquals(2, result.result.confirmedReps)
        assertTrue(result.events.all { it.concentricMs!! < it.eccentricMs!! })
        assertNull(result.result.relativeRom)
        assertNull(result.result.wristMotionRange)
        val wrongHand = PhoneRepAnalyzer.analyzePersonal(document, "dumbbell_curl", key.copy(side = "RIGHT"), runtime, 2, true, false)
        assertNull(wrongHand.result.phoneReanalyzedReps)
        assertNull(PhoneRepAnalyzer.analyzePersonal(document, "dumbbell_curl", key, runtime, 2, true, true).result.confirmedReps)
    }

}
