package dev.kyungmin.kcoach.phone

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserProfileMigrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun legacy(device: WorkoutProfile?, accounts: List<WorkoutProfile> = emptyList()) {
        android.util.AtomicFile(File(app.noBackupFilesDir, "workout-profile.json")).delete()
        val root = JSONObject().put("version", 1).put("accounts", JSONObject().apply {
            accounts.forEachIndexed { i, value -> put("account-$i", JSONObject().put("profile", value.json()).put("turns", org.json.JSONArray().put("discard this conversation"))) }
        }).put("session_owners", JSONObject().apply { accounts.firstOrNull()?.let { put("old-session", it.id) } })
        device?.let { root.put("device_profile", JSONObject().put("profile", it.json())
            .put("wearing", JSONObject().put("side", "RIGHT").put("placement", "INSIDE"))) }
        File(app.noBackupFilesDir, "ai-coach.json").writeText(root.toString())
    }
    @Test fun migratesDeviceFactsAndHistoricalOwnerBeforeDeletingLegacyChat() {
        val body = WorkoutProfile(heightCm = 180.0, weightKg = 75.0, weightMeasuredOn = "2026-10-16", goal = "근비대")
        val historical = WorkoutProfile(weightKg = 70.0, weightMeasuredOn = "2026-09-01")
        legacy(body, listOf(historical))
        val store = UserProfileStore(app)
        store.migrate()
        assertEquals(body, store.deviceProfile()!!.profile)
        assertEquals(WorkoutWearingSelection("RIGHT", "INSIDE"), store.deviceProfile()!!.wearing)
        assertEquals(historical, store.profileForSession("old-session"))
        assertFalse(File(app.noBackupFilesDir, "ai-coach.json").exists())
        assertFalse(File(app.noBackupFilesDir, "workout-profile.json").readText().contains("conversation"))
        val wearing = WorkoutWearingStore(app)
        wearing.beginSession("new-session", store.deviceProfile()!!.wearing).getOrThrow()
        val updated = store.saveDeviceProfile(store.deviceProfile()!!.copy(profile = body.copy(weightKg = 80.0)))
        assertEquals(body, wearing.frozenBodyProfile("new-session", null).getOrThrow())
        assertEquals(updated, UserProfileStore(app).deviceProfile())
        assertEquals(historical, store.profileForSession("old-session"))
    }
    @Test fun distinctLegacyProfilesArePreservedWithoutChoosingAnotherPersonsDefaults() {
        val one = WorkoutProfile(goal = "one"); val two = WorkoutProfile(goal = "two")
        legacy(null, listOf(one, two))
        val store = UserProfileStore(app)
        assertNull(store.deviceProfile())
        assertEquals(one, store.profileForSession("old-session"))
        val saved = JSONObject(File(app.noBackupFilesDir, "workout-profile.json").readText()).getJSONObject("profiles")
        assertEquals(two.json().toString(), saved.getJSONObject(two.id).toString())
    }
    @Test fun uniqueAccountProfileBecomesOfflineDefault() {
        val profile = WorkoutProfile(goal = "keep me")
        legacy(null, listOf(profile))
        app.getSharedPreferences("workout-wearing", Context.MODE_PRIVATE).edit()
            .putString("last-side", "LEFT").putString("last-placement", "OUTSIDE").commit()
        assertEquals(profile, UserProfileStore(app).deviceProfile()!!.profile)
        assertEquals(WorkoutWearingSelection("LEFT", "OUTSIDE"), WorkoutWearingStore(app).defaultSelection().getOrThrow())
    }
    @Test fun malformedLegacyFactsAreNotDeletedOrReplacedWithEmptyProfile() {
        android.util.AtomicFile(File(app.noBackupFilesDir, "workout-profile.json")).delete()
        val old = File(app.noBackupFilesDir, "ai-coach.json").apply { writeText("broken") }
        assertTrue(runCatching { UserProfileStore(app).migrate() }.isFailure)
        assertEquals("broken", old.readText())
        assertFalse(File(app.noBackupFilesDir, "workout-profile.json").exists())
    }
    @Test fun retiredCredentialsAndCachesAreDeletedWithoutTouchingMeasurements() {
        val keep = File(app.filesDir, "captures").apply { mkdirs() }.resolve("keep.kcs").apply { writeText("sensor") }
        listOf("chatgpt-accounts.enc", "ai-calibration-runs.json", "workout-reviews.json").forEach {
            File(app.noBackupFilesDir, it).writeText("retired")
        }
        app.getSharedPreferences("ai-task-settings", Context.MODE_PRIVATE).edit().putBoolean("old", true).commit()
        val store = UserProfileStore(app)
        val body = store.saveDeviceProfile(DeviceWorkoutProfile(WorkoutProfile(goal = "keep")))
        repeat(2) { store.removeRetiredConnectionData() }
        assertEquals(body, store.deviceProfile())
        assertEquals("sensor", keep.readText())
        assertFalse(File(app.noBackupFilesDir, "chatgpt-accounts.enc").exists())
        assertTrue(app.getSharedPreferences("ai-task-settings", Context.MODE_PRIVATE).all.isEmpty())
    }
}
