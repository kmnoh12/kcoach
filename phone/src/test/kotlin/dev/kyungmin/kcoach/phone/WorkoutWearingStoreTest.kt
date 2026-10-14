package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutWearingStoreTest {
    @Test fun normalWorkoutCommandsAreBoundBeforeTheDurableOutboxAndBackgroundResyncUsesTheSameBinding() {
        val app = RuntimeEnvironment.getApplication()
        val wearing = WorkoutWearingStore(app)
        wearing.beginSession("session", WorkoutWearingSelection("LEFT", "OUTSIDE")).getOrThrow()
        val key = PatternCompatibility(PatternCandidateStore(app).localUserId(), MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch-A", "watch-native-si-v1")
        val runtime = PatternRuntimeProfile("candidate", 1, "a".repeat(64), key,
            List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0), 3_000_000_000, 1_800_000_000, 4_800_000_000, .2)
        val transfers = PatternTransferOutbox(app)
        val command = transfers.enqueueTransition(runtime, PatternProfileOperation.ACTIVATE, PatternValidationEvidence("b".repeat(64), "c".repeat(64))).getOrThrow().command
        transfers.acknowledge(PatternProfileAck(command.commandId, command.operation, runtime.id, runtime.version,
            runtime.candidateHash, command.runtimeHash, 1, "watch-A", PatternProfileStatus.STAGED)).getOrThrow()
        val start = WearSync.richCommand("START", "session", "dumbbell_curl", "컬", 0, 8, 5.0, 60,
            setId = "set", attemptId = "attempt")
        PhoneWorkoutService.send(app, WearSync.PATH_COMMAND, start)
        val saved = WearSync.parse(PhoneCommandOutbox(app).pending()!!)
        assertEquals(PatternWearingContext(key.localUserId, "LEFT", "OUTSIDE"), PatternWearingContext.decode(saved[17]))
        assertEquals("VERIFIED_PATTERN", saved[18])
        assertEquals("8", saved[6])
        val syncFields = WearSync.parse(start).toMutableList().apply { this[1] = "SYNC"; this[11] = "WORKING" }
        val bridge = PhoneWearBridge(app)
        try {
            bridge.sendRaw(WearSync.PATH_COMMAND, syncFields.joinToString("|"))
            val sync = WearSync.parse(PhoneCommandOutbox(app).pending()!!)
            assertEquals("SYNC", sync[1])
            assertEquals(saved[17], sync[17])
            assertEquals("VERIFIED_PATTERN", sync[18])
            assertEquals("set", sync[13]); assertEquals("attempt", sync[14])
        } finally { bridge.close() }
    }

    @Test fun aLateStoredProfileWaitsForTheNextSetAndWearingSurvivesRestart() {
        val app = RuntimeEnvironment.getApplication()
        val wearing = WorkoutWearingStore(app)
        val selection = WorkoutWearingSelection("LEFT", "OUTSIDE")
        wearing.beginSession("session", selection).getOrThrow()
        fun start(set: String, attempt: String) = WearSync.richCommand("START", "session", "dumbbell_curl", "컬",
            0, 8, 5.0, 60, setId = set, attemptId = attempt)
        val first = start("set-1", "attempt-1")
        assertTrue(WearSync.parse(wearing.bind(first).getOrThrow()).getOrNull(16).isNullOrEmpty())
        val key = PatternCompatibility(PatternCandidateStore(app).localUserId(), MeasurementRecipe.CURL, "LEFT", "OUTSIDE",
            "watch-A", "watch-native-si-v1")
        val runtime = PatternRuntimeProfile("candidate", 1, "a".repeat(64), key,
            List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0), 3_000_000_000, 1_800_000_000, 4_800_000_000, .2)
        val outbox = PatternTransferOutbox(app)
        val command = outbox.enqueueTransition(runtime, PatternProfileOperation.ACTIVATE,
            PatternValidationEvidence("b".repeat(64), "c".repeat(64))).getOrThrow().command
        outbox.acknowledge(PatternProfileAck(command.commandId, command.operation, runtime.id, runtime.version,
            runtime.candidateHash, command.runtimeHash, 1, "watch-A", PatternProfileStatus.STAGED)).getOrThrow()
        val restored = WorkoutWearingStore(app)
        assertEquals(selection, restored.selection("session").getOrThrow())
        assertTrue(WearSync.parse(restored.bind(first).getOrThrow()).getOrNull(16).isNullOrEmpty())
        val next = WearSync.parse(restored.bind(start("set-2", "attempt-2")).getOrThrow())
        assertEquals(PatternWearingContext(key.localUserId, "LEFT", "OUTSIDE"), PatternWearingContext.decode(next[17]))
        assertEquals("set-2", next[13]); assertEquals("attempt-2", next[14])
        assertFalse(restored.beginSession("session", WorkoutWearingSelection("RIGHT", "OUTSIDE")).isSuccess)
    }
}
