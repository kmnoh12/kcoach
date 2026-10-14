package dev.kyungmin.kcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ManualSetProtocolTest {
    @Test
    fun aManualZeroAndAttemptIdentitySurviveTheActualWireCodec() {
        val command = ManualSetCommand("command-1", "session", "leg_press", 0,
            "set-1", "attempt-1", 0, 3, ManualSetOperation.COMPLETE)
        val envelope = WireCodec.decode(WireCodec.encode(command.toEnvelope(10, 20)))
        assertEquals(command, ManualSetCommand.fromEnvelope(envelope))
        assertEquals(0, envelope.payload["performedReps"]?.toInt())
    }

    @Test
    fun missingActualCountIsNotReplacedWithTargetRepetitions() {
        val command = ManualSetCommand("command", "session", "leg_press", 0,
            "set", "attempt", 12, 0, ManualSetOperation.COMPLETE)
        val envelope = command.toEnvelope(10, 20)
        val forged = envelope.copy(payload = envelope.payload - "performedReps" + ("targetReps" to "12"))
        assertFailsWith<IllegalArgumentException> { ManualSetCommand.fromEnvelope(forged) }
    }
}
