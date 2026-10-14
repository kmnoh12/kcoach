package dev.kyungmin.kcoach.phone

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** AI provenance is additional metadata; candidate/original storage and adoption remain the existing stores. */
internal class AiCalibrationStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "ai-calibration-runs.json"))
    private fun read(): JSONObject {
        val bytes = try { file.readFully() } catch (_: FileNotFoundException) { return JSONObject().put("version", 1).put("runs", JSONObject()) }
        require(bytes.size <= 64_000_000)
        return JSONObject(String(bytes, Charsets.UTF_8)).also { require(it.getInt("version") == 1) }
    }
    private fun write(root: JSONObject) {
        val bytes = root.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= 64_000_000)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun key(accountId: String, input: AiCalibrationInput, options: JSONObject, prompt: String) =
        aiCoachHash(JSONArray(listOf(accountId, input.hash, options.getString("model"), options.optJSONObject("reasoning")?.optString("effort"), prompt)).toString())
    fun get(key: String): JSONObject? = synchronized(lock) { read().getJSONObject("runs").optJSONObject(key) }
    fun latest(accountId: String, candidateId: String): JSONObject? = synchronized(lock) {
        val runs = read().getJSONObject("runs")
        runs.keys().asSequence().map { runs.getJSONObject(it) }.filter { it.getString("account_id") == accountId &&
            it.getString("base_candidate_id") == candidateId }.maxByOrNull { it.getLong("started_at_ms") }
    }
    fun provenance(accountId: String, candidateId: String): JSONObject? = synchronized(lock) {
        val runs = read().getJSONObject("runs")
        runs.keys().asSequence().map { runs.getJSONObject(it) }.filter { row -> row.getString("account_id") == accountId &&
            row.getString("status") == "COMPLETED" && (row.getString("base_candidate_id") == candidateId ||
                row.optJSONArray("candidates")?.let { candidates -> (0 until candidates.length()).any { candidates.getJSONObject(it).getString("id") == candidateId } } == true) }
            .maxByOrNull { it.getLong("started_at_ms") }
    }
    fun begin(key: String, accountId: String, input: AiCalibrationInput, options: JSONObject, promptHash: String): JSONObject = synchronized(lock) {
        val root = read(); val runs = root.getJSONObject("runs")
        val old = runs.optJSONObject(key)
        require(old?.optString("status") != "REQUESTING")
        if (old?.optString("status") == "COMPLETED") return@synchronized old
        val row = JSONObject().put("key", key).put("run_id", UUID.randomUUID().toString()).put("status", "REQUESTING")
            .put("account_id", accountId).put("input_hash", input.hash).put("base_candidate_id", input.candidate.profile.id)
            .put("base_candidate_hash", input.candidate.sha256).put("schema_version", 1).put("prompt_hash", promptHash)
            .put("requested_model", options.getString("model")).put("effort", options.optJSONObject("reasoning")?.opt("effort") ?: JSONObject.NULL)
            .put("started_at_ms", System.currentTimeMillis()).put("input", JSONObject(input.json))
        runs.put(key, row); write(root); row
    }
    fun complete(key: String, runId: String, answer: AiCalibrationAnswer, response: ChatGptResponseResult,
        candidates: List<Pair<PatternCandidate, JSONObject>>, timings: JSONObject): JSONObject = synchronized(lock) {
        val root = read(); val row = root.getJSONObject("runs").getJSONObject(key)
        require(row.getString("run_id") == runId && row.getString("status") == "REQUESTING")
        require(response.responseId.isNotBlank() && response.model.isNotBlank() && candidates.size == answer.proposals.size)
        require(JSONObject(answer.json).getString("input_hash") == row.getString("input_hash"))
        row.put("status", "COMPLETED").put("answer", JSONObject(answer.json)).put("response_id", response.responseId)
            .put("actual_model", response.model).put("finished_at_ms", System.currentTimeMillis()).put("timings_ms", timings)
            .put("candidates", JSONArray(candidates.map { (candidate, replay) -> JSONObject().put("id", candidate.profile.id)
                .put("hash", candidate.sha256).put("version", candidate.profile.version).put("training_replay", replay)
                .put("adoption_status", "INDEPENDENT_VALIDATION_PENDING") }))
        write(root); row
    }
    fun end(key: String, runId: String, status: String, code: String) = synchronized(lock) {
        require(status in setOf("FAILED", "CANCELLED", "INTERRUPTED"))
        val root = read(); val row = root.getJSONObject("runs").optJSONObject(key) ?: return@synchronized
        if (row.getString("run_id") != runId || row.getString("status") != "REQUESTING") return@synchronized
        row.put("status", status).put("error_code", code).put("finished_at_ms", System.currentTimeMillis()); write(root)
    }
    companion object { private val lock = Any() }
}
