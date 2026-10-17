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
class PatternProfileStoreTest {
    @Test fun oldReceiptsWithoutCameraSafetyEvidenceAreQuarantinedAndCanStillBeDisabled() {
        val app = RuntimeEnvironment.getApplication()
        val store = PatternProfileStore(app, "watch-A")
        val runtime = runtime(1)
        val command = PatternProfileCommand("old", PatternProfileOperation.ACTIVATE, runtime, 0,
            PatternValidationEvidence("c".repeat(64), "d".repeat(64)))
        val envelope = WireCodec.decode(command.encode())
        val oldEncoded = WireCodec.encode(envelope.copy(payload = envelope.payload - "safetyVersion"))
        val old = PatternProfileCommand.decode(oldEncoded)
        assertEquals(0, old.evidence!!.safetyVersion)
        assertTrue(store.stage(old).isFailure)
        store.stage(command).getOrThrow()
        val frozen = store.beginSet(runtime.compatibility, "s", "set", "attempt", "clock", true).getOrThrow()!!
        store.confirmUsed(frozen, 10, 11).getOrThrow()
        val prefs = app.getSharedPreferences("reviewed-pattern-runtime", 0)
        val state = org.json.JSONObject(prefs.getString("state", null)!!)
        val slots = state.getJSONObject("slots")
        slots.getJSONObject(slots.keys().next()).put("currentActive", oldEncoded)
        state.getJSONObject("receipts").getJSONObject("old").put("command", oldEncoded)
        assertTrue(prefs.edit().putString("state", state.toString()).commit())
        assertNull(store.frozen(runtime.compatibility, true).getOrThrow())
        assertNull(store.beginSet(runtime.compatibility, "s", "next", "next-attempt", "clock", true).getOrThrow())
        val disable = PatternProfileCommand("disable-old", PatternProfileOperation.DISABLE, runtime, 1,
            old.evidence!!.copy(previousActiveHash = runtime.candidateHash))
        store.stage(disable).getOrThrow()
        assertNull(store.beginSet(runtime.compatibility, "s", "third", "third-attempt", "clock", true).getOrThrow())
        assertEquals(PatternProfileStatus.DISABLED, store.confirmDisabled(runtime.compatibility, "s", "third", "third-attempt", "clock", 20, 21).getOrThrow()!!.status)
    }
    @Test fun disablingWaitsForANewAttemptAndAnObservedBasicDetectorSample() {
        val app = RuntimeEnvironment.getApplication()
        val first = runtime(1)
        val store = PatternProfileStore(app, "watch-A")
        val evidence = PatternValidationEvidence("c".repeat(64), "d".repeat(64))
        store.stage(PatternProfileCommand("first", PatternProfileOperation.ACTIVATE, first, 0, evidence)).getOrThrow()
        val frozen = store.beginSet(first.compatibility, "s", "set1", "a1", "clock", true).getOrThrow()!!
        store.confirmUsed(frozen, 10, 11).getOrThrow()
        assertTrue(store.stage(PatternProfileCommand("wrong", PatternProfileOperation.DISABLE,
            first.copy(maximumDistance = .3), 1, evidence.copy(previousActiveHash = first.candidateHash))).isFailure)
        val disable = PatternProfileCommand("disable", PatternProfileOperation.DISABLE, first, 1,
            evidence.copy(previousActiveHash = first.candidateHash))
        assertEquals(PatternProfileStatus.STAGED, store.stage(disable).getOrThrow().status)
        assertEquals(frozen, store.beginSet(first.compatibility, "s", "set1", "a1", "clock", true).getOrThrow())
        val restarted = PatternProfileStore(app, "watch-A")
        assertNull(restarted.beginSet(first.compatibility, "s", "set2", "a2", "clock", true).getOrThrow())
        assertEquals(PatternProfileStatus.STAGED, restarted.stage(disable).getOrThrow().status)
        assertTrue(restarted.confirmDisabled(first.compatibility, "s", "set1", "a1", "clock", 20, 21).isFailure)
        // No sample reached set2; the following attempt must still acknowledge the same request.
        assertNull(restarted.beginSet(first.compatibility, "s", "set3", "a3", "clock", true).getOrThrow())
        val ack = restarted.confirmDisabled(first.compatibility, "s", "set3", "a3", "clock", 30, 31).getOrThrow()!!
        assertEquals(PatternProfileStatus.DISABLED, ack.status)
        assertEquals("a3", ack.application!!.attemptId)
        assertEquals(ack.copy(duplicate = true), PatternProfileStore(app, "watch-A")
            .confirmDisabled(first.compatibility, "s", "set3", "a3", "clock", 32, 33).getOrThrow())
        assertNull(restarted.frozen(first.compatibility, true).getOrThrow())
    }
    private fun runtime(version: Long) = PatternRuntimeProfile("candidate-$version", version,
        (if (version == 1L) "a" else "b").repeat(64),
        PatternCompatibility("owner", MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch-A", "watch-native-si-v1"),
        List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0), 3_000_000_000,
        1_800_000_000, 4_800_000_000, .2)

    @Test fun anUnpersonalizedSetStaysUnpersonalizedUntilTheNextAttempt() {
        val app = RuntimeEnvironment.getApplication()
        val first = runtime(1)
        val store = PatternProfileStore(app, "watch-A")
        assertNull(store.beginSet(first.compatibility, "session", "set-1", "attempt-1", "clock-A", active = true).getOrThrow())
        val evidence = PatternValidationEvidence("c".repeat(64), "d".repeat(64))
        assertEquals(PatternProfileStatus.STAGED, store.stage(PatternProfileCommand("activate-1",
            PatternProfileOperation.ACTIVATE, first, 0, evidence)).getOrThrow().status)
        val restarted = PatternProfileStore(app, "watch-A")
        assertNull(restarted.beginSet(first.compatibility, "session", "set-1", "attempt-1", "clock-A", active = true).getOrThrow())
        assertTrue(restarted.beginSet(first.compatibility, "session", "set-1", "attempt-1", "clock-B", active = true).isFailure)
        val next = restarted.beginSet(first.compatibility, "session", "set-2", "attempt-2", "clock-A", active = true).getOrThrow()!!
        assertEquals(first, next.runtime)
        assertEquals(PatternProfileStatus.APPLIED, restarted.confirmUsed(next, 10_000_000_000, 10_001_000_000).getOrThrow().status)
    }

    @Test fun activationAndRollbackWaitForNewSetsAndRestoreTheExactPreviousSnapshot() {
        val app = RuntimeEnvironment.getApplication()
        val store = PatternProfileStore(app, "watch-A")
        val first = runtime(1); val second = runtime(2)
        val evidence = PatternValidationEvidence("c".repeat(64), "d".repeat(64))
        val firstCommand = PatternProfileCommand("active-1", PatternProfileOperation.ACTIVATE, first, 0, evidence)
        store.stage(firstCommand).getOrThrow()
        val firstSet = store.beginSet(first.compatibility, "session", "set-1", "attempt-1", "clock-A", true).getOrThrow()!!
        store.confirmUsed(firstSet, 10_000_000_000, 10_001_000_000).getOrThrow()
        val secondCommand = PatternProfileCommand("active-2", PatternProfileOperation.ACTIVATE, second, 1,
            evidence.copy(previousActiveHash = first.candidateHash))
        store.stage(secondCommand).getOrThrow()
        assertEquals(first, store.beginSet(first.compatibility, "session", "set-1", "attempt-1", "clock-A", true).getOrThrow()!!.runtime)
        val secondSet = store.beginSet(first.compatibility, "session", "set-2", "attempt-2", "clock-A", true).getOrThrow()!!
        store.confirmUsed(secondSet, 20_000_000_000, 20_001_000_000).getOrThrow()
        val alteredPrevious = first.copy(maximumDistance = .3)
        assertTrue(store.stage(PatternProfileCommand("altered-rollback", PatternProfileOperation.ROLLBACK, alteredPrevious, 2,
            evidence.copy(previousActiveHash = second.candidateHash))).isFailure)
        val rollback = PatternProfileCommand("rollback", PatternProfileOperation.ROLLBACK, first, 2,
            evidence.copy(previousActiveHash = second.candidateHash))
        assertEquals(PatternProfileStatus.STAGED, store.stage(rollback).getOrThrow().status)
        assertEquals(second, store.beginSet(first.compatibility, "session", "set-2", "attempt-2", "clock-A", true).getOrThrow()!!.runtime)
        val third = PatternProfileStore(app, "watch-A").beginSet(first.compatibility, "session", "set-3", "attempt-3", "clock-A", true).getOrThrow()!!
        assertEquals(first, third.runtime)
        assertEquals(PatternProfileOperation.ROLLBACK, third.operation)
        assertEquals(PatternProfileStatus.APPLIED, store.confirmUsed(third, 30_000_000_000, 30_001_000_000).getOrThrow().status)
    }

    @Test fun stagingSurvivesRestartAndCannotReplaceTheFrozenProfileMidSet() {
        val app = RuntimeEnvironment.getApplication()
        val first = runtime(1)
        val command = PatternProfileCommand("command-1", PatternProfileOperation.SHADOW, first, 0)
        val store = PatternProfileStore(app, "watch-A")
        val saved = store.stage(command).getOrThrow()
        assertEquals(PatternProfileStatus.STAGED, saved.status)
        assertNull(saved.application)
        val restarted = PatternProfileStore(app, "watch-A")
        val frozen = restarted.beginSet(first.compatibility, "session", "set-1", "attempt-1", "clock-A").getOrThrow()!!
        assertEquals(first, frozen.runtime)
        assertEquals(1L, frozen.slotRevision)
        val second = runtime(2)
        assertEquals(PatternProfileStatus.STAGED, restarted.stage(PatternProfileCommand("command-2", PatternProfileOperation.SHADOW, second, 1)).getOrThrow().status)
        assertEquals(first, PatternProfileStore(app, "watch-A").beginSet(first.compatibility, "session", "set-1", "attempt-1", "clock-A").getOrThrow()!!.runtime)
        val ack = restarted.confirmUsed(frozen, 10_000_000_000, 10_001_000_000).getOrThrow()
        assertEquals(PatternProfileStatus.APPLIED, ack.status)
        assertEquals("attempt-1", ack.application!!.attemptId)
        assertTrue(restarted.stage(command).getOrThrow().duplicate)
        val next = restarted.beginSet(first.compatibility, "session", "set-2", "attempt-2", "clock-A").getOrThrow()!!
        assertEquals(second, next.runtime)
        assertEquals(PatternProfileStatus.CONFLICT, restarted.stage(PatternProfileCommand("stale", PatternProfileOperation.SHADOW, first, 0)).getOrThrow().status)
        assertEquals(PatternProfileStatus.WRONG_DEVICE, PatternProfileStore(app, "watch-B").stage(command).getOrThrow().status)
    }
}
