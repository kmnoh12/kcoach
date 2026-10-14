package dev.kyungmin.kcoach.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Canonical, bounded local candidate snapshot; hashes cover the training sources as well as the parameters. */
object ReviewedPatternCodec {
    private const val MAGIC = 0x52505031 // RPP1
    fun encode(profile: ReviewedPatternProfile): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).use { out ->
            out.writeInt(MAGIC); out.writeUTF(profile.id); out.writeLong(profile.version); out.writeUTF(profile.engineId)
            val key = profile.compatibility
            out.writeUTF(key.localUserId); out.writeUTF(key.recipe.name); out.writeUTF(key.side)
            out.writeUTF(key.placement); out.writeUTF(key.deviceId); out.writeUTF(key.coordinates)
            out.writeInt(profile.sources.size)
            profile.sources.forEach { source ->
                out.writeUTF(source.captureId); out.writeUTF(source.rawSha256); out.writeLong(source.reviewRevision)
                out.writeUTF(source.dayId); out.writeUTF(source.exerciseId); out.writeUTF(source.referenceDigest)
            }
            out.writeInt(profile.exerciseConditions.size); profile.exerciseConditions.sorted().forEach(out::writeUTF)
            out.writeInt(profile.trainingRepCount)
            profile.template.forEach { frame -> frame.forEach(out::writeDouble) }
            profile.scales.forEach(out::writeDouble)
            out.writeInt(profile.activeAxes.size); profile.activeAxes.forEach(out::writeInt)
            out.writeLong(profile.medianDurationNs); out.writeLong(profile.minimumDurationNs); out.writeLong(profile.maximumDurationNs)
            out.writeDouble(profile.maximumDistance); out.writeDouble(profile.trainingDispersion)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): ReviewedPatternProfile {
        require(bytes.size in 1..2_000_000)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Unknown pattern schema" }
            val id = input.readUTF(); val version = input.readLong(); val engine = input.readUTF()
            require(engine == "reviewed-six-axis-dtw-v1")
            val key = PatternCompatibility(input.readUTF(), MeasurementRecipe.valueOf(input.readUTF()), input.readUTF(),
                input.readUTF(), input.readUTF(), input.readUTF())
            fun count(max: Int): Int = input.readInt().also { require(it in 1..max) }
            val sources = List(count(500)) { PatternSource(input.readUTF(), input.readUTF(), input.readLong(),
                input.readUTF(), input.readUTF(), input.readUTF()) }
            val conditions = List(count(500)) { input.readUTF() }.toSet()
            val reps = input.readInt()
            val template = List(33) { List(6) { input.readDouble() } }
            val scales = List(6) { input.readDouble() }
            val axes = List(count(6)) { input.readInt() }
            val median = input.readLong(); val minimum = input.readLong(); val maximum = input.readLong()
            val threshold = input.readDouble(); val dispersion = input.readDouble()
            require(input.available() == 0) { "Trailing pattern data" }
            ReviewedPatternProfile(id, version, key, sources, conditions, reps, template, scales, axes,
                median, minimum, maximum, threshold, dispersion, engine)
        }
    }
}
