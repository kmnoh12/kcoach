package dev.kyungmin.kcoach.core

/** The watch supplies its own device identity; the phone never guesses a connected device from old profiles. */
data class PatternWearingContext(val owner: String, val side: String, val placement: String) {
    init {
        require(owner.matches(Regex("[A-Za-z0-9._-]{1,160}")))
        require(side in setOf("LEFT", "RIGHT") && placement in setOf("OUTSIDE", "INSIDE"))
    }
    fun encode() = "$owner:$side:$placement"
    fun compatibility(recipe: MeasurementRecipe, deviceId: String) = PatternCompatibility(owner, recipe, side, placement, deviceId, "watch-native-si-v1")
    companion object {
        fun decode(encoded: String): PatternWearingContext {
            require(encoded.length in 1..200)
            val fields = encoded.split(':'); require(fields.size == 3)
            return PatternWearingContext(fields[0], fields[1], fields[2])
        }
    }
}
