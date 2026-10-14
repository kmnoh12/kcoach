package dev.kyungmin.kcoach.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Model advice is a separate, disposable interpretation, never a user-entered personal fact. */
internal class AiCoachInterpretationStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai-coach-interpretations", Context.MODE_PRIVATE)
    fun current(accountId: String, history: AiCoachHistory): JSONArray = synchronized(lock) {
        val saved = runCatching { JSONArray(prefs.getString(accountId, "[]")) }.getOrDefault(JSONArray())
        val revisions = revisions(history)
        val rows = (0 until saved.length()).map { saved.getJSONObject(it) }.filter { row ->
            row.optString("profile_id") == history.profile.id && row.optLong("profile_revision") == history.profile.revision &&
                row.getJSONObject("source_revisions").let { refs -> refs.length() > 0 && refs.keys().asSequence().all { revisions[it] == refs.getString(it) } }
        }.takeLast(20).map { JSONObject(it.toString()).put("scope_changed", it.optString("history_fingerprint") != history.fingerprint) }
        val result = JSONArray(rows)
        if (saved.toString() != result.toString()) check(prefs.edit().putString(accountId, result.toString()).commit())
        result
    }
    fun save(accountId: String, history: AiCoachHistory, proposals: JSONArray, response: ChatGptResponseResult) = synchronized(lock) {
        require(proposals.length() <= 3)
        val rows = current(accountId, history)
        val revisions = revisions(history)
        for (i in 0 until proposals.length()) {
            val proposal = proposals.getJSONObject(i)
            require(proposal.keys().asSequence().toSet() == setOf("text", "citations"))
            val text = proposal.getString("text"); require(text.isNotBlank() && text.length <= 800)
            val refs = proposal.getJSONArray("citations"); require(refs.length() in 1..20)
            val ids = (0 until refs.length()).map { refs.get(it) as? String ?: error("Invalid interpretation reference") }
            require(ids.distinct().size == ids.size && ids.all { it in revisions })
            rows.put(JSONObject().put("kind", "MODEL_INTERPRETATION").put("text", text).put("citations", refs)
                .put("profile_id", history.profile.id).put("profile_revision", history.profile.revision)
                .put("history_fingerprint", history.fingerprint).put("source_revisions", JSONObject(ids.associateWith { revisions.getValue(it) }))
                .put("response_id", response.responseId).put("model", response.model).put("created_at_ms", System.currentTimeMillis()))
        }
        val unique = (0 until rows.length()).map { rows.getJSONObject(it) }.reversed().distinctBy { it.getString("text") }.take(20).reversed()
        check(prefs.edit().putString(accountId, JSONArray(unique).toString()).commit())
    }
    private fun revisions(history: AiCoachHistory) = history.records.associate { "session:${it.evidence.snapshot.sessionId}" to it.sourceRevision } +
        mapOf("profile:${history.profile.id}:${history.profile.revision}" to history.profile.revision.toString(),
            "summary:${history.profile.id}:${history.fingerprint}" to history.fingerprint)
    companion object { private val lock = Any() }
}
