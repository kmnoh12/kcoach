package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import kotlin.test.*

class ReviewedStartWristDirectionTest {
    private val key = StartWristDirectionKey(StartWristContext("external_rotation", "owner", "LEFT", "OUTSIDE",
        "ELBOW", "PALM_UP_HORIZONTAL"), "watch-A")
    private fun interval(id: String, label: StartDirectionLabel, offset: Long = 0,
        selectedKey: StartWristDirectionKey = key, fakeCorrectNegative: Boolean = false): ReviewedStartDirectionInterval {
        val records = (0..120).map { i ->
            val t = 1_000_000_000L + offset + i * 20_000_000L
            val correct = label == StartDirectionLabel.CORRECT_DIRECTION || fakeCorrectNegative
            val gyro = if (label == StartDirectionLabel.EVERYDAY_MOVEMENT && !fakeCorrectNegative) 1.1f else 0f
            SensorCaptureRecord(SensorSample(t, (i % 3 - 1) * 0.02f, 0f, if (correct) -9.80665f else 9.80665f,
                gyro, 0f, 0f, sensorAccuracy = 3), accelerationTimestampNs = t, gyroscopeTimestampNs = t,
                receivedElapsedNs = t + 1_000_000L, accelerationAccuracy = 3)
        }
        val document = SensorCaptureDocument(2, 50.0, records)
        return ReviewedStartDirectionInterval(id, hash(document), if (offset == 0L) "2026-10-14" else "2026-10-15",
            selectedKey, document, records.first().sample.timestampNs, records.last().sample.timestampNs,
            label, StartDirectionReviewSource.HUMAN_VIDEO, 1, 1_000)
    }
    private fun hash(document: SensorCaptureDocument) = SensorCaptureFormat.sha256(ByteArrayOutputStream().apply {
        SensorCaptureFormat.write(this, document.records, document.sampleRateRequestedHz, document.formatVersion)
    }.toByteArray())
    private fun training() = listOf(interval("fit-positive", StartDirectionLabel.CORRECT_DIRECTION),
        interval("fit-opposite", StartDirectionLabel.OPPOSITE_DIRECTION, 5_000_000_000L))
    private fun candidate() = ReviewedStartDirectionBuilder.build(training(), "direction-A", 1, 0.10).getOrThrow()
    private fun heldOut() = StartDirectionLabel.entries.mapIndexed { i, label ->
        interval("check-$i", label, 20_000_000_000L + i * 5_000_000_000L)
    }

    @Test fun aReviewedTargetUsesOnlyTheSpecifiedMountAndKeepsSourceAndMarginEvidence() {
        val profile = candidate()
        assertEquals(key, profile.key)
        assertTrue(profile.target.matches(0.0, 0.0, -1.0))
        assertFalse(profile.target.matches(0.0, 0.0, 1.0))
        assertEquals(0.10, profile.angularMarginRad)
        assertEquals(listOf("fit-positive", "fit-opposite"), profile.sources.map { it.captureId })
        assertTrue(profile.sources.all { it.reviewDigest.matches(Regex("[0-9a-f]{64}")) })
        assertEquals(profile.fingerprint, candidate().fingerprint)
        assertNotEquals(profile.fingerprint, profile.copy(version = 2).fingerprint)
        assertFalse(profile.gateConfig.requireSetupMotion)
        assertEquals(0.75, profile.gateConfig.maxGyroMagnitude)
    }

    @Test fun freshCorrectOppositeAndEverydayCapturesReplayTheRealTwoSecondGate() {
        val report = ReviewedStartDirectionValidation.evaluate(candidate(), heldOut()).getOrThrow()
        assertTrue(report.passed, report.failures.toString())
        assertEquals(listOf(true, false, false), report.rows.map { it.ready })
        assertEquals(2_000L, report.rows.first().firstReadyAfterMs)
        assertEquals(candidate().fingerprint, report.candidateFingerprint)
    }

    @Test fun runtimeSnapshotNeedsAllControlsAndRejectsTruncatedOrExtendedEncoding() {
        val candidate = candidate()
        val report = ReviewedStartDirectionValidation.evaluate(candidate, heldOut()).getOrThrow()
        val runtime = StartDirectionRuntimeProfile.from(candidate, report)
        val encoded = StartDirectionRuntimeCodec.encode(runtime)
        assertEquals(runtime, StartDirectionRuntimeCodec.decode(encoded))
        assertEquals(candidate.gateConfig, runtime.gateConfig)
        assertFailsWith<IllegalArgumentException> { StartDirectionRuntimeProfile.from(candidate, report.copy(rows = report.rows.take(1))) }
        assertFailsWith<IllegalArgumentException> { StartDirectionRuntimeProfile.from(candidate, report.copy(candidateFingerprint = "a".repeat(64))) }
        val bytes = java.util.Base64.getDecoder().decode(encoded)
        assertFails { StartDirectionRuntimeCodec.decode(java.util.Base64.getEncoder().encodeToString(bytes + byteArrayOf(0))) }
        assertFails { StartDirectionRuntimeCodec.decode(java.util.Base64.getEncoder().encodeToString(bytes.dropLast(1).toByteArray())) }
    }

    @Test fun reanalysisKeepsOnlyOriginalSamplesAfterTheCountingBoundaryAndMissingStartIsNotZero() {
        val original = heldOut().first().document
        assertNull(StartDirectionMeasurementWindow.select(original, null))
        assertNull(StartDirectionMeasurementWindow.select(original, original.records.last().sample.timestampNs + 1))
        val boundary = original.records[100].sample.timestampNs
        val selected = assertNotNull(StartDirectionMeasurementWindow.select(original, boundary))
        assertEquals(original.records.drop(100), selected.records)
        assertEquals(121, original.records.size)
        assertEquals(0, selected.validateTimestamps().size)
    }

    @Test fun trainingReuseAndMissingNegativeControlsNeverCountAsIndependentValidation() {
        val reused = ReviewedStartDirectionValidation.evaluate(candidate(), training() + heldOut().last()).getOrThrow()
        assertFalse(reused.passed)
        assertTrue("training_overlap" in reused.failures)
        val noNegatives = ReviewedStartDirectionValidation.evaluate(candidate(), heldOut().take(1)).getOrThrow()
        assertFalse(noNegatives.passed)
        assertTrue("missing_opposite_direction" in noNegatives.failures)
        assertTrue("missing_everyday_movement" in noNegatives.failures)
    }

    @Test fun anEverydayHoldIndistinguishableFromTheTargetFailsRatherThanReceivingAnInventedPostureVerdict() {
        val controls = heldOut().dropLast(1) + interval("ambiguous-day-to-day", StartDirectionLabel.EVERYDAY_MOVEMENT,
            40_000_000_000L, fakeCorrectNegative = true)
        val report = ReviewedStartDirectionValidation.evaluate(candidate(), controls).getOrThrow()
        assertFalse(report.passed)
        assertTrue("negative_direction_accepted" in report.failures)
        assertTrue(report.rows.last().ready)
    }

    @Test fun unknownConditionsDifferentMountsAndUnreviewedIntervalsAreNotTrainingData() {
        assertFailsWith<IllegalArgumentException> { StartWristDirectionKey(key.context.copy(deviceTop = "UNKNOWN"), "watch-A") }
        assertFailsWith<IllegalArgumentException> { StartWristDirectionKey(key.context.copy(variant = "OTHER"), "watch-A") }
        assertFailsWith<IllegalArgumentException> { training().first().copy(reviewSource = null) }
        assertFailsWith<java.time.DateTimeException> { training().first().copy(dayId = "2026-02-31") }
        val opposite = training().last().copy(key = key.copy(context = key.context.copy(deviceTop = "HAND")))
        assertTrue(ReviewedStartDirectionBuilder.build(listOf(training().first(), opposite), "A", 1, 0.1).isFailure)
        assertTrue(ReviewedStartDirectionValidation.evaluate(candidate(), heldOut().map { it.copy(key = key.copy(deviceId = "watch-B")) }).isFailure)
    }

    @Test fun rawEditsUnknownOrUnreliableAccelerationAndStaleOriginalClocksAreRejected() {
        val first = training().first()
        for (kind in 0..3) {
            val edited = first.document.copy(records = first.document.records.mapIndexed { index, record ->
                if (index != 50) record else when (kind) {
                    0 -> record.copy(accelerationAccuracy = 0)
                    1 -> record.copy(accelerationAccuracy = null)
                    2 -> record.copy(accelerationTimestampNs = record.sample.timestampNs - 200_000_000L)
                    else -> record.copy(sample = record.sample.copy(accelerationZ = 8f))
                }
            })
            val changed = first.copy(document = edited, rawSha256 = if (kind == 3) first.rawSha256 else hash(edited))
            assertTrue(ReviewedStartDirectionBuilder.build(listOf(changed, training().last()), "A", 1, 0.1).isFailure, "kind=$kind")
        }
    }

    @Test fun duplicateReferencesRawAliasesAndNonSeparableDirectionsCannotFitATarget() {
        val first = training().first()
        assertTrue(ReviewedStartDirectionBuilder.build(training() + first, "A", 1, 0.1).isFailure)
        assertTrue(ReviewedStartDirectionBuilder.build(training() + first.copy(captureId = "renamed"), "A", 1, 0.1).isFailure)
        val sameDirection = interval("wrong-label", StartDirectionLabel.OPPOSITE_DIRECTION, 5_000_000_000L, fakeCorrectNegative = true)
        assertTrue(ReviewedStartDirectionBuilder.build(listOf(first, sameDirection), "A", 1, 0.1).isFailure)
        assertTrue(ReviewedStartDirectionBuilder.build(training(), "A", 1, 0.0).isFailure)
    }
}
