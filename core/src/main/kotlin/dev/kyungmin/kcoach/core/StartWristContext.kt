package dev.kyungmin.kcoach.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** User-declared setup, separate from a sensor-confirmed direction or a learned repetition profile. */
data class StartWristContext(val exerciseId: String, val owner: String, val side: String,
    val placement: String, val deviceTop: String, val variant: String) {
    init {
        require(exerciseId == ExerciseMotionCatalog.normalizeExerciseId(exerciseId))
        require(owner.matches(Regex("[A-Za-z0-9._-]{1,160}")))
        require(side in setOf("LEFT", "RIGHT") && placement in setOf("OUTSIDE", "INSIDE"))
        require(deviceTop in setOf("ELBOW", "HAND", "UNKNOWN"))
        require(variant in variantsFor(exerciseId)) { "Start variant belongs to a different exercise" }
    }
    val completeForDirectionValidation: Boolean get() = deviceTop != "UNKNOWN" && variant !in setOf("UNKNOWN", "OTHER")

    companion object {
        fun expectedVariant(exerciseId: String): String? = when (StartWristOrientationPolicy.candidate(exerciseId)) {
            StartWristCandidate.HORIZONTAL_PALM_UP -> "PALM_UP_HORIZONTAL"
            StartWristCandidate.HORIZONTAL_KNUCKLES_UP -> "KNUCKLES_UP_HORIZONTAL"
            StartWristCandidate.ARM_DOWN -> "NEUTRAL_ARM_DOWN"
            else -> null
        }
        fun variantsFor(exerciseId: String): Set<String> {
            val expected = requireNotNull(expectedVariant(exerciseId)) { "Start direction is deferred or manual for this exercise" }
            return setOf(expected, "UNKNOWN", "OTHER")
        }
    }
}

object StartWristContextCodec {
    private const val MAGIC = 0x53574331 // SWC1
    fun encode(value: StartWristContext): String = Base64.getEncoder().encodeToString(ByteArrayOutputStream().apply {
        DataOutputStream(this).use { out ->
            out.writeInt(MAGIC)
            listOf(value.exerciseId, value.owner, value.side, value.placement, value.deviceTop, value.variant).forEach(out::writeUTF)
        }
    }.toByteArray()).also { require(it.length <= 1024) }
    fun decode(encoded: String): StartWristContext {
        require(encoded.length in 1..1024)
        return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
            require(input.readInt() == MAGIC)
            val value = StartWristContext(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF())
            require(input.available() == 0)
            value
        }
    }
}
