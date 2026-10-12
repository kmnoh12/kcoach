package dev.kyungmin.kcoach.core

import java.util.UUID

/** Positions are display data. A set and its measurement attempt own the result. */
object SetIdentity {
    fun migratedId(sessionId: String, exerciseIndex: Int, setIndex: Int, attempt: Boolean = false): String =
        UUID.nameUUIDFromBytes("$sessionId:$exerciseIndex:$setIndex:$attempt".toByteArray(Charsets.UTF_8)).toString()

    fun key(exerciseId: String, setIndex: Int, setId: String, attemptId: String): String =
        if (setId.isBlank()) "$exerciseId:$setIndex" else "$setId:$attemptId"

    fun completionKey(sessionId: String, set: PlannedSetSnapshot): String =
        "$sessionId:${key(set.exerciseId, set.setIndex, set.setId, set.attemptId)}"

    fun resolve(snapshot: WorkoutSnapshot, exerciseId: String, setIndex: Int, setId: String = "", attemptId: String = ""): PlannedSetSnapshot? {
        if (setId.isNotBlank() || attemptId.isNotBlank()) {
            if (setId.isBlank() || attemptId.isBlank()) return null
            return snapshot.plannedSets.singleOrNull { it.setId == setId && it.attemptId == attemptId && it.exerciseId == exerciseId }
        }
        return snapshot.plannedSets.singleOrNull { it.exerciseId == exerciseId && it.setIndex == setIndex }
            ?.takeUnless { it.identityRequired }
    }
}
