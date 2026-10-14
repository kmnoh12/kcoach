package dev.kyungmin.kcoach.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Tiny execution snapshot; the full human-label archive remains on the phone. */
data class PatternRuntimeProfile(val id: String, val version: Long, val candidateHash: String,
    val compatibility: PatternCompatibility, val template: List<List<Double>>, val scales: List<Double>,
    val activeAxes: List<Int>, val medianDurationNs: Long, val minimumDurationNs: Long,
    val maximumDurationNs: Long, val maximumDistance: Double, val engineId: String = "reviewed-six-axis-dtw-v1") {
    init {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,100}")) && version > 0 && candidateHash.matches(Regex("[0-9a-f]{64}")))
        require(engineId == "reviewed-six-axis-dtw-v1")
        require(template.size == 33 && template.all { it.size == 6 && it.all(Double::isFinite) })
        require(scales.size == 6 && scales.all { it.isFinite() && it > 0 })
        require(activeAxes.isNotEmpty() && activeAxes.distinct() == activeAxes && activeAxes.all { it in 0..5 })
        require(minimumDurationNs >= 350_000_000 && maximumDurationNs <= 12_000_000_000 &&
            minimumDurationNs < maximumDurationNs && medianDurationNs in minimumDurationNs..maximumDurationNs)
        require(maximumDistance.isFinite() && maximumDistance in 0.05..0.8)
    }
    companion object {
        fun from(profile: ReviewedPatternProfile) = PatternRuntimeProfile(profile.id, profile.version,
            SensorCaptureFormat.sha256(ReviewedPatternCodec.encode(profile)), profile.compatibility, profile.template,
            profile.scales, profile.activeAxes, profile.medianDurationNs, profile.minimumDurationNs, profile.maximumDurationNs,
            profile.maximumDistance, profile.engineId)
    }
}

object PatternRuntimeCodec {
    private const val MAGIC = 0x50525431 // PRT1
    fun encode(profile: PatternRuntimeProfile): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).use { out ->
            out.writeInt(MAGIC); out.writeUTF(profile.id); out.writeLong(profile.version); out.writeUTF(profile.candidateHash)
            out.writeUTF(profile.engineId)
            val key = profile.compatibility
            out.writeUTF(key.localUserId); out.writeUTF(key.recipe.name); out.writeUTF(key.side); out.writeUTF(key.placement)
            out.writeUTF(key.deviceId); out.writeUTF(key.coordinates)
            profile.template.forEach { it.forEach(out::writeDouble) }; profile.scales.forEach(out::writeDouble)
            out.writeInt(profile.activeAxes.size); profile.activeAxes.forEach(out::writeInt)
            out.writeLong(profile.medianDurationNs); out.writeLong(profile.minimumDurationNs); out.writeLong(profile.maximumDurationNs)
            out.writeDouble(profile.maximumDistance)
        }
    }.toByteArray().also { require(it.size <= 4096) }

    fun decode(bytes: ByteArray): PatternRuntimeProfile {
        require(bytes.size in 1..4096)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC)
            val id = input.readUTF(); val version = input.readLong(); val hash = input.readUTF(); val engine = input.readUTF()
            val key = PatternCompatibility(input.readUTF(), MeasurementRecipe.valueOf(input.readUTF()), input.readUTF(),
                input.readUTF(), input.readUTF(), input.readUTF())
            val template = List(33) { List(6) { input.readDouble() } }; val scales = List(6) { input.readDouble() }
            val axisCount = input.readInt().also { require(it in 1..6) }
            val axes = List(axisCount) { input.readInt() }
            val median = input.readLong(); val minimum = input.readLong(); val maximum = input.readLong(); val threshold = input.readDouble()
            require(input.available() == 0)
            PatternRuntimeProfile(id, version, hash, key, template, scales, axes, median, minimum, maximum, threshold, engine)
        }
    }
}
