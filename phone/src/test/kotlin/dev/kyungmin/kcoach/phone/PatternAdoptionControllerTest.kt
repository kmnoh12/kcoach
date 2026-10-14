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
class PatternAdoptionControllerTest {
    @Test fun aFrozenCandidateWithoutOriginalsCannotChangeWatchSettings() {
        val app = RuntimeEnvironment.getApplication()
        val store = PatternCandidateStore(app)
        val key = PatternCompatibility(store.localUserId(), MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch-A", "watch-native-si-v1")
        val source = PatternSource("capture-A", "a".repeat(64), 1, "2026-10-14", "dumbbell_curl", "b".repeat(64))
        val profile = ReviewedPatternProfile("candidate-1", 1, key, listOf(source), setOf("dumbbell_curl"), 3,
            List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0), 3_000_000_000, 1_800_000_000, 4_800_000_000, .2, .1)
        store.saveCandidate(profile).getOrThrow()
        val controller = PatternAdoptionController(app)
        assertTrue(controller.activate(profile.id).isFailure)
        assertTrue(PatternTransferOutbox(app).pending().getOrThrow().isEmpty())
        assertNull(controller.state(profile.id).getOrThrow().observedActive)
    }

    @Test fun aStagedRequestIsNotShownAsActiveUntilTheWatchObservesUse() {
        val app = RuntimeEnvironment.getApplication()
        val store = PatternCandidateStore(app)
        val key = PatternCompatibility(store.localUserId(), MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch-A", "watch-native-si-v1")
        val profile = ReviewedPatternProfile("candidate-receipt", 1, key,
            listOf(PatternSource("source", "a".repeat(64), 1, "2026-10-14", "dumbbell_curl", "b".repeat(64))),
            setOf("dumbbell_curl"), 3, List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0),
            3_000_000_000, 1_800_000_000, 4_800_000_000, .2, .1)
        store.saveCandidate(profile).getOrThrow()
        val runtime = PatternRuntimeProfile.from(profile)
        val outbox = PatternTransferOutbox(app)
        // Transport fixture: these hashes exercise receipt state only; they are not adoption evidence.
        val command = outbox.enqueueTransition(runtime, PatternProfileOperation.ACTIVATE,
            PatternValidationEvidence("c".repeat(64), "d".repeat(64))).getOrThrow().command
        val controller = PatternAdoptionController(app)
        assertNull(controller.state(profile.id).getOrThrow().observedActive)
        val staged = PatternProfileAck(command.commandId, command.operation, runtime.id, runtime.version,
            runtime.candidateHash, command.runtimeHash, 1, key.deviceId, PatternProfileStatus.STAGED)
        outbox.acknowledge(staged).getOrThrow()
        val received = controller.state(profile.id).getOrThrow()
        assertNull(received.observedActive)
        assertEquals(PatternProfileStatus.STAGED, received.transition!!.ack!!.status)
        outbox.acknowledge(staged.copy(status = PatternProfileStatus.APPLIED,
            application = PatternProfileApplication("workout", "set", "attempt", "clock", 100, 200))).getOrThrow()
        assertEquals(runtime, controller.state(profile.id).getOrThrow().observedActive!!.command.runtime)
        // Turning an already observed model OFF must remain possible when its source labels/files are unusable.
        val disabled = controller.disable(profile.id).getOrThrow().command
        assertEquals(PatternProfileOperation.DISABLE, disabled.operation)
        assertEquals(runtime, controller.state(profile.id).getOrThrow().observedActive!!.command.runtime)
        outbox.acknowledge(PatternProfileAck(disabled.commandId, disabled.operation, runtime.id, runtime.version,
            runtime.candidateHash, disabled.runtimeHash, 2, key.deviceId, PatternProfileStatus.STAGED)).getOrThrow()
        assertNotNull(controller.state(profile.id).getOrThrow().observedActive)
        outbox.acknowledge(PatternProfileAck(disabled.commandId, disabled.operation, runtime.id, runtime.version,
            runtime.candidateHash, disabled.runtimeHash, 2, key.deviceId, PatternProfileStatus.DISABLED,
            application = PatternProfileApplication("next-workout", "next-set", "next-attempt", "clock", 300, 400))).getOrThrow()
        val restored = controller.state(profile.id).getOrThrow()
        assertNull(restored.observedActive)
        assertEquals(PatternProfileStatus.DISABLED, restored.transition!!.ack!!.status)
    }
}
