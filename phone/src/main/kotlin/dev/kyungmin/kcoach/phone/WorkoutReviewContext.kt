package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.pow

internal data class ReviewMetric(val id: String, val exerciseId: String, val setId: String?, val values: JSONObject)
internal data class WorkoutReviewContext(val profileId: String, val sessionId: String, val json: String,
    val fingerprint: String, val evidenceIds: Set<String>, val exerciseNames: Map<String, String>,
    val sets: Map<String, String>, val metrics: Map<String, ReviewMetric>)

internal class WorkoutReviewContextBuilder(private val context: Context) {
    fun build(accountId: String, sessionId: String): WorkoutReviewContext {
        val history = AiCoachHistoryReader(context).read(accountId)
        val current = history.records.singleOrNull { it.evidence.snapshot.sessionId == sessionId }
            ?: error("Link this completed session to the current profile first")
        val snapshot = current.evidence.snapshot
        val completed = snapshot.plannedSets.filter { it.completed }
        require(completed.isNotEmpty() && completed.size <= 200) { "Review supports up to 200 completed sets" }
        val baselineId = WorkoutComparison.previous(snapshot, history.records.map { it.evidence.snapshot })?.sessionId
        val previous = history.records.firstOrNull { it.evidence.snapshot.sessionId == baselineId }
        val evidence = linkedSetOf("profile:${history.profile.id}:${history.profile.revision}", "session:$sessionId")
        previous?.let { evidence.add("session:${it.evidence.snapshot.sessionId}") }
        val names = completed.associate { it.exerciseId to it.exerciseName.ifBlank { it.exerciseId } }
        val sets = linkedMapOf<String, String>()
        val metrics = linkedMapOf<String, ReviewMetric>()
        val perSet = JSONArray()
        val perExercise = JSONArray()
        fun addMetric(id: String, exercise: String, set: String?, values: JSONObject, array: JSONArray) {
            metrics[id] = ReviewMetric(id, exercise, set, values)
            array.put(JSONObject(values.toString()).put("metric_id", id).put("exercise_id", exercise))
        }
        completed.forEachIndexed { index, set ->
            val id = set.setId.ifBlank { "legacy-$index" }
            require(sets.put(id, set.exerciseId) == null)
            val ref = "set:$sessionId:$id"
            evidence.add(ref)
            val values = JSONObject().put("set_id", id).put("original_set_id", set.setId.ifBlank { null } ?: JSONObject.NULL)
                .put("set_index", set.setIndex).put("type", set.setType).put("rep_source", set.repSource?.name ?: JSONObject.NULL)
                .put("weight_kg", set.weightKg ?: JSONObject.NULL).put("performed_reps", set.performedReps ?: JSONObject.NULL)
                .put("rpe", set.rpe ?: JSONObject.NULL).put("evidence_id", ref)
            val analysis = current.analysis.rows.values.singleOrNull { row ->
                if (set.setId.isNotBlank()) row.setId == set.setId && row.attemptId == set.attemptId
                else row.exerciseId == set.exerciseId && row.setIndex == set.setIndex
            }
            values.put("sensor_analysis_available", analysis != null)
            if (analysis != null) {
                values.put("watch_estimate", analysis.watchReps ?: JSONObject.NULL)
                    .put("phone_estimate", analysis.phoneReps ?: JSONObject.NULL)
                    .put("phone_record_proposal", analysis.confirmedReps ?: JSONObject.NULL)
                    .put("signal_quality", analysis.signalQuality ?: JSONObject.NULL)
                    .put("capture_id", analysis.captureId).put("tracking_status", analysis.trackingStatus)
                analysis.motionMetrics?.let { motion ->
                    values.put("average_cycle_seconds", motion.averageCycleMs / 1000)
                        .put("average_departure_seconds", motion.averageDepartureMs / 1000)
                        .put("average_return_seconds", motion.averageReturnMs / 1000)
                        .put("average_concentric_seconds", motion.averageConcentricMs?.div(1000) ?: JSONObject.NULL)
                        .put("average_eccentric_seconds", motion.averageEccentricMs?.div(1000) ?: JSONObject.NULL)
                        .put("average_pause_seconds", motion.averagePauseMs?.div(1000) ?: JSONObject.NULL)
                        .put("average_wrist_angular_speed_rad_s", motion.averageAngularSpeedRadS)
                        .put("late_cycle_change_percent", motion.lateCycleChangeRatio?.times(100) ?: JSONObject.NULL)
                        .put("late_wrist_travel_change_percent", motion.lateWristAngularTravelChangeRatio?.times(100) ?: JSONObject.NULL)
                        .put("observed_reps", motion.reps.size)
                    // First/last observations, with explicit omitted counts. Originals remain in the analysis store.
                    val sample = if (motion.reps.size <= 8) motion.reps else motion.reps.take(4) + motion.reps.takeLast(4)
                    values.put("rep_samples", JSONArray(sample.map { rep -> JSONObject()
                        .put("rep_index", motion.reps.indexOf(rep)).put("cycle_seconds", rep.durationMs / 1000)
                        .put("departure_seconds", rep.departureMs / 1000).put("return_seconds", rep.returnMs / 1000)
                        .put("concentric_seconds", rep.concentricMs?.div(1000) ?: JSONObject.NULL)
                        .put("eccentric_seconds", rep.eccentricMs?.div(1000) ?: JSONObject.NULL)
                        .put("pause_seconds", rep.pauseMs?.div(1000) ?: JSONObject.NULL)
                        .put("wrist_angular_travel_rad", rep.angularTravelRad) }))
                        .put("rep_samples_omitted", motion.reps.size - sample.size)
                }
            }
            addMetric("metric:set:$id", set.exerciseId, id, values, perSet)
        }
        completed.groupBy { it.exerciseId }.forEach { (exercise, exerciseSets) ->
            val normal = WorkoutComparison.totals(exerciseSets.filter { it.setType == "NORMAL" })
            val values = JSONObject().put("exercise_name", names.getValue(exercise)).put("completed_sets", exerciseSets.size)
                .put("normal_sets", normal.sets).put("normal_reps", normal.reps ?: JSONObject.NULL)
                .put("normal_volume_kg_reps", normal.volumeKgReps ?: JSONObject.NULL)
                .put("warmup_sets", exerciseSets.count { it.setType == "WARMUP" })
            addMetric("metric:exercise:$exercise", exercise, null, values, perExercise)
        }
        val comparison = aiCoachComparison(current, previous, history)
        val comparisons = comparison.getJSONArray("exercises")
        for (i in 0 until comparisons.length()) {
            val row = comparisons.getJSONObject(i)
            val id = "metric:comparison:$i"
            row.put("metric_id", id)
            metrics[id] = ReviewMetric(id, row.getString("exercise_id"), null, row)
        }
        val root = JSONObject().put("task", "workout_review").put("schema_version", 2)
            .put("profile", history.profile.json()).put("session", JSONObject().put("session_id", sessionId)
                .put("routine_id", snapshot.routineId).put("title", current.evidence.title)
                .put("started_at_ms", snapshot.startedAtMs).put("duration_minutes", current.evidence.minutes)
                .put("source_revision", current.sourceRevision))
            .put("baseline_session", previous?.let { JSONObject().put("session_id", it.evidence.snapshot.sessionId)
                .put("started_at_ms", it.evidence.snapshot.startedAtMs).put("source_revision", it.sourceRevision) } ?: JSONObject.NULL)
            .put("comparison", comparison).put("per_exercise_metrics", perExercise).put("per_set_metrics", perSet)
            .put("sensor_summary", JSONObject().put("analyzed_sets", perSet.countObjects { it.optBoolean("sensor_analysis_available") })
                .put("completed_sets", completed.size).put("planned_capture_states", JSONObject(snapshot.captureStates.mapValues { it.value.name }))
                .put("coverage", "분석된 수는 완료 세트와 일치를 확인한 수이며 원본 개수가 아님. 분석 미도착은 미수신·미측정·분석 중 어느 상태인지 아직 미확인."))
            .put("optional_camera_metrics", JSONObject.NULL).put("evidence_index", JSONArray(evidence.toList()))
            .put("measurement_note", "수동 수행 횟수 우선. 워치·폰의 횟수는 추정이며 손목 지표는 관절 ROM·자세·근육 활성·RIR이 아님. 카메라 관측 없음.")
        val encoded = root.toString()
        require(encoded.length <= 60_000) { "Review evidence exceeds supported input size" }
        return WorkoutReviewContext(history.profile.id, sessionId, encoded, aiCoachHash(encoded), evidence, names, sets, metrics)
    }
}

private fun JSONArray.countObjects(test: (JSONObject) -> Boolean) = (0 until length()).count { test(getJSONObject(it)) }

internal data class WorkoutReviewAnswer(val json: String) {
    val value: JSONObject get() = JSONObject(json)
    companion object {
        fun parse(text: String, context: WorkoutReviewContext): WorkoutReviewAnswer {
            require(text.length <= 30_000)
            val root = JSONObject(text)
            root.exactKeys("summary", "overview", "findings", "exercise_analyses", "next_targets", "coverage", "limitations", "memory_proposals")
            val allNumbers = numberValues(JSONObject(context.json))
            fun checkedText(value: String, limit: Int, numbers: Set<Double>? = allNumbers): String {
                require(value.isNotBlank() && value.length <= limit)
                numbers?.let { known ->
                    Regex("(?<![A-Za-z0-9])[-+]?\\d+(?:\\.\\d+)?").findAll(value).forEach { match ->
                        val number = match.value.toDouble()
                        val decimals = match.value.substringAfter('.', "").length
                        val rounding = if (decimals == 0) 0.000001 else 0.5 * 10.0.pow(-decimals) + 0.000001
                        require(known.any { abs(it - number) <= rounding }) { "Review contains an unsupported factual number" }
                    }
                }
                return value
            }
            fun evidence(json: JSONObject, required: Boolean = true, exercise: String? = null) {
                val refs = json.stringList("evidence_ids", context.evidenceIds.size, 512)
                require(!required || refs.isNotEmpty())
                require(refs.distinct().size == refs.size && refs.all { it in context.evidenceIds })
                if (exercise != null) refs.forEach { ref ->
                    val prefix = "set:${context.sessionId}:"
                    if (ref.startsWith(prefix)) require(context.sets[ref.removePrefix(prefix)] == exercise)
                }
            }
            fun refs(json: JSONObject, exercise: String): Set<Double> {
                val ids = json.stringList("metric_refs", context.metrics.size, 512)
                require(ids.distinct().size == ids.size)
                val rows = ids.map { context.metrics[it]?.takeIf { metric -> metric.exerciseId == exercise } ?: error("Unknown or foreign metric") }
                return rows.flatMap { numberValues(it.values) }.toSet()
            }
            checkedText(root.getString("summary"), 240); checkedText(root.getString("overview"), 1000)
            val findings = root.getJSONArray("findings"); require(findings.length() in 1..3)
            for (i in 0 until findings.length()) findings.getJSONObject(i).also {
                it.exactKeys("text", "evidence_ids"); evidence(it); checkedText(it.getString("text"), 600)
            }
            val analyses = root.getJSONArray("exercise_analyses")
            require(analyses.length() == context.exerciseNames.size)
            val exercises = mutableSetOf<String>()
            for (i in 0 until analyses.length()) analyses.getJSONObject(i).also { row ->
                row.exactKeys("exercise_id", "status", "headline", "metric_refs", "observations", "highlighted_set_ids", "limitations")
                val id = row.getString("exercise_id")
                require(id in context.exerciseNames && exercises.add(id))
                require(row.getString("status") in setOf("analyzed", "partial", "insufficient_data"))
                checkedText(row.getString("headline"), 240, refs(row, id))
                row.stringList("highlighted_set_ids", context.sets.size, 512).also { highlighted ->
                    require(highlighted.distinct().size == highlighted.size && highlighted.all { context.sets[it] == id })
                }
                row.stringList("limitations", 6, 300)
                val observations = row.getJSONArray("observations"); require(observations.length() in 1..3)
                for (j in 0 until observations.length()) observations.getJSONObject(j).also {
                    it.exactKeys("kind", "text", "metric_refs", "evidence_ids")
                    require(it.getString("kind") in setOf("observed", "interpretation")); evidence(it, exercise = id)
                    checkedText(it.getString("text"), 600, refs(it, id))
                }
            }
            val targets = root.getJSONArray("next_targets"); require(targets.length() <= 2)
            for (i in 0 until targets.length()) targets.getJSONObject(i).also {
                it.exactKeys("exercise_id", "target", "reason", "evidence_ids")
                require(it.getString("exercise_id") in context.exerciseNames); evidence(it, exercise = it.getString("exercise_id"))
                checkedText(it.getString("target"), 300, null) // Explicit proposed numbers are not historical facts.
                checkedText(it.getString("reason"), 600)
            }
            root.getJSONObject("coverage").also { it.exactKeys("evidence_ids", "limitations"); evidence(it); it.stringList("limitations", 8, 300) }
            root.stringList("limitations", 8, 300)
            require(root.getJSONArray("memory_proposals").length() == 0) { "Personal memory proposals are not enabled for reviews" }
            return WorkoutReviewAnswer(root.toString())
        }
    }
}

private fun numberValues(value: Any): Set<Double> = when (value) {
    is JSONObject -> value.keys().asSequence().flatMap { numberValues(value.get(it)).asSequence() }.toSet()
    is JSONArray -> (0 until value.length()).flatMap { numberValues(value.get(it)) }.toSet()
    is Number -> setOf(value.toDouble()).filter { it.isFinite() }.toSet()
    else -> emptySet()
}
internal fun JSONObject.exactKeys(vararg keys: String) { require(this.keys().asSequence().toSet() == keys.toSet()) }
internal fun JSONObject.stringList(key: String, count: Int, length: Int): List<String> {
    val array = getJSONArray(key); require(array.length() <= count)
    return (0 until array.length()).map { i -> (array.get(i) as? String ?: error("Expected string"))
        .also { require(it.isNotBlank() && it.length <= length) } }
}
