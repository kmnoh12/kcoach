package dev.kyungmin.kcoach.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PatternNativeValidationTest {
    @Test fun missingNativeEvidenceCannotBecomeAnApprovedProfile() {
        val key = PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch-A", "watch-native-si-v1")
        val profile = ReviewedPatternProfile("candidate", 1, key,
            listOf(PatternSource("training", "a".repeat(64), 1, "2026-10-14", "dumbbell_curl", "b".repeat(64))),
            setOf("dumbbell_curl"), 3, List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0),
            3_000_000_000, 1_800_000_000, 4_800_000_000, .2, .1)
        val report = PatternNativeValidation.evaluate(profile, emptyList()).getOrThrow()
        assertFalse(report.approved)
        assertNull(report.latencyP95Ms)
        assertTrue("insufficient_sets" in report.failures)
        assertTrue("feedback_not_measured" in report.failures)
    }
    private val key = PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch-A", "watch-native-si-v1")
    private fun source(id: String, day: String, offset: Long, reps: Int): ReviewedPatternSet {
        val records = (0..reps * 150 + 20).map { i ->
            val phase = if (i <= reps * 150) (i % 150) / 150.0 * 2 * PI else 0.0
            val time = offset + i * 20_000_000L
            SensorCaptureRecord(SensorSample(time, (4 * (1 - cos(phase))).toFloat(), 0f, 9.8f,
                (1.2 * sin(phase)).toFloat(), 0f, 0f, 3), accelerationTimestampNs = time,
                gyroscopeTimestampNs = time, receivedElapsedNs = time + 1_000_000)
        }
        val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val refs = (0 until reps).map { i -> RepReference(id, "set-$id", "attempt-$id",
            RepCycle(offset + i * 3_000_000_000, offset + i * 3_000_000_000 + 1_500_000_000,
                offset + (i + 1) * 3_000_000_000, MusclePhase.CONCENTRIC), ReferenceSource.REVIEWED_VIDEO,
            5_000_000, true, true, 100, revision = 1) }
        return ReviewedPatternSet(id, SensorCaptureFormat.sha256(bytes), "dumbbell_curl", key, day,
            SensorCaptureDocument(2, 50.0, records), refs)
    }

    @Test fun qualifyingObservedOutputsCanPassButEmulationOrMissingFeedbackCannot() {
        val profile = ReviewedPatternBuilder.build(listOf(source("training", "2026-10-10", 1_000_000_000, 3)), "candidate", 1).getOrThrow()
        val runtime = PatternRuntimeProfile.from(profile)
        val cases = (0 until 20).map { index ->
            val set = source("heldout-$index", "2026-10-${14 + index % 3}", (index + 1) * 100_000_000_000L, 8)
            val detector = ReviewedPatternDetector(set.exerciseId, key, runtime)
            val events = set.document.records.mapNotNull { detector.add(it.sample) }.map {
                PatternTrialEvent(it.cycle, it.emittedAtNs, it.emittedAtNs + 3_000_000, it.distance)
            }
            val trace = PatternTrialTrace(runtime, set.captureId, "set-${set.captureId}", "attempt-${set.captureId}", "clock-$index", true,
                PatternTrialSource.WATCH_SENSOR_EVENT, set.document.sampleCount.toLong(),
                List(64) { if (it == 22) set.document.sampleCount.toLong() else 0L }, events, false)
            NativePatternCase(HeldOutPatternSet(set, SetCountReference(trace.setId, trace.attemptId, 8, 100)),
                trace, trace.watchClockId, 7, events.map { it.processedAtNs + 20_000_000 }, baselineSnapshotVerified = true)
        }
        // Synthetic contract fixture: this proves policy behavior, not actual Watch8 performance.
        val report = PatternNativeValidation.evaluate(profile, cases).getOrThrow()
        assertTrue(report.approved, report.failures.toString())
        assertEquals(20, report.exactSets)
        assertEquals(160, report.totalReps)
        assertEquals(0.0, report.mae)
        assertFalse(PatternNativeValidation.evaluate(profile, cases.map { it.copy(feedbackNs = null) }).getOrThrow().approved)
        val emulated = cases.map { it.copy(trace = it.trace!!.copy(source = PatternTrialSource.EMULATED)) }
        assertFalse(PatternNativeValidation.evaluate(profile, emulated).getOrThrow().approved)
    }

    private fun nativeCase(profile: ReviewedPatternProfile, stationary: Boolean = false, countOnly: Boolean = false): NativePatternCase {
        val original = source("new-capture", "2026-10-15", 20_000_000_000, 3)
        val records = if (stationary) original.document.records.map { it.copy(sample = it.sample.copy(accelerationX = 0f, gyroscopeX = 0f)) }
            else original.document.records
        val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val set = original.copy(rawSha256 = SensorCaptureFormat.sha256(bytes), document = original.document.copy(records = records),
            references = if (stationary || countOnly) emptyList() else original.references)
        val runtime = PatternRuntimeProfile.from(profile)
        val detector = ReviewedPatternDetector(set.exerciseId, key, runtime)
        val events = records.mapNotNull { detector.add(it.sample) }.map {
            PatternTrialEvent(it.cycle, it.emittedAtNs, it.emittedAtNs + 3_000_000, it.distance)
        }
        val trace = PatternTrialTrace(runtime, set.captureId, "set-${set.captureId}", "attempt-${set.captureId}", "clock", true,
            PatternTrialSource.WATCH_SENSOR_EVENT, records.size.toLong(), List(64) { if (it == 22) records.size.toLong() else 0L }, events, false)
        return NativePatternCase(HeldOutPatternSet(set, SetCountReference(trace.setId, trace.attemptId, if (stationary) 0 else 3, 100)),
            trace, trace.watchClockId, if (stationary) 0 else 2, events.map { it.processedAtNs + 20_000_000 }, baselineSnapshotVerified = true)
    }

    @Test fun nativeCountOnlyAndZeroKeepCountsButCannotSupplyBoundaryOrLatencyEvidence() {
        val profile = ReviewedPatternBuilder.build(listOf(source("training", "2026-10-10", 1_000_000_000, 3)), "candidate", 1).getOrThrow()
        val zero = PatternNativeValidation.evaluate(profile, listOf(nativeCase(profile, stationary = true))).getOrThrow()
        assertEquals(0, zero.rows.single().predicted)
        assertEquals(1, zero.exactSets)
        assertTrue(zero.rows.single().latencyMs.isEmpty())
        assertTrue(zero.rows.single().boundaryErrorsMs.isEmpty())
        assertNull(zero.rows.single().exclusionReason)
        val countOnly = PatternNativeValidation.evaluate(profile, listOf(nativeCase(profile, countOnly = true))).getOrThrow()
        assertEquals(3, countOnly.rows.single().predicted)
        assertTrue(countOnly.rows.single().boundaryErrorsMs.isEmpty())
        assertTrue("boundaries_not_validated" in countOnly.failures)
        assertTrue("latency_not_validated" in countOnly.failures)
    }

    @Test fun nativeBaselineMustMatchTheObservedActiveSnapshotAndItsActualCausalCount() {
        val profile = ReviewedPatternBuilder.build(listOf(source("training", "2026-10-10", 1_000_000_000, 3)), "candidate", 1).getOrThrow()
        val runtime = PatternRuntimeProfile.from(profile)
        val base = nativeCase(profile)
        val personal = base.copy(baselineRuntime = runtime, baselineRuntimeUsed = true, baselineWatchCount = 3,
            baselineSampleCalls = base.heldOut.set.document.sampleCount)
        fun evaluate(case: NativePatternCase, previous: PatternRuntimeProfile? = runtime) =
            PatternNativeValidation.evaluate(profile, listOf(case), previousRuntime = previous).getOrThrow()
        assertEquals(3, evaluate(personal).rows.single().baseline)
        assertEquals(runtime.candidateHash, evaluate(personal).previousHash)
        assertTrue("baseline_snapshot_unverified" in evaluate(personal.copy(baselineSnapshotVerified = false)).failures)
        assertTrue("baseline_usage_mismatch" in evaluate(personal.copy(baselineRuntimeUsed = false)).failures)
        assertTrue("baseline_interrupted" in evaluate(personal.copy(baselineRuntimeInterrupted = true)).failures)
        val missingCalls = evaluate(personal.copy(baselineSampleCalls = null))
        assertNull(missingCalls.rows.single().baseline)
        assertTrue("baseline_samples_incomplete" in missingCalls.failures)
        assertTrue("baseline_samples_incomplete" in evaluate(personal.copy(
            baselineSampleCalls = personal.baselineSampleCalls!! - 1)).failures)
        assertTrue("baseline_samples_incomplete" in evaluate(personal.copy(
            baselineSampleCalls = personal.baselineSampleCalls!! + 1)).failures)
        val wrongCount = evaluate(personal.copy(baselineWatchCount = 2))
        assertNull(wrongCount.rows.single().baseline)
        assertTrue("baseline_replay_mismatch" in wrongCount.failures)
        assertTrue("baseline_snapshot_mismatch" in evaluate(personal, null).failures)
        assertTrue("baseline_snapshot_mismatch" in evaluate(base).failures)
    }

    @Test fun renamedNativeOriginalCannotInflateIndependentEvidenceAndWrongRecipesAreRejected() {
        val profile = ReviewedPatternBuilder.build(listOf(source("training", "2026-10-10", 1_000_000_000, 3)), "candidate", 1).getOrThrow()
        val case = nativeCase(profile, countOnly = true)
        val renamed = case.copy(heldOut = case.heldOut.copy(set = case.heldOut.set.copy(captureId = "renamed-original")))
        assertTrue(PatternNativeValidation.evaluate(profile, listOf(case, renamed)).isFailure)
        assertTrue(PatternNativeValidation.evaluate(profile, listOf(case.copy(heldOut = case.heldOut.copy(
            set = case.heldOut.set.copy(exerciseId = "leg_press"))))).isFailure)
    }

}
