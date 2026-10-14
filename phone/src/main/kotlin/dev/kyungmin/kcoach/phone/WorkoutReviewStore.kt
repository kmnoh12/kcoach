package dev.kyungmin.kcoach.phone

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

internal class WorkoutReviewStore(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(context.noBackupFilesDir, "workout-reviews.json"))
    private fun read(): JSONObject {
        val bytes = try { file.readFully() } catch (_: FileNotFoundException) { return JSONObject().put("version", 1).put("entries", JSONObject()) }
        require(bytes.size <= 64_000_000)
        return JSONObject(String(bytes, Charsets.UTF_8)).also { require(it.getInt("version") == 1) }
    }
    private fun write(root: JSONObject) {
        val bytes = root.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= 64_000_000)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun key(accountId: String, context: WorkoutReviewContext, options: JSONObject, promptHash: String): String =
        aiCoachHash(JSONArray(listOf(accountId, context.profileId, context.sessionId, context.fingerprint,
            options.getString("model"), options.optJSONObject("reasoning")?.optString("effort"), promptHash)).toString())

    fun lookup(key: String): JSONObject? = synchronized(lock) { read().getJSONObject("entries").optJSONObject(key) }

    fun latest(accountId: String, context: WorkoutReviewContext): JSONObject? = synchronized(lock) {
        read().getJSONObject("entries").let { entries -> entries.keys().asSequence().map { entries.getJSONObject(it) }
            .filter { it.getString("account_id") == accountId && it.getString("profile_id") == context.profileId && it.getString("session_id") == context.sessionId }
            .maxByOrNull { it.getLong("created_at_ms") } }
    }

    /** Registration is persisted before the model call, and duplicate in-flight keys are rejected. */
    fun begin(key: String, accountId: String, context: WorkoutReviewContext, options: JSONObject, promptHash: String): JSONObject = synchronized(lock) {
        val root = read(); val entries = root.getJSONObject("entries")
        val old = entries.optJSONObject(key)
        require(old?.optString("status") != "REQUESTING") { "A review is already in flight" }
        if (old?.optString("status") == "COMPLETED") return@synchronized old
        val row = JSONObject().put("key", key).put("request_id", UUID.randomUUID().toString()).put("account_id", accountId)
            .put("profile_id", context.profileId).put("session_id", context.sessionId).put("input_fingerprint", context.fingerprint)
            .put("requested_model", options.getString("model")).put("effort", options.optJSONObject("reasoning")?.opt("effort") ?: JSONObject.NULL)
            .put("prompt_hash", promptHash).put("schema_version", 2).put("created_at_ms", System.currentTimeMillis())
            .put("context", JSONObject(context.json)).put("status", "REQUESTING")
        entries.put(key, row); write(root); row
    }

    fun complete(key: String, requestId: String, context: WorkoutReviewContext, result: ChatGptResponseResult): JSONObject = synchronized(lock) {
        val answer = WorkoutReviewAnswer.parse(result.text, context)
        require(result.responseId.isNotBlank() && result.model.isNotBlank())
        val root = read(); val row = root.getJSONObject("entries").getJSONObject(key)
        require(row.getString("request_id") == requestId && row.getString("status") == "REQUESTING")
        require(row.getString("input_fingerprint") == context.fingerprint && row.getString("profile_id") == context.profileId)
        require(WorkoutReviewContextBuilder(app).build(row.getString("account_id"), context.sessionId).fingerprint == context.fingerprint) {
            "Evidence changed before the completed answer was saved"
        }
        row.put("status", "COMPLETED").put("response_id", result.responseId).put("actual_model", result.model)
            .put("answer", JSONObject(answer.json)).put("completed_at_ms", System.currentTimeMillis())
        write(root); row
    }

    fun end(key: String, requestId: String, status: String, code: String): JSONObject? = synchronized(lock) {
        require(status in setOf("FAILED", "CANCELLED", "INTERRUPTED"))
        val root = read(); val row = root.getJSONObject("entries").optJSONObject(key) ?: return@synchronized null
        if (row.getString("request_id") != requestId || row.getString("status") != "REQUESTING") return@synchronized row
        row.put("status", status).put("error_code", code).put("completed_at_ms", System.currentTimeMillis()); write(root); row
    }
    companion object { private val lock = Any() }
}
