package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.WorkoutComparison
import dev.kyungmin.kcoach.core.WorkoutCondition
import org.json.JSONArray
import org.json.JSONObject

/** A disposable projection of owned originals. Model advice never becomes a saved personal fact. */
internal class AiCoachSummaryStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai-coach-summary", Context.MODE_PRIVATE)

    fun refresh(accountId: String, history: AiCoachHistory): JSONObject = synchronized(lock) {
        require(accountId.isNotBlank())
        val fingerprint = history.fingerprint
        val cached = runCatching { prefs.getString(accountId, null)?.let(::JSONObject) }.getOrNull()
        if (cached?.optInt("version") == VERSION && cached.optString("input_fingerprint") == fingerprint &&
            cached.optString("profile_id") == history.profile.id) {
            cached.optJSONObject("summary")?.takeIf { it.optString("input_fingerprint") == fingerprint &&
                it.optString("kind") == "COMPUTED_FACTS" }?.let { return@synchronized it }
        }
        val summary = build(history, fingerprint)
        val entry = JSONObject().put("version", VERSION).put("profile_id", history.profile.id)
            .put("input_fingerprint", fingerprint).put("summary", summary)
            .put("source_revisions", JSONObject(history.records.associate { it.evidence.snapshot.sessionId to it.sourceRevision }))
        check(prefs.edit().putString(accountId, entry.toString()).commit()) { "Failed to save personal fact summary" }
        summary
    }

    private fun build(history: AiCoachHistory, fingerprint: String): JSONObject {
        val allSets = history.records.flatMap { it.evidence.snapshot.plannedSets }.filter { it.completed }
        val normal = allSets.filter { it.setType == "NORMAL" }
        val totals = WorkoutComparison.totals(normal)
        val conditions = linkedMapOf<WorkoutCondition, MutableList<Pair<AiCoachHistoryRecord, List<dev.kyungmin.kcoach.core.PlannedSetSnapshot>>>>()
        history.records.forEach { record -> WorkoutComparison.normalGroups(record.evidence.snapshot).forEach { (condition, sets) ->
            conditions.getOrPut(condition) { mutableListOf() }.add(record to sets)
        } }
        val trends = JSONArray()
        conditions.entries.take(12).forEach { (condition, rows) ->
            val current = rows.first().first
            val baseline = WorkoutComparison.previous(current.evidence.snapshot, history.records.map { it.evidence.snapshot })
            val previous = history.records.firstOrNull { it.evidence.snapshot.sessionId == baseline?.sessionId }
            val comparison = WorkoutComparison.compare(current.evidence.snapshot, previous?.evidence?.snapshot).single { it.condition == condition }
            val row = comparison.json().put("exercise_name", rows.first().second.first().exerciseName)
                .put("recorded_sessions", rows.size).put("current_evidence_id", "session:${current.evidence.snapshot.sessionId}")
                .put("baseline_evidence_id", baseline?.let { "session:${it.sessionId}" } ?: JSONObject.NULL)
            // Free-text exercise names and large ladders must not make the request unbounded.
            if (row.toString().length <= 1_600 && trends.toString().length + row.toString().length <= 6_000) trends.put(row)
        }
        return JSONObject().put("evidence_id", "summary:${history.profile.id}:$fingerprint")
            .put("input_fingerprint", fingerprint).put("profile_revision", history.profile.revision)
            .put("kind", "COMPUTED_FACTS").put("completed_sessions", history.records.size)
            .put("linked_sessions", history.linkedCount).put("unavailable_linked_sessions", history.linkedCount - history.records.size)
            .put("first_started_at_ms", history.records.minOfOrNull { it.evidence.snapshot.startedAtMs } ?: JSONObject.NULL)
            .put("latest_started_at_ms", history.records.maxOfOrNull { it.evidence.snapshot.startedAtMs } ?: JSONObject.NULL)
            .put("normal_sets", totals.sets).put("normal_reps", totals.reps ?: JSONObject.NULL)
            .put("normal_volume_kg_reps", totals.volumeKgReps ?: JSONObject.NULL)
            .put("warmup_sets", allSets.count { it.setType == "WARMUP" })
            .put("unknown_rep_sets", normal.count { it.performedReps == null })
            .put("unknown_weight_sets", normal.count { it.weightKg == null })
            .put("exercise_conditions", conditions.size).put("recent_condition_trends", trends)
            .put("conditions_omitted", conditions.size - trends.length())
            .put("interpretation_note", "전체 연결 완료 기록에서 앱이 계산한 사실. 미입력은 0이 아니며 모델 조언·가설은 개인 사실에 포함하지 않음. 조건별 추세는 동일 루틴의 직전 기록과 비교하고 다른 조건이면 새 조건으로 표시.")
    }

    companion object { private val lock = Any(); private const val VERSION = 1 }
}
