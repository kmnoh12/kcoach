package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class AiCoachHistoryRecord(val evidence: FinishedWorkoutStore.Evidence,
    val analysis: SetAnalysisStore.SessionRead, val sourceRevision: String)
internal data class AiCoachHistory(val profile: AiCoachProfile, val linkedCount: Int, val records: List<AiCoachHistoryRecord>) {
    val fingerprint: String get() = aiCoachHash(JSONArray(listOf(profile.json(),
        JSONArray(records.map { JSONArray(listOf(it.evidence.snapshot.sessionId, it.sourceRevision)) }), linkedCount)).toString())
}

internal class AiCoachHistoryReader(private val context: Context) {
    fun read(accountId: String): AiCoachHistory {
        val store = AiCoachStore(context)
        val profile = store.profile(accountId) ?: error("Save a workout profile first")
        val linked = store.sessionIds(accountId)
        val analyses = SetAnalysisStore(context)
        val records = FinishedWorkoutStore(context).evidenceForSessions(linked).map { saved ->
            val analysis = analyses.readForSession(saved.snapshot.sessionId, allowLegacyScan = false, savedSnapshot = saved.snapshot)
            // A late result can precede durable record reconciliation. Use the existing reducer,
            // which preserves manual entries, without mutating the original workout here.
            val corrected = analysis.rows.values.fold(saved.snapshot) { snapshot, row ->
                WorkoutSessionReducer.confirmReps(snapshot, row.exerciseId, row.setIndex, row.confirmedReps, row.setId, row.attemptId)
            }
            val revision = aiCoachHash(JSONArray(listOf(WorkoutSnapshotCodec.encode(corrected), saved.title,
                saved.minutes, saved.analysisRevision, analysis.revision)).toString())
            AiCoachHistoryRecord(saved.copy(snapshot = corrected), analysis, revision)
        }
        return AiCoachHistory(profile, linked.size, records)
    }
}

internal fun aiCoachHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun WorkoutCondition.json(): JSONObject = JSONObject().put("exercise_id", exerciseId)
    .put("equipment", equipment).put("grip", grip).put("family", family)

internal fun WorkoutConditionComparison.json(): JSONObject = condition.json()
    .put("previous_sets", previous?.sets ?: JSONObject.NULL).put("current_sets", current.sets)
    .put("previous_reps", previous?.reps ?: JSONObject.NULL).put("current_reps", current.reps ?: JSONObject.NULL)
    .put("previous_volume_kg_reps", previous?.volumeKgReps ?: JSONObject.NULL)
    .put("current_volume_kg_reps", current.volumeKgReps ?: JSONObject.NULL)
    .put("volume_change_kg_reps", volumeChange ?: JSONObject.NULL)
    .put("volume_change_percent", volumeChangePercent ?: JSONObject.NULL)
    .put("set_count_changed", setCountChanged).put("condition_status", if (previous == null) "NEW_CONDITION" else "MATCHING_FIELDS")
    .put("missing_condition_fields", JSONArray(condition.missingFields))
    .put("same_weight_reps", JSONArray(sameWeight.map { row -> JSONObject().put("weight_kg", row.weightKg)
        .put("previous_sets", row.previousSets).put("current_sets", row.currentSets)
        .put("previous_reps", row.previousReps).put("current_reps", row.currentReps)
        .put("same_set_count", row.comparableSetCount)
        .put("rep_change_same_set_count", if (row.comparableSetCount) row.currentReps - row.previousReps else JSONObject.NULL) }))
    .put("same_reps_weights", JSONArray(sameReps.map { row -> JSONObject().put("reps", row.reps)
        .put("previous_weights_kg", JSONArray(row.previousWeightsKg)).put("current_weights_kg", JSONArray(row.currentWeightsKg)) }))

internal fun aiCoachComparison(current: AiCoachHistoryRecord, previous: AiCoachHistoryRecord?, history: AiCoachHistory): JSONObject {
    val snapshot = current.evidence.snapshot
    val prior = previous?.evidence?.snapshot
    val ambiguous = history.records.any { it.evidence.snapshot.let { other ->
        other.sessionId != snapshot.sessionId && other.routineId == snapshot.routineId && other.startedAtMs == snapshot.startedAtMs
    } } || (prior == null && history.records.any { it.evidence.snapshot.routineId == snapshot.routineId &&
        it.evidence.snapshot.startedAtMs < snapshot.startedAtMs })
    val before = prior?.let(WorkoutComparison::normalGroups).orEmpty()
    val after = WorkoutComparison.normalGroups(snapshot)
    return JSONObject().put("current_evidence_id", "session:${snapshot.sessionId}")
        .put("baseline_evidence_id", prior?.let { "session:${it.sessionId}" } ?: JSONObject.NULL)
        .put("baseline_status", when { ambiguous -> "AMBIGUOUS_TIMESTAMP"; prior == null -> "FIRST_ROUTINE_RECORD"; else -> "PREVIOUS_COMPLETED_ROUTINE" })
        .put("exercises", JSONArray(WorkoutComparison.compare(snapshot, prior).map { it.json() }))
        .put("added_conditions", JSONArray((after.keys - before.keys).map { it.json() }))
        .put("removed_conditions", JSONArray((before.keys - after.keys).map { it.json() }))
        .put("basis", "동일 routineId의 직전 완료 기록. 일반 세트만, 동일 운동·기구·그립·변형 필드별 계산. 누락 조건은 미확인이며 훈련량 변화는 근육 성장률이 아님.")
}
