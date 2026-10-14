package dev.kyungmin.kcoach.wear

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualSetOutboxTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun command(id: String = "command") = ManualSetCommand(id, "session", "leg_press", 0,
        "set", "attempt", 0, 0, ManualSetOperation.COMPLETE)
    private fun ack(status: ManualSetStatus, revision: Long = 1) = ManualSetAck("command", "session", "set", "attempt", status, revision, 0)

    @Test fun offlineCountSurvivesRestartAndOnlyAnExactAcknowledgementStopsRetry() {
        assertTrue(ManualSetOutbox(app).enqueue(command()))
        val recreated = ManualSetOutbox(app)
        assertEquals(listOf(command()), recreated.pending())
        assertFalse(recreated.acknowledge(ack(ManualSetStatus.APPLIED).copy(attemptId = "old")))
        assertEquals(listOf(command()), recreated.pending())
        assertTrue(recreated.acknowledge(ack(ManualSetStatus.APPLIED)))
        assertTrue(ManualSetOutbox(app).pending().isEmpty())
        assertEquals(0, recreated.latest("session", "set", "attempt")!!.command.performedReps)
    }

    @Test fun conflictRetainsBothCountsAndRequiresAnExplicitReplacementAtTheNewRevision() {
        val store = ManualSetOutbox(app)
        assertTrue(store.enqueue(command()))
        assertFalse(store.enqueue(command("another").copy(performedReps = 5)))
        assertTrue(store.acknowledge(ack(ManualSetStatus.CONFLICT, 3).copy(currentReps = 7)))
        assertTrue(store.pending().isEmpty())
        assertEquals(7, store.latest("session", "set", "attempt")!!.ack!!.currentReps)
        assertFalse(store.enqueue(command("resolved").copy(expectedRevision = 0)))
        assertTrue(store.enqueue(command("resolved").copy(performedReps = 8, expectedRevision = 3, operation = ManualSetOperation.EDIT)))
        assertEquals(8, ManualSetOutbox(app).pending().single().performedReps)
        assertTrue(store.acknowledge(ack(ManualSetStatus.DUPLICATE, 1)))
        assertEquals(3L, store.latest("session", "set", "attempt")!!.command.expectedRevision)
        assertEquals("resolved", store.latest("session", "set", "attempt")!!.command.commandId)
    }
}
