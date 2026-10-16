package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutProfileStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun emptyOldProfilesStayUnknownAndStaleLocalEditsCannotReplaceSavedFacts() {
        val store = UserProfileStore(app)
        val old = WorkoutProfile().json().apply { remove("gender"); remove("skeletal_muscle_kg") }
        val decoded = WorkoutProfile.fromJson(old)
        assertEquals("", decoded.gender); assertNull(decoded.skeletalMuscleKg)
        val first = store.saveDeviceProfile(DeviceWorkoutProfile(decoded))
        assertNull(first.profile.heightCm); assertNull(first.profile.ageYears)
        val changed = store.saveDeviceProfile(first.copy(profile = first.profile.copy(goal = "업데이트")))
        try { store.saveDeviceProfile(first); fail("Stale profile edit") } catch (_: IllegalArgumentException) { }
        try { store.saveDeviceProfile(DeviceWorkoutProfile(WorkoutProfile())); fail("Changed identity") } catch (_: IllegalArgumentException) { }
        assertEquals(changed, store.deviceProfile())
        try { WorkoutProfile(weightKg = 60.0, weightMeasuredOn = "2026-10-15", skeletalMuscleKg = 70.0); fail("Invalid units/value") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun changingDefaultWearingCannotChangeAnExistingAttemptOrInferItsDirection() {
        val store = UserProfileStore(app)
        val left = WorkoutWearingSelection("LEFT", "OUTSIDE")
        val first = store.saveDeviceProfile(DeviceWorkoutProfile(WorkoutProfile(), left))
        val wearing = WorkoutWearingStore(app)
        wearing.beginSession("frozen-session", wearing.defaultSelection().getOrThrow()).getOrThrow()
        val command = WearSync.richCommand("START", "frozen-session", "dumbbell_curl", "컬", 0, 8, 5.0, 60,
            setId = "frozen-set", attemptId = "frozen-attempt")
        val frozen = wearing.bind(command).getOrThrow()
        val right = WorkoutWearingSelection("RIGHT", "INSIDE")
        store.saveDeviceProfile(first.copy(wearing = right))
        assertEquals(left, WorkoutWearingStore(app).selection("frozen-session").getOrThrow())
        assertEquals(frozen, WorkoutWearingStore(app).bind(command).getOrThrow())
        assertEquals(right, wearing.defaultSelection().getOrThrow())
        wearing.beginSession("new-session", wearing.defaultSelection().getOrThrow()).getOrThrow()
        assertEquals(right, wearing.selection("new-session").getOrThrow())
        assertNull(wearing.selection("new-session").getOrThrow()!!.deviceTop)
    }

    @Test fun explicitlyClearedDefaultsDoNotResurrectLastWorkoutOrItsExerciseVariants() {
        val wearing = WorkoutWearingStore(app)
        wearing.beginSession("legacy-session", WorkoutWearingSelection("LEFT", "OUTSIDE", "HAND",
            mapOf("external_rotation" to StartWristContext.expectedVariant("external_rotation")!!))).getOrThrow()
        assertEquals(WorkoutWearingSelection("LEFT", "OUTSIDE"), wearing.defaultSelection().getOrThrow())
        UserProfileStore(app).saveDeviceProfile(DeviceWorkoutProfile(WorkoutProfile()))
        assertNull(WorkoutWearingStore(app).defaultSelection().getOrThrow())
        assertEquals("HAND", wearing.selection("legacy-session").getOrThrow()!!.deviceTop)
    }

}
