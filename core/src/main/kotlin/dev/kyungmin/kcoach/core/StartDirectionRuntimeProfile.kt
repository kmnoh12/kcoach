package dev.kyungmin.kcoach.core

import java.io.*
import java.util.Base64

/** A frozen, human-reviewed direction target. A transfer receipt does not certify the current hold. */
data class StartDirectionRuntimeProfile(val id: String, val version: Long, val key: StartWristDirectionKey,
    val candidateFingerprint: String, val validationDigest: String, val gateConfig: StartPoseGateConfig) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,100}")) && version > 0)
        require(listOf(candidateFingerprint, validationDigest).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(gateConfig.expectedGravity != null && !gateConfig.requireSetupMotion && gateConfig.stableDurationMs == 2_000L)
        require(gateConfig.minCandidateSamples in 3..200 && gateConfig.maxSampleGapMs in 1..60 && gateConfig.maxAccelerationAgeMs in 1..40)
        require(gateConfig.maxGyroMagnitude.isFinite() && gateConfig.maxGyroMagnitude in 0.01..5.0)
        require(gateConfig.maxGravityMagnitudeError.isFinite() && gateConfig.maxGravityMagnitudeError in 0.01..3.5)
        require(gateConfig.maxOrientationDrift.isFinite() && gateConfig.maxOrientationDrift in 0.001..0.5)
        require(gateConfig.minSetupMotionGyro.isFinite() && gateConfig.minSetupMotionGyro in 0.01..5.0 && gateConfig.minSetupMotionSamples in 1..100)
    }
    companion object {
        fun from(candidate: ReviewedStartDirectionCandidate, report: StartDirectionValidationReport): StartDirectionRuntimeProfile {
            require(report.passed && report.candidateFingerprint == candidate.fingerprint)
            require(StartDirectionLabel.entries.all { label -> report.rows.any { it.source.label == label } })
            require(report.rows.all { it.ready == (it.source.label == StartDirectionLabel.CORRECT_DIRECTION) })
            return StartDirectionRuntimeProfile(candidate.id, candidate.version, candidate.key, candidate.fingerprint,
                SensorCaptureFormat.sha256(report.toString().toByteArray()), candidate.gateConfig)
        }
    }
}

object StartDirectionRuntimeCodec {
    fun encode(profile: StartDirectionRuntimeProfile): String = Base64.getEncoder().encodeToString(ByteArrayOutputStream().apply {
        DataOutputStream(this).use { out ->
            out.writeInt(0x53445231) // SDR1
            out.writeUTF(profile.id); out.writeLong(profile.version)
            out.writeUTF(StartWristContextCodec.encode(profile.key.context)); out.writeUTF(profile.key.deviceId); out.writeUTF(profile.key.coordinates)
            out.writeUTF(profile.candidateFingerprint); out.writeUTF(profile.validationDigest)
            val c = profile.gateConfig; val t = requireNotNull(c.expectedGravity)
            out.writeLong(c.stableDurationMs); out.writeInt(c.minCandidateSamples)
            out.writeDouble(c.maxGyroMagnitude); out.writeDouble(c.maxGravityMagnitudeError); out.writeDouble(c.maxOrientationDrift)
            out.writeLong(c.maxSampleGapMs); out.writeLong(c.maxAccelerationAgeMs)
            out.writeDouble(c.minSetupMotionGyro); out.writeInt(c.minSetupMotionSamples)
            out.writeDouble(t.x); out.writeDouble(t.y); out.writeDouble(t.z); out.writeDouble(t.maxAngularErrorRad)
        }
    }.toByteArray()).also { require(it.length <= 4096) }

    fun decode(encoded: String): StartDirectionRuntimeProfile {
        require(encoded.length in 1..4096)
        return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
            require(input.readInt() == 0x53445231)
            val id = input.readUTF(); val version = input.readLong()
            val key = StartWristDirectionKey(StartWristContextCodec.decode(input.readUTF()), input.readUTF(), input.readUTF())
            val fingerprint = input.readUTF(); val validation = input.readUTF()
            val duration = input.readLong(); val samples = input.readInt()
            val gyro = input.readDouble(); val gravity = input.readDouble(); val drift = input.readDouble()
            val gap = input.readLong(); val age = input.readLong()
            val setupGyro = input.readDouble(); val setupSamples = input.readInt()
            val target = StartWristGravityTarget(input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble())
            val result = StartDirectionRuntimeProfile(id, version, key, fingerprint, validation,
                StartPoseGateConfig(duration, samples, gyro, gravity, drift, gap, requireSetupMotion = false,
                    minSetupMotionGyro = setupGyro, minSetupMotionSamples = setupSamples,
                    expectedGravity = target, maxAccelerationAgeMs = age))
            require(input.available() == 0); result
        }
    }
    fun hash(encoded: String): String {
        val profile = decode(encoded)
        require(encode(profile) == encoded) { "Use the canonical direction snapshot encoding" }
        return SensorCaptureFormat.sha256(encoded.toByteArray())
    }
}

/** Prep remains in the raw store. A missing start boundary must not turn that prep into a zero-rep result. */
object StartDirectionMeasurementWindow {
    fun select(document: SensorCaptureDocument, startNs: Long?): SensorCaptureDocument? {
        if (startNs == null || startNs <= 0 || document.records.isEmpty() ||
            document.records.none { it.sample.timestampNs == startNs }) return null
        val records = document.records.filter { it.sample.timestampNs >= startNs }
        if (records.isEmpty()) return null
        return document.copy(records = records)
    }
}
