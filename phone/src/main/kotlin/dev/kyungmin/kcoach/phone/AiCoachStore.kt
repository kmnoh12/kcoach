package dev.kyungmin.kcoach.phone

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate
import java.util.UUID

internal data class AiCoachProfile(
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
) {
    init {
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
        .put("goal", goal).put("experience", experience).put("constraints", constraints).put("equipment", equipment)
    companion object {
        fun fromJson(json: JSONObject) = AiCoachProfile(json.getString("id"), json.getLong("revision"),
            json.optionalDouble("height_cm"), json.optionalDouble("weight_kg"), json.optString("weight_measured_on"),
            json.optionalDouble("body_fat_percent"), json.optString("goal"), json.optString("experience"),
            json.optString("constraints"), json.optString("equipment"))
    }
}

internal data class AiCoachTurn(val question: String, val answer: String, val citations: List<String>,
    val requestedModel: String, val actualModel: String, val effort: String?, val responseId: String,
    val createdAtMs: Long, val contextJson: String) {
    fun json() = JSONObject().put("question", question).put("answer", answer).put("citations", JSONArray(citations))
        .put("requested_model", requestedModel).put("actual_model", actualModel).put("effort", effort ?: JSONObject.NULL)
        .put("response_id", responseId).put("created_at_ms", createdAtMs).put("context", JSONObject(contextJson))
    companion object {
        fun fromJson(json: JSONObject): AiCoachTurn {
            val refs = json.getJSONArray("citations")
            return AiCoachTurn(json.getString("question"), json.getString("answer"),
                (0 until refs.length()).map(refs::getString), json.getString("requested_model"), json.getString("actual_model"),
                json.opt("effort") as? String, json.getString("response_id"), json.getLong("created_at_ms"), json.getJSONObject("context").toString())
        }
    }
}

/** Personal facts and conversations stay separate from encrypted OAuth credentials. */
internal class AiCoachStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "ai-coach.json"))
    private fun read(): JSONObject {
        val bytes = try { file.readFully() } catch (_: FileNotFoundException) {
            return JSONObject().put("version", 1).put("accounts", JSONObject()).put("session_owners", JSONObject())
        }
        require(bytes.size <= 64_000_000) { "AI coach storage exceeds supported size" }
        return JSONObject(String(bytes, Charsets.UTF_8)).also { require(it.getInt("version") == 1) }
    }
    private fun write(root: JSONObject) {
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 64_000_000)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    fun profile(accountId: String): AiCoachProfile? = synchronized(lock) {
        read().getJSONObject("accounts").optJSONObject(accountId)?.optJSONObject("profile")?.let(AiCoachProfile::fromJson)
    }
    fun saveProfile(accountId: String, profile: AiCoachProfile): AiCoachProfile = synchronized(lock) {
        require(accountId.isNotBlank())
        val root = read()
        val accounts = root.getJSONObject("accounts")
        require(accounts.keys().asSequence().filter { it != accountId }.none {
            accounts.getJSONObject(it).getJSONObject("profile").getString("id") == profile.id
        }) { "A workout profile cannot be shared across accounts implicitly" }
        val entry = accounts.optJSONObject(accountId) ?: JSONObject().put("turns", JSONArray())
        val previous = entry.optJSONObject("profile")?.let(AiCoachProfile::fromJson)
        require(previous == null || previous.id == profile.id) { "Cannot silently replace profile identity" }
        require(previous == null || previous.revision == profile.revision) { "Profile changed while editing" }
        val saved = profile.copy(revision = Math.addExact(profile.revision, 1))
        accounts.put(accountId, entry.put("profile", saved.json()))
        write(root)
        saved
    }
    /** Linking existing records requires a user action and never takes another profile's records. */
    fun linkSessions(accountId: String, sessionIds: Collection<String>) = synchronized(lock) {
        val root = read()
        val profile = root.getJSONObject("accounts").getJSONObject(accountId).getJSONObject("profile").getString("id")
        val owners = root.getJSONObject("session_owners")
        require(sessionIds.all { it.isNotBlank() && (!owners.has(it) || owners.getString(it) == profile) }) {
            "Some records are already linked to another workout profile"
        }
        sessionIds.forEach { owners.put(it, profile) }
        write(root)
    }
    fun sessionIds(accountId: String): Set<String> = synchronized(lock) {
        val root = read()
        val id = root.getJSONObject("accounts").optJSONObject(accountId)?.optJSONObject("profile")?.getString("id")
            ?: return@synchronized emptySet()
        val owners = root.getJSONObject("session_owners")
        owners.keys().asSequence().filter { owners.getString(it) == id }.toSet()
    }
    fun turns(accountId: String): List<AiCoachTurn> = synchronized(lock) {
        val turns = read().getJSONObject("accounts").optJSONObject(accountId)?.optJSONArray("turns")
            ?: return@synchronized emptyList()
        (0 until turns.length()).map { AiCoachTurn.fromJson(turns.getJSONObject(it)) }
    }
    fun appendTurn(accountId: String, expectedProfileId: String, turn: AiCoachTurn) = synchronized(lock) {
        require(turn.question.isNotBlank() && turn.question.length <= 4_000 && turn.answer.isNotBlank() && turn.answer.length <= 12_000)
        require(turn.contextJson.length <= 60_000)
        val root = read()
        val entry = root.getJSONObject("accounts").getJSONObject(accountId)
        require(entry.getJSONObject("profile").getString("id") == expectedProfileId)
        entry.getJSONArray("turns").put(turn.json())
        write(root)
    }
    companion object { private val lock = Any() }
}

private fun JSONObject.optionalDouble(key: String): Double? = if (isNull(key)) null else getDouble(key)
