package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PatternBenchmarkStoreTest {
    @Test fun aFixedDatasetSurvivesRestartAndRejectsChangedOrMissingOriginalsAndLabels() {
        val app = RuntimeEnvironment.getApplication()
        val key = PatternCompatibility(PatternCandidateStore(app).localUserId(), MeasurementRecipe.CURL, "LEFT", "OUTSIDE", "watch", "watch-native-si-v1")
        val records = (0..30).map { i -> SensorCaptureRecord(SensorSample(1_000_000_000L + i * 20_000_000, 0f, 0f, 9.8f, 0f, 0f, 0f, 3)) }
        val bytes = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, records, 50.0) }.toByteArray()
        val set = ReviewedPatternSet("capture", SensorCaptureFormat.sha256(bytes), "dumbbell_curl", key, "2026-10-14",
            SensorCaptureDocument(2, 50.0, records), emptyList())
        val input = HeldOutPatternSet(set, SetCountReference("set", "attempt", 0, 100))
        val store = PatternBenchmarkStore(app)
        val fixed = store.freeze(listOf(input)).getOrThrow()
        val restarted = PatternBenchmarkStore(app)
        assertEquals(fixed, restarted.load(fixed.id).getOrThrow())
        assertEquals(1, restarted.list(key).getOrThrow().size)
        val runtime = PatternRuntimeProfile("candidate", 1, "a".repeat(64), key, List(33) { List(6) { 0.0 } }, List(6) { 1.0 }, listOf(0),
            3_000_000_000, 1_800_000_000, 4_800_000_000, .2)
        assertTrue(restarted.compare(fixed.id, runtime, runtime, listOf(input)).getOrThrow().passed)
        assertFalse(restarted.compare(fixed.id, runtime, runtime, emptyList()).isSuccess)
        assertFalse(restarted.compare(fixed.id, runtime, runtime, listOf(input.copy(countReference = input.countReference.copy(reps = 1)))).isSuccess)
        val changed = set.copy(exerciseId = "barbell_biceps_curl")
        assertFalse(restarted.compare(fixed.id, runtime, runtime, listOf(input.copy(set = changed))).isSuccess)
        assertEquals(fixed, restarted.load(fixed.id).getOrThrow())
    }
}
