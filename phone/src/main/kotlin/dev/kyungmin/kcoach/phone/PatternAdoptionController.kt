package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*

data class PatternAdoptionState(val candidate: PatternCandidate, val observedActive: PendingPatternTransfer?,
    val previousObserved: PendingPatternTransfer?, val transition: PendingPatternTransfer?)

/** A button queues a checked request. Only an observed-use ACK changes the reported active state. */
class PatternAdoptionController(context: Context) {
    private val candidates = PatternCandidateStore(context)
    private val outbox = PatternTransferOutbox(context)

    fun state(candidateId: String): Result<PatternAdoptionState> = synchronized(lock) {
        runCatching {
            val candidate = candidates.load(candidateId).getOrThrow()
            val history = outbox.activeHistory(candidate.profile.compatibility).getOrThrow()
            PatternAdoptionState(candidate, history.firstOrNull(), history.getOrNull(1),
                outbox.latestTransition(candidate.profile.compatibility).getOrThrow())
        }
    }

    fun activate(candidateId: String): Result<PendingPatternTransfer> = synchronized(lock) {
        runCatching {
            val state = state(candidateId).getOrThrow()
            val pending = state.transition?.takeIf { it.command.operation == PatternProfileOperation.ACTIVATE &&
                it.command.runtime.id == candidateId && it.ack?.status in setOf(null, PatternProfileStatus.STAGED) }
            if (pending != null) {
                val evidence = requireNotNull(pending.command.evidence)
                val checked = candidates.verifyAdoptionEvidence(candidateId, evidence, requireBaselineHash = true).getOrThrow()
                require(PatternRuntimeProfile.from(checked.profile) == pending.command.runtime)
                return@runCatching outbox.enqueueTransition(pending.command.runtime, pending.command.operation, evidence).getOrThrow()
            }
            val checked = candidates.validateForAdoption(candidateId).getOrThrow()
            outbox.enqueueTransition(PatternRuntimeProfile.from(checked.candidate.profile), PatternProfileOperation.ACTIVATE,
                checked.evidence).getOrThrow()
        }
    }

    fun rollback(candidateId: String): Result<PendingPatternTransfer> = synchronized(lock) {
        runCatching {
            val state = state(candidateId).getOrThrow()
            val active = requireNotNull(state.observedActive) { "No observed active profile" }
            require(active.command.runtime.id == candidateId) { "Only the currently observed active profile can be changed" }
            val previous = requireNotNull(state.previousObserved) { "No previous observed profile" }
            val evidence = requireNotNull(previous.command.evidence)
            val candidate = candidates.verifyAdoptionEvidence(previous.command.runtime.id, evidence).getOrThrow()
            require(PatternRuntimeProfile.from(candidate.profile) == previous.command.runtime) { "Previous frozen runtime changed" }
            outbox.enqueueTransition(previous.command.runtime, PatternProfileOperation.ROLLBACK,
                evidence.copy(previousActiveHash = active.command.runtime.candidateHash)).getOrThrow()
        }
    }

    fun disable(candidateId: String): Result<PendingPatternTransfer> = synchronized(lock) {
        runCatching {
            val state = state(candidateId).getOrThrow()
            val active = requireNotNull(state.observedActive) { "No observed active profile" }
            require(active.command.runtime.id == candidateId) { "Only the currently observed active profile can be disabled" }
            val evidence = requireNotNull(active.command.evidence)
            // Disabling does not assert accuracy. Keep this escape path usable after a bad label or lost original.
            outbox.enqueueTransition(active.command.runtime, PatternProfileOperation.DISABLE,
                evidence.copy(previousActiveHash = active.command.runtime.candidateHash)).getOrThrow()
        }
    }

    companion object { private val lock = Any() }
}
