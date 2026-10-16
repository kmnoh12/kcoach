package dev.kyungmin.kcoach.phone

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

internal data class WorkoutProfile(
    val id: String = UUID.randomUUID().toString(),
    val revision: Long = 0,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val weightMeasuredOn: String = "",
    val bodyFatPercent: Double? = null,
    val goal: String = "",
    val experience: String = "",
    val constraints: String = "",
    val equipment: String = "",
    val ageYears: Int? = null,
    val calorieBasis: String = "",
    val gender: String = "",
    val skeletalMuscleKg: Double? = null,
) {
    init {
        require(ageYears == null || ageYears in 1..120)
        require(calorieBasis in setOf("", "RESISTANCE_MULTIPLE_8_15"))
        require(gender in setOf("", "MALE", "FEMALE", "OTHER"))
        require(skeletalMuscleKg == null || (skeletalMuscleKg.isFinite() && skeletalMuscleKg > 0 && skeletalMuscleKg <= 300))
        require(skeletalMuscleKg == null || weightKg == null || skeletalMuscleKg <= weightKg)
        require(id.isNotBlank() && revision >= 0)
        require(heightCm == null || (heightCm.isFinite() && heightCm in 50.0..260.0))
        require(weightKg == null || (weightKg.isFinite() && weightKg in 10.0..500.0))
        require(bodyFatPercent == null || (bodyFatPercent.isFinite() && bodyFatPercent in 0.0..80.0))
        require(weightMeasuredOn.isEmpty() || runCatching { LocalDate.parse(weightMeasuredOn) }.isSuccess)
        require(weightKg == null || weightMeasuredOn.isNotBlank())
        require(listOf(goal, experience, constraints, equipment).all { it.length <= 2_000 })
    }
    fun json(): JSONObject = JSONObject().put("id", id).put("revision", revision)
        .put("height_cm", heightCm ?: JSONObject.NULL).put("weight_kg", weightKg ?: JSONObject.NULL)
        .put("weight_measured_on", weightMeasuredOn).put("body_fat_percent", bodyFatPercent ?: JSONObject.NULL)
        .put("age_years", ageYears ?: JSONObject.NULL).put("calorie_basis", calorieBasis)
        .put("gender", gender).put("skeletal_muscle_kg", skeletalMuscleKg ?: JSONObject.NULL)
        .put("goal", goal).put("experience", experience).put("constraints", constraints).put("equipment", equipment)
    companion object {
        fun fromJson(json: JSONObject) = WorkoutProfile(json.getString("id"), json.getLong("revision"),
            json.optionalDouble("height_cm"), json.optionalDouble("weight_kg"), json.optString("weight_measured_on"),
            json.optionalDouble("body_fat_percent"), json.optString("goal"), json.optString("experience"),
            json.optString("constraints"), json.optString("equipment"), if (json.isNull("age_years")) null else json.getInt("age_years"), json.optString("calorie_basis"),
            json.optString("gender"), json.optionalDouble("skeletal_muscle_kg"))
    }
}

/** Body and wearing defaults stored only on this device. */
internal data class DeviceWorkoutProfile(val profile: WorkoutProfile, val wearing: WorkoutWearingSelection? = null) {
    init { require(wearing == null || (wearing.deviceTop == null && wearing.startVariants.isEmpty())) }
}

/** Migrates personal facts before removing the retired integration's storage. */
internal class UserProfileStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "workout-profile.json"))
    private fun read(): JSONObject {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists())
            return JSONObject(String(file.readFully(), Charsets.UTF_8)).also { require(it.getInt("version") == 1) }
        val old = AtomicFile(File(context.noBackupFilesDir, "ai-coach.json"))
        val root = JSONObject().put("version", 1).put("profiles", JSONObject()).put("session_owners", JSONObject())
        if (old.baseFile.exists() || File(old.baseFile.path + ".bak").exists()) {
            val legacy = JSONObject(String(old.readFully(), Charsets.UTF_8))
            require(legacy.getInt("version") == 1)
            val profiles = root.getJSONObject("profiles")
            val accounts = legacy.optJSONObject("accounts") ?: JSONObject()
            accounts.keys().forEach { key -> accounts.getJSONObject(key).optJSONObject("profile")?.let {
                val profile = WorkoutProfile.fromJson(it)
                profiles.put(profile.id, profile.json())
            } }
            legacy.optJSONObject("device_profile")?.let { device ->
                WorkoutProfile.fromJson(device.getJSONObject("profile"))
                root.put("device_profile", device)
            }
            // A unique legacy profile can become the device default; distinct people are never merged.
            if (!root.has("device_profile") && profiles.length() == 1) {
                val device = JSONObject().put("profile", profiles.getJSONObject(profiles.keys().next()))
                val wearing = context.getSharedPreferences("workout-wearing", Context.MODE_PRIVATE)
                val side = wearing.getString("last-side", null)
                val placement = wearing.getString("last-placement", null)
                if (side != null && placement != null) {
                    WorkoutWearingSelection(side, placement)
                    device.put("wearing", JSONObject().put("side", side).put("placement", placement))
                }
                root.put("device_profile", device)
            }
            root.put("session_owners", legacy.optJSONObject("session_owners") ?: JSONObject())
        }
        write(root)
        old.delete()
        return root
    }
    private fun write(root: JSONObject) {
        val output = file.startWrite()
        try { output.write(root.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    fun migrate() { synchronized(lock) { read() } }
    fun deviceProfile(): DeviceWorkoutProfile? = synchronized(lock) {
        read().optJSONObject("device_profile")?.let { json -> DeviceWorkoutProfile(
            WorkoutProfile.fromJson(json.getJSONObject("profile")), json.optJSONObject("wearing")?.let {
                WorkoutWearingSelection(it.getString("side"), it.getString("placement")) }) }
    }
    fun profileForSession(sessionId: String): WorkoutProfile? = synchronized(lock) {
        val root = read()
        val owner = root.getJSONObject("session_owners").optString(sessionId)
        root.getJSONObject("profiles").optJSONObject(owner)?.let(WorkoutProfile::fromJson)
    }
    fun saveDeviceProfile(value: DeviceWorkoutProfile): DeviceWorkoutProfile = synchronized(lock) {
        val root = read()
        val previous = root.optJSONObject("device_profile")?.getJSONObject("profile")?.let(WorkoutProfile::fromJson)
        require(previous == null || previous.id == value.profile.id) { "Local profile identity changed" }
        require((previous?.revision ?: 0) == value.profile.revision) { "Local profile changed while editing" }
        val saved = value.copy(profile = value.profile.copy(revision = Math.addExact(value.profile.revision, 1)))
        root.put("device_profile", JSONObject().put("profile", saved.profile.json()).put("wearing", saved.wearing?.let {
            JSONObject().put("side", it.side).put("placement", it.placement)
        } ?: JSONObject.NULL))
        write(root)
        saved
    }
    /** Removes only retired authentication, generated text and task preferences, never workout evidence. */
    fun removeRetiredConnectionData() {
        listOf("chatgpt-accounts.enc", "ai-calibration-runs.json", "workout-reviews.json").forEach {
            AtomicFile(File(context.noBackupFilesDir, it)).delete()
        }
        listOf("ai-task-settings", "ai-coach-summary", "ai-coach-interpretations").forEach {
            check(context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit())
        }
        runCatching {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry("kcoach.chatgpt.accounts.v1")
        }
    }
    companion object { private val lock = WorkoutRuntimeLock.monitor }
}

private fun JSONObject.optionalDouble(key: String): Double? = if (isNull(key)) null else getDouble(key)
