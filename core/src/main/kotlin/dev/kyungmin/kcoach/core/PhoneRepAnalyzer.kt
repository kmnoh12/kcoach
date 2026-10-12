package dev.kyungmin.kcoach.core

import kotlin.math.sqrt

/**
 * Deterministic phone-side reanalysis of a KCS capture.
 *
 * This is intentionally profile-driven. A profile may refuse automatic counting
 * when the exercise is not observable from a wrist IMU (for example a machine
 * leg press), instead of reporting a convincing but false number.
 */
object PhoneRepAnalyzer {
    data class Output(
        val events: List<RepEvent>,
        val result: SetAnalysisResult,
    )

    fun analyze(document: SensorCaptureDocument, watchDetectedReps: Int? = null): Output =
        analyze(document, "", "", watchDetectedReps)

    fun analyze(
        document: SensorCaptureDocument,
        exerciseId: String,
        exerciseName: String = "",
        watchDetectedReps: Int? = null,
    ): Output {
        val profile = ExerciseTrackingProfiles.resolve(exerciseId, exerciseName)
        return analyze(document, profile, watchDetectedReps)
    }

    fun analyze(
        document: SensorCaptureDocument,
        profile: ExerciseTrackingProfile,
        watchDetectedReps: Int? = null,
    ): Output {
        if (profile.mode == TrackingMode.MANUAL_REQUIRED) return sanitizeOutput(emptyOutput(watchDetectedReps, profile))
        if (!document.sampleRateRequestedHz.isFinite() || document.sampleRateRequestedHz <= 0.0 ||
            document.validateTimestamps().isNotEmpty()) return sanitizeOutput(emptyOutput(watchDetectedReps, profile))
        val records = document.records
        if (records.size < 3) return sanitizeOutput(emptyOutput(watchDetectedReps, profile))
        if (profile.signal == TrackingSignal.FOREARM_ROTATION) {
            return analyzeExternalRotation(document, profile, watchDetectedReps)
        }

        val detector = ProfiledMotionRepDetector(profile)
        val motionEvents = buildList {
            records.forEach { record -> detector.add(record.sample)?.let(::add) }
            detector.finish()?.let(::add)
        }
        val events = motionEvents.mapIndexed { index, event ->
            RepEvent(
                repIndex = index + 1,
                startTimestampNs = event.startTimestampNs,
                turnaroundTimestampNs = event.turnaroundTimestampNs,
                endTimestampNs = event.endTimestampNs,
                durationMs = event.durationMs,
                concentricMs = event.durationMs / 2L,
                eccentricMs = event.durationMs - event.durationMs / 2L,
                amplitude = event.motionDisplacement,
                peakAngularVelocity = event.peakMotionRate,
                confidence = event.confidence,
            )
        }

        val durations = events.map { it.durationMs.toDouble() }
        val amplitudes = events.map { it.amplitude }
        val heartRates = records.mapNotNull { it.heartRateBpm }.filter { it in 20..240 }
        val avgDuration = durations.average().takeUnless { it.isNaN() }
        val avgAmplitude = amplitudes.average().takeUnless { it.isNaN() }
        val tempoVariance = variance(durations)
        val amplitudeVariance = variance(amplitudes)
        val rawConfidence = events.map { it.confidence }.average().takeUnless { it.isNaN() } ?: 0.0
        val signalQuality = calculateSignalQuality(records, document.sampleRateRequestedHz)
        val confidence = (rawConfidence * signalQuality * if (profile.mode == TrackingMode.WRIST_IMU) 0.95 else 0.85)
            .coerceIn(0.0, 1.0)
        val consistency = scoreConsistency(durations, amplitudes)
        val stability = (confidence * 100.0).toInt().coerceIn(0, 100)
        val angularSpeeds = events.map { it.peakAngularVelocity }
        val averageAngularSpeed = angularSpeeds.average().takeUnless { it.isNaN() }
        val relativeRom = if (amplitudes.isEmpty()) null else {
            (amplitudes.average() / (profile.minimumLobeDisplacement * 2.0).coerceAtLeast(0.001))
                .coerceIn(0.0, 1.0)
        }
        val trackingStatus = if (signalQuality < 0.85) "센서 기록 품질 부족 · 반복 수 확인 필요" else when (profile.mode) {
            TrackingMode.WRIST_IMU -> "손목 IMU 자동 추정"
            TrackingMode.WRIST_IMU_CAUTION -> "손목 기반 추정 · 실제 기준 세트 검증 필요"
            TrackingMode.MANUAL_REQUIRED -> "수동 입력 필요"
        }
        val result = SetAnalysisResult(
            watchDetectedReps = watchDetectedReps,
            phoneReanalyzedReps = events.size,
            confirmedReps = events.size.takeIf { confidence >= 0.72 && it > 0 },
            repSource = RepSource.CUSTOM_IMU,
            averageRepDurationMs = avgDuration,
            tempoVariance = tempoVariance,
            amplitudeVariance = amplitudeVariance,
            stabilityScore = stability,
            consistencyScore = consistency,
            lateSetDegradation = lateSetDegradation(durations),
            averageHeartRate = heartRates.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            peakHeartRate = heartRates.maxOrNull(),
            analysisConfidence = confidence,
            algorithmVersion = profile.id,
            trackingMode = profile.mode,
            trackingProfileId = profile.id,
            trackingStatus = trackingStatus,
            averageAngularSpeed = averageAngularSpeed,
            relativeRom = relativeRom,
            repConfidence = confidence,
            capabilityTier = profile.capabilityTier,
            wristMotionSpeed = averageAngularSpeed,
            wristMotionRange = relativeRom,
            tempoConsistency = consistency,
            signalQuality = signalQuality,
        )
        return Output(events, CapabilityOutputPolicy.sanitize(result))
    }

    private fun sanitizeOutput(output: Output): Output =
        output.copy(result = CapabilityOutputPolicy.sanitize(output.result))

    private fun analyzeExternalRotation(
        document: SensorCaptureDocument,
        profile: ExerciseTrackingProfile,
        watchDetectedReps: Int?,
    ): Output {
        val detector = ForearmRotationRepDetector(profile)
        val rotationEvents = buildList {
            document.records.forEach { record ->
                detector.add(record.sample)?.let(::add)
            }
            detector.finish()?.let(::add)
        }
        val events = rotationEvents.mapIndexed { index, event ->
            RepEvent(
                repIndex = index + 1,
                startTimestampNs = event.startTimestampNs,
                turnaroundTimestampNs = event.turnaroundTimestampNs,
                endTimestampNs = event.endTimestampNs,
                durationMs = event.durationMs,
                concentricMs = event.durationMs / 2L,
                eccentricMs = event.durationMs - event.durationMs / 2L,
                amplitude = event.angularDisplacement,
                peakAngularVelocity = event.peakAngularVelocity,
                confidence = event.confidence,
            )
        }
        val durations = events.map { it.durationMs.toDouble() }
        val amplitudes = events.map { it.amplitude }
        val heartRates = document.records.mapNotNull { it.heartRateBpm }.filter { it in 20..240 }
        val signalQuality = calculateSignalQuality(document.records, document.sampleRateRequestedHz)
        val rawConfidence = events.map { it.confidence }.average().takeUnless { it.isNaN() } ?: 0.0
        val confidence = (rawConfidence * signalQuality * 0.90).coerceIn(0.0, 1.0)
        val trackingStatus = if (signalQuality < 0.85) "센서 기록 품질 부족 · 반복 수 확인 필요" else "손목 관측 외회전 · 회전축 왕복 신호 · 손목 IMU가 팔꿈치 고정/관절 ROM을 확인하지 않음 · Watch8 실물/영상 검증 필요"
        val result = SetAnalysisResult(
            watchDetectedReps = watchDetectedReps,
            phoneReanalyzedReps = events.size,
            confirmedReps = events.size.takeIf { confidence >= 0.72 && it > 0 },
            repSource = RepSource.CUSTOM_IMU,
            averageRepDurationMs = durations.average().takeUnless { it.isNaN() },
            tempoVariance = variance(durations),
            amplitudeVariance = variance(amplitudes),
            stabilityScore = (confidence * 100.0).toInt().coerceIn(0, 100),
            consistencyScore = scoreConsistency(durations, amplitudes),
            lateSetDegradation = lateSetDegradation(durations),
            averageHeartRate = heartRates.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            peakHeartRate = heartRates.maxOrNull(),
            analysisConfidence = confidence,
            algorithmVersion = profile.id,
            trackingMode = profile.mode,
            trackingProfileId = profile.id,
            trackingStatus = trackingStatus,
            averageAngularSpeed = events.map { it.peakAngularVelocity }.average().takeUnless { it.isNaN() },
            relativeRom = null,
            repConfidence = confidence,
            capabilityTier = profile.capabilityTier,
            wristMotionSpeed = events.map { it.peakAngularVelocity }.average().takeUnless { it.isNaN() },
            wristMotionRange = null,
            tempoConsistency = scoreConsistency(durations, amplitudes),
            signalQuality = signalQuality,
        )
        return Output(events, CapabilityOutputPolicy.sanitize(result))
    }

    private fun emptyOutput(watchDetectedReps: Int?, profile: ExerciseTrackingProfile): Output {
        val manual = profile.mode == TrackingMode.MANUAL_REQUIRED
        return Output(
            events = emptyList(),
            result = SetAnalysisResult(
                watchDetectedReps = watchDetectedReps,
                phoneReanalyzedReps = null,
                confirmedReps = null,
                repSource = if (manual) RepSource.MANUAL else RepSource.CUSTOM_IMU,
                averageRepDurationMs = null,
                tempoVariance = null,
                amplitudeVariance = null,
                stabilityScore = 0,
                consistencyScore = 0,
                lateSetDegradation = null,
                averageHeartRate = null,
                peakHeartRate = null,
                analysisConfidence = 0.0,
                algorithmVersion = profile.id,
                trackingMode = profile.mode,
                trackingProfileId = profile.id,
                trackingStatus = if (manual) "손목 IMU만으로 관측 불가 · 수동 입력 필요" else "샘플 부족 · 재측정 필요",
                repConfidence = 0.0,
                capabilityTier = profile.capabilityTier,
                signalQuality = 0.0,
                tempoConsistency = 0,
            ),
        )
    }

    private fun calculateSignalQuality(records: List<SensorCaptureRecord>, requestedHz: Double): Double {
        if (records.size < 2 || !requestedHz.isFinite() || requestedHz <= 0.0) return 0.0
        val intervalsNs = records.zipWithNext { a, b ->
            b.sample.timestampNs.toDouble() - a.sample.timestampNs.toDouble()
        }
        if (intervalsNs.any { it <= 0.0 || !it.isFinite() }) return 0.0
        val expectedIntervalNs = 1_000_000_000.0 / requestedHz
        // Use elapsed coverage, not the observed median: uniformly lost samples can still look regular.
        // Averaging also avoids treating ordinary short/long scheduling jitter as missing samples.
        val coverage = (intervalsNs.size * expectedIntervalNs / intervalsNs.sum()).coerceIn(0.0, 1.0)
        val gapRate = intervalsNs.count { it > expectedIntervalNs * 2.5 }.toDouble() / intervalsNs.size
        val reliableFraction = 1.0 - records.count { it.sample.sensorAccuracy == 0 }.toDouble() / records.size
        var quality = (coverage * (1.0 - gapRate) * reliableFraction).coerceIn(0.0, 1.0)
        // The motion detector resets across >450ms. Remaining clear reps cannot prove a complete set.
        if (intervalsNs.any { it > 450_000_000.0 }) quality = quality.coerceAtMost(0.70)
        return quality
    }

    private fun variance(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        return values.map { (it - mean) * (it - mean) }.average()
    }

    private fun scoreConsistency(durations: List<Double>, amplitudes: List<Double>): Int {
        if (durations.isEmpty() || amplitudes.isEmpty()) return 0
        val durationMean = durations.average().coerceAtLeast(1.0)
        val amplitudeMean = amplitudes.average().coerceAtLeast(1e-6)
        val durationCv = sqrt(variance(durations) ?: 0.0) / durationMean
        val amplitudeCv = sqrt(variance(amplitudes) ?: 0.0) / amplitudeMean
        return (100.0 - (durationCv * 55.0 + amplitudeCv * 45.0)).toInt().coerceIn(0, 100)
    }

    private fun lateSetDegradation(durations: List<Double>): Double? {
        if (durations.size < 4) return null
        val split = durations.size / 2
        val first = durations.take(split).average()
        val last = durations.drop(split).average()
        return ((last - first) / first.coerceAtLeast(1.0)).coerceIn(-1.0, 1.0)
    }
}
