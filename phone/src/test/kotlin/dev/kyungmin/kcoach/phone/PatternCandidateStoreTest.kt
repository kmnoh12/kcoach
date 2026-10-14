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
class PatternCandidateStoreTest {
    @Test fun aStoredCandidateIsFrozenSurvivesRestartAndHasNoValidationWithoutNewClips() {
        val app = RuntimeEnvironment.getApplication()
        val store = PatternCandidateStore(app)
        val key = PatternCompatibility(store.localUserId(), MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch-A", "watch-native-si-v1")
        val source = PatternSource("capture-A", "a".repeat(64), 1, "2026-10-14", "dumbbell_curl", "b".repeat(64))
        val profile = ReviewedPatternProfile("candidate-1", 1, key, listOf(source), setOf("dumbbell_curl"), 3,
            List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0), 3_000_000_000, 1_800_000_000, 4_800_000_000, .2, .1)
        val saved = store.saveCandidate(profile).getOrThrow()
        val restarted = PatternCandidateStore(app)
        assertEquals(key.localUserId, restarted.localUserId())
        assertEquals(profile, restarted.load(saved.profile.id).getOrThrow().profile)
        assertFalse(restarted.saveCandidate(profile.copy(maximumDistance = .3)).isSuccess)
        assertTrue(restarted.validate(saved.profile.id).isFailure) // original training sources are absent, never blindly trusted
        assertEquals(1, restarted.list().getOrThrow().size)
    }
}
