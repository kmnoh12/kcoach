package dev.kyungmin.kcoach.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PatternProfileProtocolTest {
    @Test fun disabledReceiptRequiresActualBasicProcessingAndCannotClaimPersonalModelUse() {
        val model = runtime()
        val command = PatternProfileCommand("disable", PatternProfileOperation.DISABLE, model, 2,
            PatternValidationEvidence("c".repeat(64), "d".repeat(64), model.candidateHash))
        assertEquals(command, PatternProfileCommand.decode(command.encode()))
        val staged = PatternProfileAck(command.commandId, command.operation, model.id, model.version,
            model.candidateHash, command.runtimeHash, 3, "watch-A", PatternProfileStatus.STAGED)
        assertThrows(IllegalArgumentException::class.java) { staged.copy(status = PatternProfileStatus.DISABLED) }
        val disabled = staged.copy(status = PatternProfileStatus.DISABLED,
            application = PatternProfileApplication("s", "set", "a", "clock", 10, 11))
        assertEquals(disabled, PatternProfileAck.decode(disabled.encode()))
        assertThrows(IllegalArgumentException::class.java) { disabled.copy(status = PatternProfileStatus.APPLIED) }
        assertThrows(IllegalArgumentException::class.java) { disabled.copy(operation = PatternProfileOperation.ACTIVATE) }
    }
    private fun runtime() = PatternRuntimeProfile("candidate-A", 2, "a".repeat(64),
        PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch-A", "watch-native-si-v1"),
        List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0), 3_000_000_000,
        1_800_000_000, 4_800_000_000, .2)

    @Test fun aTransferBindsCommandRevisionFullCandidateHashAndSmallSnapshotHash() {
        val command = PatternProfileCommand("command-A", PatternProfileOperation.SHADOW, runtime(), 3)
        val encoded = command.encode()
        assertEquals(command, PatternProfileCommand.decode(encoded))
        val envelope = WireCodec.decode(encoded)
        val forged = WireCodec.encode(envelope.copy(payload = envelope.payload + ("runtimeHash" to "b".repeat(64))))
        assertThrows(IllegalArgumentException::class.java) { PatternProfileCommand.decode(forged) }
        assertThrows(IllegalArgumentException::class.java) {
            PatternProfileCommand("unverified", PatternProfileOperation.ACTIVATE, runtime(), 3)
        }
    }

    @Test fun shadowSelectionKeepsExistingSetAttemptAndCommandRevisionFieldsIntact() {
        val start = WearSync.richCommand("START", "paired-session", "dumbbell_curl", "컬", 0, 8, null, 0,
            setId = "set-A", attemptId = "attempt-A")
        val fields = WearSync.parse(WearSync.withPatternShadow(start, "command-A"))
        assertEquals("set-A", fields[13])
        assertEquals("attempt-A", fields[14])
        assertEquals("command-A", fields[15])
        assertEquals("0", fields[10])
    }

    @Test fun aSetBindingCarriesOwnershipAndWearingWithoutChangingTheComparisonOrSetIdentity() {
        val key = runtime().compatibility
        val start = WearSync.withPatternShadow(WearSync.richCommand("START", "session", "dumbbell_curl", "컬", 0, 8,
            null, 60, setId = "set", attemptId = "attempt"), "comparison")
        val fields = WearSync.parse(WearSync.withPatternBinding(start, key))
        assertEquals("set", fields[13]); assertEquals("attempt", fields[14]); assertEquals("comparison", fields[15])
        assertEquals(key, PatternCompatibilityCodec.decode(fields[16]))
        assertThrows(IllegalArgumentException::class.java) { PatternCompatibilityCodec.decode(fields[16].replace("LEFT", "UNKNOWN")) }
        assertThrows(IllegalArgumentException::class.java) { WearSync.withPatternBinding("1|START|session|curl|0|8", key) }
    }
}
