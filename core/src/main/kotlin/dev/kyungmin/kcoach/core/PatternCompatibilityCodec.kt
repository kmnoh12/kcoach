package dev.kyungmin.kcoach.core

/** Explicit ownership and wearing selection, independent of the chosen active version. */
object PatternCompatibilityCodec {
    fun encode(key: PatternCompatibility): String = listOf(key.localUserId, key.recipe.name, key.side,
        key.placement, key.deviceId, key.coordinates).joinToString(":")

    fun decode(encoded: String): PatternCompatibility {
        require(encoded.length in 1..512)
        val fields = encoded.split(':')
        require(fields.size == 6)
        return PatternCompatibility(fields[0], MeasurementRecipe.valueOf(fields[1]), fields[2], fields[3], fields[4], fields[5])
    }
}
