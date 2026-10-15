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

    @Test fun offlineDefaultsSurviveRestartWithoutCreatingOrChangingAnAiAccount() {
        val store = AiCoachStore(app)
        val account = store.saveProfile("account-a", AiCoachProfile(goal = "원래 계정 목표"))
        store.linkSessions("account-a", listOf("account-session"))
        assertNull(store.deviceProfile())
        val saved = store.saveDeviceProfile(DeviceWorkoutProfile(AiCoachProfile(heightCm = 175.0,
            weightKg = 70.0, weightMeasuredOn = "2026-10-15", ageYears = 24, gender = "MALE",
            skeletalMuscleKg = 32.5), WorkoutWearingSelection("RIGHT", "INSIDE")))
        assertEquals(saved, AiCoachStore(app).deviceProfile())
        assertEquals(account, store.profile("account-a"))
        assertEquals(setOf("account-session"), store.sessionIds("account-a"))
        assertNull(store.profile("account-b"))
        assertEquals(saved.wearing, WorkoutWearingStore(app).defaultSelection().getOrThrow())
    }

    @Test fun emptyOldProfilesStayUnknownAndStaleLocalEditsCannotReplaceSavedFacts() {
        val store = AiCoachStore(app)
        val old = AiCoachProfile().json().apply { remove("gender"); remove("skeletal_muscle_kg") }
        val decoded = AiCoachProfile.fromJson(old)
        assertEquals("", decoded.gender); assertNull(decoded.skeletalMuscleKg)
        val first = store.saveDeviceProfile(DeviceWorkoutProfile(decoded))
        assertNull(first.profile.heightCm); assertNull(first.profile.ageYears)
        val changed = store.saveDeviceProfile(first.copy(profile = first.profile.copy(goal = "업데이트")))
        try { store.saveDeviceProfile(first); fail("Stale profile edit") } catch (_: IllegalArgumentException) { }
        try { store.saveDeviceProfile(DeviceWorkoutProfile(AiCoachProfile())); fail("Changed identity") } catch (_: IllegalArgumentException) { }
        assertEquals(changed, store.deviceProfile())
        try { AiCoachProfile(weightKg = 60.0, weightMeasuredOn = "2026-10-15", skeletalMuscleKg = 70.0); fail("Invalid units/value") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun changingDefaultWearingCannotChangeAnExistingAttemptOrInferItsDirection() {
        val store = AiCoachStore(app)
        val left = WorkoutWearingSelection("LEFT", "OUTSIDE")
        val first = store.saveDeviceProfile(DeviceWorkoutProfile(AiCoachProfile(), left))
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
        AiCoachStore(app).saveDeviceProfile(DeviceWorkoutProfile(AiCoachProfile()))
        assertNull(WorkoutWearingStore(app).defaultSelection().getOrThrow())
        assertEquals("HAND", wearing.selection("legacy-session").getOrThrow()!!.deviceTop)
    }

    @Test fun localBodyFactsFreezeAtStartAndCannotRewriteOlderWorkoutsOrAnotherAccountProfile() {
        val store = AiCoachStore(app)
        val wearing = WorkoutWearingStore(app)
        val account = store.saveProfile("account-a", AiCoachProfile(weightKg = 90.0, weightMeasuredOn = "2026-10-01"))
        wearing.beginSession("old-session", null).getOrThrow()
        val body = store.saveDeviceProfile(DeviceWorkoutProfile(AiCoachProfile(weightKg = 70.0,
            weightMeasuredOn = "2026-10-01", ageYears = 24, calorieBasis = "RESISTANCE_MULTIPLE_8_15")))
        wearing.beginSession("local-session", null).getOrThrow()
        store.saveDeviceProfile(body.copy(profile = body.profile.copy(weightKg = 75.0)))
        assertEquals(body.profile, WorkoutWearingStore(app).frozenBodyProfile("local-session", account).getOrThrow())
        assertEquals(account, wearing.frozenBodyProfile("old-session", account).getOrThrow())
        assertNull(wearing.frozenBodyProfile("old-session", null).getOrThrow())
        assertEquals(account, store.profile("account-a"))
    }

    @Test fun explicitImportCopiesOnlyBodyFactsAndRetainsEachAccountsRecordOwner() {
        val store = AiCoachStore(app)
        val first = store.saveProfile("account-a", AiCoachProfile(goal = "계정 A 목표", equipment = "기존 기구"))
        val other = store.saveProfile("account-b", AiCoachProfile(goal = "계정 B 목표"))
        store.linkSessions("account-a", listOf("owned-session"))
        val local = store.saveDeviceProfile(DeviceWorkoutProfile(AiCoachProfile(heightCm = 180.0,
            gender = "OTHER", skeletalMuscleKg = 32.0)))
        val imported = store.copyDeviceBodyToAccount("account-a")
        assertEquals(first.id, imported.id); assertEquals(first.revision + 1, imported.revision)
        assertEquals(180.0, imported.heightCm!!, 0.0); assertEquals("OTHER", imported.gender)
        assertEquals("계정 A 목표", imported.goal); assertEquals("기존 기구", imported.equipment)
        assertEquals(setOf("owned-session"), store.sessionIds("account-a"))
        assertEquals(other, store.profile("account-b")); assertEquals(local, store.deviceProfile())
        assertNotEquals(imported.id, local.profile.id)
    }
}
