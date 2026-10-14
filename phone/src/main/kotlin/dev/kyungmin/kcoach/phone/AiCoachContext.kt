package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.PlannedSetSnapshot
import org.json.JSONArray
import org.json.JSONObject

internal data class AiCoachContext(val profileId: String, val json: String, val evidenceIds: Set<String>)
internal data class AiCoachAnswer(val text: String, val citations: List<String>, val memoryProposals: JSONArray = JSONArray()) {
    companion object {
        fun parse(text: String, context: AiCoachContext): AiCoachAnswer {
            val json = JSONObject(text)
            require(json.keys().asSequence().toSet() in setOf(setOf("answer", "citations"), setOf("answer", "citations", "memory_proposals")))
            val answer = json.opt("answer") as? String ?: error("Invalid answer text")
            require(answer.isNotBlank() && answer.length <= 12_000)
            val refs = json.getJSONArray("citations")
            require(refs.length() <= context.evidenceIds.size)
            val citations = (0 until refs.length()).map { refs.get(it) as? String ?: error("Invalid citation") }
            require(citations.distinct().size == citations.size && citations.all { it in context.evidenceIds })
            val proposals = json.optJSONArray("memory_proposals") ?: JSONArray()
            require(proposals.length() <= 3)
            for (i in 0 until proposals.length()) {
                val row = proposals.getJSONObject(i)
                require(row.keys().asSequence().toSet() == setOf("text", "citations"))
                require(row.getString("text").isNotBlank() && row.getString("text").length <= 800)
                val refs = row.getJSONArray("citations")
                require(refs.length() in 1..20 && (0 until refs.length()).all { refs.get(it) is String && refs.getString(it) in context.evidenceIds })
            }
            return AiCoachAnswer(answer, citations, proposals)
        }
    }
}

internal class AiCoachContextBuilder(private val context: Context) {
    fun build(accountId: String, question: String): AiCoachContext {
        require(question.isNotBlank() && question.length <= 4_000)
        val history = AiCoachHistoryReader(context).read(accountId)
        val records = history.records
        val summary = AiCoachSummaryStore(context).refresh(accountId, history)
        val profile = history.profile
        val ids = linkedSetOf("profile:${profile.id}:${profile.revision}", summary.getString("evidence_id"))
        val revisions = JSONObject()
        val rows = JSONArray()
        val comparisons = JSONArray()
        val root = JSONObject().put("profile", profile.json()).put("personal_summary", summary)
            .put("model_interpretations", AiCoachInterpretationStore(context).current(accountId, history))
            .put("records", rows).put("comparisons", comparisons)
            .put("evidence_revisions", revisions).put("records_linked", history.linkedCount)
            .put("records_available", records.size)
            .put("count_sources_note", "watch_estimate/phone_estimate/phone_record_proposal은 알고리즘 추정·제안. completed_sets의 rep_source=MANUAL만 사용자 입력 횟수. observed_wrist_motion은 손목 관측값이며 관절 ROM·자세 점수가 아님.")
            .put("coverage_note", "연결된 전체 완료 기록에서 직전 동일 루틴 기준을 찾음. 개인 요약은 전체 기록의 계산 사실이며 요청 원본은 질문 관련·최근 기록의 일부. 최신 분석을 읽되 수동 횟수를 우선함.")
        val trends = summary.getJSONArray("recent_condition_trends")
        for (i in 0 until trends.length()) {
            val trend = trends.getJSONObject(i)
            listOf("current_evidence_id", "baseline_evidence_id").forEach { field ->
                if (!trend.isNull(field)) {
                    val id = trend.getString(field)
                    records.firstOrNull { "session:${it.evidence.snapshot.sessionId}" == id }?.let {
                        ids.add(id); revisions.put(id, it.sourceRevision)
                    }
                }
            }
        }
        fun withinBudget(): Boolean {
            root.put("evidence_index", JSONArray(ids.toList()))
            return root.toString().length <= 57_000
        }
        val relevant = records.filter { record -> record.evidence.title.isNotBlank() && question.contains(record.evidence.title) ||
            record.evidence.snapshot.plannedSets.any { it.exerciseName.isNotBlank() && question.contains(it.exerciseName) } }
        val selected = (relevant + records).distinctBy { it.evidence.snapshot.sessionId }.take(20)
        val snapshots = records.map { it.evidence.snapshot }
        val included = linkedMapOf<String, AiCoachHistoryRecord>()
        var partial = history.linkedCount > records.size
        var recordBytes = 0
        fun include(record: AiCoachHistoryRecord): Boolean {
            val id = record.evidence.snapshot.sessionId
            if (id in included) return true
            val row = recordJson(record)
            val length = row.toString().length
            if (included.size >= 28 || recordBytes + length > 35_000) { partial = true; return false }
            rows.put(row)
            val evidenceId = "session:$id"
            val alreadyKnown = evidenceId in ids
            ids.add(evidenceId); revisions.put(evidenceId, record.sourceRevision)
            if (!withinBudget()) {
                rows.remove(rows.length() - 1)
                if (!alreadyKnown) { ids.remove(evidenceId); revisions.remove(evidenceId) }
                partial = true; return false
            }
            included[id] = record
            if (record.evidence.snapshot.plannedSets.count { it.completed } > 60 || record.analysis.rows.size > 60) partial = true
            recordBytes += length
            return true
        }
        var comparisonBytes = 0
        selected.forEach { current ->
            val snapshot = current.evidence.snapshot
            val previousId = dev.kyungmin.kcoach.core.WorkoutComparison.previous(snapshot, snapshots)?.sessionId
            val previous = records.firstOrNull { it.evidence.snapshot.sessionId == previousId }
            if (include(current) && (previous == null || include(previous)) && comparisons.length() < 8) {
                val comparison = aiCoachComparison(current, previous, history)
                val length = comparison.toString().length
                if (comparisonBytes + length <= 9_000) {
                    comparisons.put(comparison)
                    if (withinBudget()) comparisonBytes += length
                    else { comparisons.remove(comparisons.length() - 1); partial = true }
                }
                else partial = true
            }
        }
        root.put("evidence_index", JSONArray(ids.toList())).put("context_is_partial", partial || included.size < records.size)
        val encoded = root.toString()
        require(encoded.length <= 60_000) { "Context exceeds supported request size" }
        return AiCoachContext(profile.id, encoded, ids)
    }

    internal fun recordJson(record: AiCoachHistoryRecord): JSONObject {
        val saved = record.evidence
        val snapshot = saved.snapshot
        val done = snapshot.plannedSets.filter { it.completed }
        val sets = done.take(60)
        val sensor = record.analysis.rows.values.take(60).map { row ->
            JSONObject().put("set_id", row.setId).put("attempt_id", row.attemptId)
                .put("exercise_id", row.exerciseId).put("set_index", row.setIndex)
                .put("watch_estimate", row.watchReps ?: JSONObject.NULL).put("phone_estimate", row.phoneReps ?: JSONObject.NULL)
                .put("phone_record_proposal", row.confirmedReps ?: JSONObject.NULL)
                .put("capture_id", row.captureId).put("signal_quality", row.signalQuality ?: JSONObject.NULL)
                .put("tempo_consistency", row.tempoConsistency ?: JSONObject.NULL).put("tracking_status", row.trackingStatus)
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
        return JSONObject().put("evidence_id", "session:${snapshot.sessionId}").put("session_id", snapshot.sessionId)
            .put("source_revision", record.sourceRevision).put("routine_id", snapshot.routineId).put("title", saved.title)
            .put("started_at_ms", snapshot.startedAtMs).put("minutes", saved.minutes).put("analysis_revision", record.analysis.revision)
            .put("snapshot_analysis_revision", saved.analysisRevision)
            .put("record_reconciliation_pending", saved.analysisRevision != record.analysis.revision)
            .put("completed_sets", JSONArray(sets.map(::setJson))).put("sensor_summary", JSONArray(sensor))
            .put("sensor_rows_omitted", record.analysis.rows.size - sensor.size)
            .put("capture_status", WorkoutCaptureStatus(context).read(snapshot))
            .put("planned_capture_states", JSONObject(snapshot.captureStates.mapValues { it.value.name }))
            .put("sets_omitted", done.size - sets.size)
    }

    private fun setJson(set: PlannedSetSnapshot) = JSONObject().put("set_id", set.setId).put("exercise_id", set.exerciseId)
        .put("exercise_name", set.exerciseName).put("set_index", set.setIndex).put("type", set.setType)
        .put("weight_kg", set.weightKg ?: JSONObject.NULL).put("performed_reps", set.performedReps ?: JSONObject.NULL)
        .put("rep_source", set.repSource?.name ?: JSONObject.NULL).put("rpe", set.rpe ?: JSONObject.NULL)
        .put("equipment", set.equipment).put("grip", set.gripType).put("family", set.family)
}
