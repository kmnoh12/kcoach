package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartWristContextStoreTest {
    private fun payload(exercise: String = "external_rotation") = WearSync.richCommand("START", "session", exercise, "운동", 0, 8, 5.0, 60,
        setId = "set", attemptId = "attempt")
    private val choice = WorkoutWearingSelection("LEFT", "OUTSIDE", "ELBOW", mapOf("external_rotation" to "PALM_UP_HORIZONTAL"))
    @Test fun sessionAttemptAndLastChoiceSurviveReloadWithoutGuessingDirection() {
        val app = RuntimeEnvironment.getApplication()
        WorkoutWearingStore(app).beginSession("session", choice).getOrThrow()
        PhoneWorkoutService.send(app, WearSync.PATH_COMMAND, payload())
        val saved = WearSync.parse(PhoneCommandOutbox(app).pending()!!)
        val start = StartWristContextCodec.decode(saved[19])
        assertEquals("ELBOW", start.deviceTop); assertEquals("PALM_UP_HORIZONTAL", start.variant)
        assertEquals(PatternWearingContext.decode(saved[17]).owner, start.owner)
        val store = WorkoutWearingStore(app)
        assertEquals(choice, store.selection("session").getOrThrow())
        assertEquals(choice, store.lastSelection().getOrThrow())
        assertEquals(saved[19], WearSync.parse(store.bind(payload()).getOrThrow())[19])
        assertFalse(store.beginSession("session", choice.copy(deviceTop = "HAND")).isSuccess)
    }
    @Test fun oldSessionsKeepUnknownFactsAndAlreadyBoundAttemptsKeepAbsence() {
        val app = RuntimeEnvironment.getApplication()
        val owner = PatternCandidateStore(app).localUserId()
        val old = JSONObject().put("schemaVersion", 1).put("owner", owner).put("side", "LEFT").put("placement", "OUTSIDE")
            .put("bindings", JSONObject().put("set:attempt", JSONObject().put("exerciseId", "external_rotation")
                .put("binding", PatternWearingContext(owner, "LEFT", "OUTSIDE").encode())))
        app.getSharedPreferences("workout-wearing", 0).edit().putString("session:session", old.toString()).commit()
        val store = WorkoutWearingStore(app)
        val choice = store.selection("session").getOrThrow()!!
        assertNull(choice.deviceTop); assertTrue(choice.startVariants.isEmpty())
        assertTrue(WearSync.parse(store.bind(payload()).getOrThrow()).getOrNull(19).isNullOrEmpty())
    }
    @Test fun aChangedExplicitContextCannotOverwriteTheFrozenAttempt() {
        val app = RuntimeEnvironment.getApplication(); val store = WorkoutWearingStore(app)
        store.beginSession("session", choice).getOrThrow()
        val bound = store.bind(payload()).getOrThrow(); val fields = WearSync.parse(bound)
        val altered = WearSync.withStartWristContext(bound, StartWristContextCodec.decode(fields[19]).copy(deviceTop = "HAND"))
        assertFalse(store.bind(altered).isSuccess)
        assertEquals(fields[19], WearSync.parse(store.bind(payload()).getOrThrow())[19])
    }
    @Test fun deferredAndUnconfirmedWearingDoNotReceiveCandidateConditions() {
        val app = RuntimeEnvironment.getApplication(); val store = WorkoutWearingStore(app)
        store.beginSession("session", choice).getOrThrow()
        assertTrue(WearSync.parse(store.bind(payload("lat_pull_down")).getOrThrow()).getOrNull(19).isNullOrEmpty())
        store.beginSession("basic", null).getOrThrow()
        val basic = WearSync.parse(payload()).toMutableList().apply { this[2] = "basic" }.joinToString("|")
        assertTrue(WearSync.parse(store.bind(basic).getOrThrow()).getOrNull(19).isNullOrEmpty())
    }
}
