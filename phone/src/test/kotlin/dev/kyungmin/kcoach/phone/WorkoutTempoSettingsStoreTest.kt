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
class WorkoutTempoSettingsStoreTest {
    @Test fun changesAndDisableWaitForTheNextAttemptAndOldAcksCannotConfirmNewSettings() {
        val app = RuntimeEnvironment.getApplication()
        val store = WorkoutTempoSettingsStore(app)
        val wearing = WorkoutWearingStore(app)
        wearing.beginSession("workout", WorkoutWearingSelection("LEFT", "OUTSIDE")).getOrThrow()
        fun start(id: String) = WearSync.richCommand("START", "workout", "dumbbell_curl", "컬", 0, 8, 5.0, 60, setId = id, attemptId = "attempt-$id")
        val old = wearing.bind(start("first")).getOrThrow()
        assertFalse(WorkoutTempoSettingsCodec.decode(WearSync.parse(old)[21]).enabled)
        val enabled = WorkoutTempoSettings("dumbbell_curl", true, mapOf(WorkoutTempoMetric.REPETITION to WorkoutTempoRange(2_000, 3_000)))
        store.save(enabled).getOrThrow()
        assertEquals(old, wearing.bind(start("first")).getOrThrow())
        val next = wearing.bind(start("second")).getOrThrow()
        assertEquals(enabled, WorkoutTempoSettingsCodec.decode(WearSync.parse(next)[21]))
        store.save(enabled.copy(enabled = false)).getOrThrow()
        assertEquals(next, wearing.bind(start("second")).getOrThrow())
        assertFalse(WorkoutTempoSettingsCodec.decode(WearSync.parse(wearing.bind(start("third")).getOrThrow())[21]).enabled)
        val outbox = PhoneCommandOutbox(app)
        val queued = WearSync.parse(outbox.enqueue(next)); val revision = queued[10]
        assertFalse(outbox.acknowledge("1|workout|$revision|APPLIED|$revision"))
        assertTrue(outbox.acknowledge("1|workout|$revision|APPLIED|$revision||${WorkoutTempoSettingsCodec.hash(queued[21])}"))
        assertNull(outbox.pending())
    }
}
