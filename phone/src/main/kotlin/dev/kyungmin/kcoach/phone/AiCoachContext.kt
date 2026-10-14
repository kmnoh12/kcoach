package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.PlannedSetSnapshot
import org.json.JSONArray
import org.json.JSONObject

internal data class AiCoachContext(val profileId: String, val json: String, val evidenceIds: Set<String>)
internal data class AiCoachAnswer(val text: String, val citations: List<String>) {
    companion object {
        fun parse(text: String, context: AiCoachContext): AiCoachAnswer {
            val json = JSONObject(text)
            require(json.keys().asSequence().toSet() == setOf("answer", "citations"))
            val answer = json.opt("answer") as? String ?: error("Invalid answer text")
            require(answer.isNotBlank() && answer.length <= 12_000)
            val refs = json.getJSONArray("citations")
            require(refs.length() <= context.evidenceIds.size)
            val citations = (0 until refs.length()).map { refs.get(it) as? String ?: error("Invalid citation") }
            require(citations.distinct().size == citations.size && citations.all { it in context.evidenceIds })
            return AiCoachAnswer(answer, citations)
        }
    }
}

internal class AiCoachContextBuilder(private val context: Context) {
    fun build(accountId: String, question: String): AiCoachContext {
        val store = AiCoachStore(context)
        val profile = store.profile(accountId) ?: error("Save a workout profile first")
        val linked = store.sessionIds(accountId)
        val recent = FinishedWorkoutStore(context).recentEvidence(100).filter { it.snapshot.sessionId in linked }
        val relevant = recent.filter { record -> record.title.isNotBlank() && question.contains(record.title) ||
            record.snapshot.plannedSets.any { it.exerciseName.isNotBlank() && question.contains(it.exerciseName) } }
        val selected = (relevant + recent).distinctBy { it.snapshot.sessionId }.take(20)
        val ids = linkedSetOf("profile:${profile.id}:${profile.revision}")
        val rows = JSONArray()
        var truncated = selected.size < recent.size
        var size = profile.json().toString().length
        val included = mutableListOf<FinishedWorkoutStore.Evidence>()
        selected.forEach { record ->
            val snapshot = record.snapshot
            val evidenceId = "session:${snapshot.sessionId}"
            val done = snapshot.plannedSets.filter { it.completed }
            val sets = done.take(60)
            val analysis = SetAnalysisStore(context).readForSession(snapshot.sessionId, savedSnapshot = snapshot)
            val sensor = analysis.rows.values.take(60).map { row ->
                JSONObject().put("set_id", row.setId).put("exercise_id", row.exerciseId).put("set_index", row.setIndex)
                    .put("watch_estimate", row.watchReps ?: JSONObject.NULL).put("phone_estimate", row.phoneReps ?: JSONObject.NULL)
                    .put("confirmed_count", row.confirmedReps ?: JSONObject.NULL)
                    .put("capture_id", row.captureId).put("signal_quality", row.signalQuality ?: JSONObject.NULL)
                    .put("tempo_consistency", row.tempoConsistency ?: JSONObject.NULL)
                    .put("wrist_motion_speed", row.wristMotionSpeed ?: JSONObject.NULL)
                    .put("wrist_motion_range", row.wristMotionRange ?: JSONObject.NULL)
                    .put("tracking_status", row.trackingStatus)
                    .put("observed_wrist_motion", row.motionMetrics?.let { motion ->
                        JSONObject().put("source", motion.source).put("observed_cycles", motion.reps.size)
                            .put("average_cycle_ms", motion.averageCycleMs)
                            .put("average_departure_ms", motion.averageDepartureMs).put("average_return_ms", motion.averageReturnMs)
                            .put("average_concentric_ms", motion.averageConcentricMs ?: JSONObject.NULL)
                            .put("average_eccentric_ms", motion.averageEccentricMs ?: JSONObject.NULL)
                            .put("average_pause_ms", motion.averagePauseMs ?: JSONObject.NULL)
                            .put("average_wrist_angular_speed_rad_s", motion.averageAngularSpeedRadS)
                            .put("tempo_coefficient_of_variation", motion.tempoCoefficientOfVariation ?: JSONObject.NULL)
                            .put("late_cycle_change_ratio", motion.lateCycleChangeRatio ?: JSONObject.NULL)
                            .put("late_wrist_angular_travel_change_ratio", motion.lateWristAngularTravelChangeRatio ?: JSONObject.NULL)
                    } ?: JSONObject.NULL)
            }
            val row = JSONObject().put("evidence_id", evidenceId).put("session_id", snapshot.sessionId)
                .put("routine_id", snapshot.routineId).put("title", record.title).put("started_at_ms", snapshot.startedAtMs)
                .put("minutes", record.minutes).put("analysis_revision", analysis.revision)
                .put("snapshot_analysis_revision", record.analysisRevision)
                .put("record_reconciliation_pending", record.analysisRevision != analysis.revision)
                .put("completed_sets", JSONArray(sets.map(::setJson))).put("sensor_summary", JSONArray(sensor))
                .put("planned_capture_states", JSONObject(snapshot.captureStates.mapValues { it.value.name }))
                .put("sets_omitted", done.size - sets.size)
            if (size + row.toString().length > 44_000) { truncated = true; return@forEach }
            rows.put(row); ids.add(evidenceId); included.add(record); size += row.toString().length
        }
        val comparisons = JSONArray()
        included.take(8).forEach { current ->
            val previous = recent.filter { it.snapshot.routineId == current.snapshot.routineId &&
                it.snapshot.startedAtMs < current.snapshot.startedAtMs }.maxByOrNull { it.snapshot.startedAtMs }
            if (previous != null && included.any { it.snapshot.sessionId == previous.snapshot.sessionId }) comparisons.put(compare(current, previous))
            else if (previous != null) truncated = true
        }
        val root = JSONObject().put("profile", profile.json()).put("records", rows).put("comparisons", comparisons)
            .put("evidence_index", JSONArray(ids.toList())).put("records_linked", linked.size)
            .put("context_is_partial", truncated || linked.size > recent.size)
            .put("coverage_note", "최근 100회 중 연결된 기록에서 질문 관련 운동과 최근 기록 최대 20회. 손목 신호는 자세 점수가 아님. 원본 센서 전체가 아닌 저장된 분석 요약.")
        val encoded = root.toString()
        require(encoded.length <= 60_000) { "Context exceeds supported request size" }
        return AiCoachContext(profile.id, encoded, ids)
    }

    private fun setJson(set: PlannedSetSnapshot) = JSONObject().put("set_id", set.setId).put("exercise_id", set.exerciseId)
        .put("exercise_name", set.exerciseName).put("set_index", set.setIndex).put("type", set.setType)
        .put("weight_kg", set.weightKg ?: JSONObject.NULL).put("performed_reps", set.performedReps ?: JSONObject.NULL)
        .put("rep_source", set.repSource?.name ?: JSONObject.NULL).put("rpe", set.rpe ?: JSONObject.NULL)
        .put("equipment", set.equipment).put("grip", set.gripType).put("family", set.family)

    private fun compare(current: FinishedWorkoutStore.Evidence, previous: FinishedWorkoutStore.Evidence): JSONObject {
        fun groups(record: FinishedWorkoutStore.Evidence) = record.snapshot.plannedSets
            .filter { it.completed && it.setType == "NORMAL" }
            .groupBy { listOf(it.exerciseId, it.equipment, it.gripType, it.family) }
        val before = groups(previous)
        val after = groups(current)
        val rows = after.map { (condition, sets) ->
            val prior = before[condition]
            fun reps(values: List<PlannedSetSnapshot>?) = values?.takeIf { it.all { set -> set.performedReps != null } }
                ?.sumOf { it.performedReps!! }
            fun volume(values: List<PlannedSetSnapshot>?) = values?.takeIf { it.all { set -> set.performedReps != null && set.weightKg != null } }
                ?.sumOf { it.performedReps!! * it.weightKg!! }
            val oldVolume = volume(prior)
            val newVolume = volume(sets)
            JSONObject().put("exercise_id", condition[0]).put("equipment", condition[1]).put("grip", condition[2]).put("family", condition[3])
                .put("previous_sets", prior?.size ?: JSONObject.NULL).put("current_sets", sets.size)
                .put("previous_reps", reps(prior) ?: JSONObject.NULL).put("current_reps", reps(sets) ?: JSONObject.NULL)
                .put("previous_volume_kg_reps", oldVolume ?: JSONObject.NULL).put("current_volume_kg_reps", newVolume ?: JSONObject.NULL)
                .put("volume_change_kg_reps", if (oldVolume != null && newVolume != null) newVolume - oldVolume else JSONObject.NULL)
                .put("volume_change_percent", if (oldVolume != null && oldVolume > 0 && newVolume != null) (newVolume - oldVolume) / oldVolume * 100 else JSONObject.NULL)
                .put("set_count_changed", prior != null && prior.size != sets.size)
        }
        return JSONObject().put("current_evidence_id", "session:${current.snapshot.sessionId}")
            .put("baseline_evidence_id", "session:${previous.snapshot.sessionId}").put("exercises", JSONArray(rows))
            .put("removed_conditions", JSONArray((before.keys - after.keys).map { JSONArray(it) }))
            .put("basis", "동일 routineId의 제공 범위 내 직전 완료 기록. 일반 세트만, 동일 운동·기구·그립·변형 조건별 계산. 훈련량 변화는 근육 성장률이 아님.")
    }
}
