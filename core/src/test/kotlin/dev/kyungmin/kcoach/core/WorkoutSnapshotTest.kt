package dev.kyungmin.kcoach.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WorkoutSnapshotTest {
    private val snapshot = WorkoutSnapshot(
        sessionId = "phone-1",
        routineId = "shoulders",
        exerciseIndex = 2,
        setIndex = 1,
        plannedSets = listOf(
            PlannedSetSnapshot(0, "external_rotation", 0, 25, 5.0, true, "WARMUP"),
            PlannedSetSnapshot(0, "external_rotation", 1, 25, null, false),
        ),
        performedReps = mapOf("external_rotation" to 25),
        phase = WorkoutPhase.RESTING,
        restEndAtMs = 50_000L,
        pausedRestRemainingMs = 0L,
        paused = false,
        startedAtMs = 1_000L,
        pausedSinceMs = 0L,
        totalPausedMs = 500L,
        lastSequence = 7L,
        lastCompletedSetKey = "phone-1:external_rotation:0",
        captureStates = mapOf("capture-1" to CaptureState.ANALYZING),
    )

    @Test
    fun `snapshot round trips every recovery field`() {
        assertEquals(snapshot, WorkoutSnapshotCodec.decode(WorkoutSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `snapshot persists the planned set type`() {
        val decoded = WorkoutSnapshotCodec.decode(WorkoutSnapshotCodec.encode(snapshot))
        assertEquals("WARMUP", decoded.plannedSets.first().setType)
    }

    @Test
    fun `version one snapshots migrate with normal set types`() {
        val decoded = WorkoutSnapshotCodec.decode(legacyVersionOneSnapshot())
        assertEquals(WorkoutSnapshot.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(listOf("NORMAL", "NORMAL"), decoded.plannedSets.map { it.setType })
    }

    @Test
    fun `truncated snapshot is rejected`() {
        val encoded = WorkoutSnapshotCodec.encode(snapshot)
        assertFails { WorkoutSnapshotCodec.decode(encoded.dropLast(8)) }
    }

    @Test
    fun `invalid runtime values are rejected before persistence`() {
        assertFails { snapshot.copy(performedReps = mapOf("external_rotation" to -1)) }
        assertFails { snapshot.copy(restEndAtMs = -1L) }
        assertFails {
            snapshot.copy(
                plannedSets = listOf(PlannedSetSnapshot(0, "external_rotation", 0, 10, Double.NaN, false)),
            )
        }
    }

    private fun legacyVersionOneSnapshot(): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            fun writeString(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
            data.writeInt(0x4B435331)
            data.writeInt(1)
            writeString(snapshot.sessionId)
            writeString(snapshot.routineId)
            data.writeInt(snapshot.exerciseIndex)
            data.writeInt(snapshot.setIndex)
            data.writeInt(snapshot.plannedSets.size)
            snapshot.plannedSets.forEach { set ->
                data.writeInt(set.exerciseIndex)
                writeString(set.exerciseId)
                data.writeInt(set.setIndex)
                data.writeInt(set.targetReps)
                data.writeBoolean(set.weightKg != null)
                set.weightKg?.let(data::writeDouble)
                data.writeBoolean(set.completed)
            }
            data.writeInt(snapshot.performedReps.size)
            snapshot.performedReps.forEach { (exercise, reps) ->
                writeString(exercise)
                data.writeInt(reps)
            }
            data.writeInt(snapshot.phase.ordinal)
            data.writeLong(snapshot.restEndAtMs)
            data.writeLong(snapshot.pausedRestRemainingMs)
            data.writeBoolean(snapshot.paused)
            data.writeLong(snapshot.startedAtMs)
            data.writeLong(snapshot.pausedSinceMs)
            data.writeLong(snapshot.totalPausedMs)
            data.writeLong(snapshot.lastSequence)
            data.writeBoolean(snapshot.lastCompletedSetKey != null)
            snapshot.lastCompletedSetKey?.let(::writeString)
            data.writeInt(snapshot.captureStates.size)
            snapshot.captureStates.forEach { (captureId, state) ->
                writeString(captureId)
                data.writeInt(state.ordinal)
            }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }
}
