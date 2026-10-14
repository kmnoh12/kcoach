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
class PatternTransferOutboxTest {
    @Test fun disablingLeavesAnObservedBasicEngineTombstoneAcrossHistoryTrimming() {
        val app = RuntimeEnvironment.getApplication()
        val outbox = PatternTransferOutbox(app)
        val model = runtime(1)
        val evidence = PatternValidationEvidence("c".repeat(64), "d".repeat(64))
        fun observed(command: PatternProfileCommand, revision: Long, status: PatternProfileStatus) =
            outbox.acknowledge(PatternProfileAck(command.commandId, command.operation, command.runtime.id,
                command.runtime.version, command.runtime.candidateHash, command.runtimeHash, revision, "watch-A", status,
                application = PatternProfileApplication("s", "set", "attempt", "clock", 10, 11))).getOrThrow()
        val activation = outbox.enqueueTransition(model, PatternProfileOperation.ACTIVATE, evidence).getOrThrow().command
        observed(activation, 1, PatternProfileStatus.APPLIED)
        val disable = outbox.enqueueTransition(model, PatternProfileOperation.DISABLE,
            evidence.copy(previousActiveHash = model.candidateHash)).getOrThrow().command
        assertEquals(model, outbox.activeHistory(model.compatibility).getOrThrow().single().command.runtime)
        observed(disable, 2, PatternProfileStatus.DISABLED)
        assertTrue(outbox.activeHistory(model.compatibility).getOrThrow().isEmpty())
        assertTrue(outbox.pending().getOrThrow().isEmpty())
        repeat(260) { index ->
            observed(outbox.enqueueShadow(runtime(10L + index)).getOrThrow().command, index + 3L, PatternProfileStatus.APPLIED)
        }
        val restarted = PatternTransferOutbox(app)
        assertEquals(PatternProfileStatus.DISABLED, restarted.latestTransition(model.compatibility).getOrThrow()!!.ack!!.status)
        assertTrue(restarted.activeHistory(model.compatibility).getOrThrow().isEmpty())
        assertTrue(restarted.enqueueTransition(model, PatternProfileOperation.ACTIVATE,
            evidence.copy(previousActiveHash = model.candidateHash)).isFailure)
        assertTrue(restarted.enqueueTransition(model, PatternProfileOperation.ACTIVATE, evidence).isSuccess)
    }
    private fun runtime(version: Long) = PatternRuntimeProfile("candidate-$version", version,
        (if (version == 1L) "a" else "b").repeat(64),
        PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch-A", "watch-native-si-v1"),
        List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0), 3_000_000_000, 1_800_000_000, 4_800_000_000, .2)

    @Test fun activeTransitionConflictsNeedNewEvidenceAndDoNotReplaceComparisonTransfers() {
        val app = RuntimeEnvironment.getApplication()
        val outbox = PatternTransferOutbox(app)
        val shadow = outbox.enqueueShadow(runtime(1)).getOrThrow().command
        val active = outbox.enqueueTransition(runtime(1), PatternProfileOperation.ACTIVATE,
            PatternValidationEvidence("c".repeat(64), "d".repeat(64))).getOrThrow().command
        assertEquals(setOf(shadow.commandId, active.commandId), outbox.pending().getOrThrow().map { it.commandId }.toSet())
        val conflict = PatternProfileAck(active.commandId, active.operation, active.runtime.id, active.runtime.version,
            active.runtime.candidateHash, active.runtimeHash, 3, "watch-A", PatternProfileStatus.CONFLICT)
        assertTrue(outbox.acknowledge(conflict).getOrThrow())
        assertEquals(listOf(shadow.commandId), PatternTransferOutbox(app).pending().getOrThrow().map { it.commandId })
        assertEquals(PatternProfileStatus.CONFLICT, outbox.latestTransition(runtime(1).compatibility).getOrThrow()!!.ack!!.status)
    }

    @Test fun rollbackHistoryRequiresObservedUseAndSurvivesComparisonHistoryTrimming() {
        val app = RuntimeEnvironment.getApplication()
        val outbox = PatternTransferOutbox(app)
        val evidence = PatternValidationEvidence("c".repeat(64), "d".repeat(64))
        fun applied(command: PatternProfileCommand, revision: Long) {
            outbox.acknowledge(PatternProfileAck(command.commandId, command.operation, command.runtime.id, command.runtime.version,
                command.runtime.candidateHash, command.runtimeHash, revision, "watch-A", PatternProfileStatus.APPLIED,
                application = PatternProfileApplication("session", "set-$revision", "attempt-$revision", "clock", 10_000_000_000, 10_001_000_000))).getOrThrow()
        }
        val first = outbox.enqueueTransition(runtime(1), PatternProfileOperation.ACTIVATE, evidence).getOrThrow().command
        assertTrue(outbox.activeHistory(runtime(1).compatibility).getOrThrow().isEmpty())
        applied(first, 1)
        assertTrue(outbox.enqueueTransition(runtime(2), PatternProfileOperation.ACTIVATE, evidence).isFailure)
        val second = outbox.enqueueTransition(runtime(2), PatternProfileOperation.ACTIVATE,
            evidence.copy(previousActiveHash = runtime(1).candidateHash)).getOrThrow().command
        assertEquals(listOf(runtime(1)), outbox.activeHistory(runtime(1).compatibility).getOrThrow().map { it.command.runtime })
        applied(second, 2)
        repeat(260) { index ->
            val shadow = outbox.enqueueShadow(runtime(10L + index)).getOrThrow().command
            applied(shadow, 3L + index)
        }
        val restarted = PatternTransferOutbox(app)
        assertEquals(listOf(runtime(2), runtime(1)), restarted.activeHistory(runtime(1).compatibility).getOrThrow().map { it.command.runtime })
        val rollback = restarted.enqueueTransition(runtime(1), PatternProfileOperation.ROLLBACK,
            evidence.copy(previousActiveHash = runtime(2).candidateHash)).getOrThrow().command
        assertEquals(262L, rollback.expectedRevision)
        assertEquals(runtime(2), restarted.activeHistory(runtime(1).compatibility).getOrThrow().first().command.runtime)
        applied(rollback, 263)
        assertEquals(listOf(runtime(1), runtime(2)), restarted.activeHistory(runtime(1).compatibility).getOrThrow().map { it.command.runtime })
    }

    @Test fun pendingTransfersSurviveRestartAndOnlyMatchingObservedUseAcknowledgesTheRequestedProfile() {
        val app = RuntimeEnvironment.getApplication()
        val outbox = PatternTransferOutbox(app)
        val first = outbox.enqueueShadow(runtime(1)).getOrThrow()
        val command = first.command
        assertEquals(command, PatternTransferOutbox(app).pending().getOrThrow().single())
        val stored = PatternProfileAck(command.commandId, command.operation, command.runtime.id, command.runtime.version,
            command.runtime.candidateHash, command.runtimeHash, 1, "watch-A", PatternProfileStatus.STAGED)
        assertFalse(outbox.acknowledge(stored.copy(runtimeHash = "c".repeat(64))).getOrThrow())
        assertTrue(outbox.acknowledge(stored).getOrThrow())
        assertEquals(1, outbox.pending().getOrThrow().size) // staged is not observed use
        val applied = stored.copy(status = PatternProfileStatus.APPLIED,
            application = PatternProfileApplication("session", "set", "attempt", "clock", 10_000_000_000, 10_001_000_000))
        assertTrue(outbox.acknowledge(applied).getOrThrow())
        assertTrue(outbox.pending().getOrThrow().isEmpty())
        val second = outbox.enqueueShadow(runtime(2)).getOrThrow()
        assertEquals(1L, second.command.expectedRevision)
        assertTrue(outbox.acknowledge(applied.copy(duplicate = true)).getOrThrow())
        assertEquals(second.command, outbox.pending().getOrThrow().single())
        val conflict = PatternProfileAck(second.command.commandId, second.command.operation, second.command.runtime.id,
            second.command.runtime.version, second.command.runtime.candidateHash, second.command.runtimeHash,
            3, "watch-A", PatternProfileStatus.CONFLICT)
        outbox.acknowledge(conflict).getOrThrow()
        val retry = PatternTransferOutbox(app).pending().getOrThrow().single()
        assertNotEquals(second.command.commandId, retry.commandId)
        assertEquals(second.command.runtime, retry.runtime)
        assertEquals(3L, retry.expectedRevision)
    }
}
