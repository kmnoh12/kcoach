package dev.kyungmin.kcoach.core

/** Ephemeral record announcement; independent of the durable desired workout command. */
data class SetRecordCommand(
    val commandId: String, val sessionId: String, val setId: String, val attemptId: String,
    val kind: RecordKind, val weightKg: Double?, val previousWeightKg: Double?,
    val reps: Int?, val previousReps: Int?,
) {
    init {
        require(listOf(commandId, sessionId, setId).all { it.isNotBlank() })
        require(listOf(commandId, sessionId, setId, attemptId).none { '|' in it })
        require(listOfNotNull(weightKg, previousWeightKg).all { it.isFinite() && it >= 0 })
        require(listOfNotNull(reps, previousReps).all { it >= 0 })
    }
    fun encode(): String = listOf("1", commandId, sessionId, setId, attemptId, kind.name,
        weightKg ?: "", previousWeightKg ?: "", reps ?: "", previousReps ?: "").joinToString("|")

    companion object {
        fun fromHit(sessionId: String, setId: String, attemptId: String, hit: RecordHit): SetRecordCommand =
            SetRecordCommand("record:$sessionId:$setId", sessionId, setId, attemptId, hit.kind,
                if (hit.kind == RecordKind.SAME_WEIGHT_REPS) hit.weightKg else hit.value,
                if (hit.kind == RecordKind.SAME_WEIGHT_REPS) hit.weightKg else hit.previous,
                if (hit.kind == RecordKind.SAME_WEIGHT_REPS) hit.value.toInt() else null,
                if (hit.kind == RecordKind.SAME_WEIGHT_REPS) hit.previous.toInt() else null)

        fun decode(payload: String): SetRecordCommand {
            val f = WearSync.parse(payload)
            require(f.size == 10 && f[0] == "1")
            return SetRecordCommand(f[1], f[2], f[3], f[4], RecordKind.valueOf(f[5]),
                f[6].takeIf { it.isNotEmpty() }?.toDouble(), f[7].takeIf { it.isNotEmpty() }?.toDouble(),
                f[8].takeIf { it.isNotEmpty() }?.toInt(), f[9].takeIf { it.isNotEmpty() }?.toInt())
        }
    }
}
