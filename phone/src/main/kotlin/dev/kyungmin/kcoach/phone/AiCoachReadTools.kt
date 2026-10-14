package dev.kyungmin.kcoach.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Request-scoped, immutable view of records explicitly linked to this account. No path or mutation tools. */
internal class AiCoachReadTools(private val app: Context, private val history: AiCoachHistory) {
    val evidenceIds = linkedSetOf<String>()
    val outputs = JSONArray()
    fun execute(name: String, args: JSONObject): JSONObject {
        val result = when (name) {
            "read_profile" -> {
                require(args.length() == 0)
                val id = "profile:${history.profile.id}:${history.profile.revision}"
                JSONObject().put("evidence_id", id).put("profile", history.profile.json())
                    .also { evidenceIds.add(id) }
            }
            "read_workouts" -> {
                require(args.keys().asSequence().toSet() == setOf("routine_id", "exercise_id", "from_ms", "to_ms", "limit", "offset"))
                val limitValue = whole(args, "limit"); val offsetValue = whole(args, "offset")
                require(limitValue in 1..10 && offsetValue in 0..10_000)
                val limit = limitValue.toInt(); val offset = offsetValue.toInt()
                val from = if (args.isNull("from_ms")) null else whole(args, "from_ms"); val to = if (args.isNull("to_ms")) null else whole(args, "to_ms")
                require(from == null || from.toLong() >= 0)
                require(to == null || to.toLong() >= (from?.toLong() ?: 0))
                val routine = nullableString(args, "routine_id"); val exercise = nullableString(args, "exercise_id")
                val matching = history.records.filter { record -> record.evidence.snapshot.let { s ->
                    (routine == null || s.routineId == routine) && (exercise == null || s.plannedSets.any { it.exerciseId == exercise }) &&
                        (from == null || s.startedAtMs >= from.toLong()) && (to == null || s.startedAtMs <= to.toLong())
                } }
                val rows = JSONArray(); var length = 0
                matching.drop(offset).take(limit).forEach { record ->
                    val row = AiCoachContextBuilder(app).recordJson(record)
                    if (length + row.toString().length <= 24_000) {
                        rows.put(row); length += row.toString().length
                        evidenceIds.add(row.getString("evidence_id"))
                    }
                }
                JSONObject().put("records", rows).put("records_omitted_due_to_size", matching.drop(offset).take(limit).size - rows.length()).put("total_matching", matching.size)
                    .put("next_offset", if (offset + rows.length() < matching.size) offset + rows.length() else JSONObject.NULL)
            }
            "read_sensor_summary" -> {
                require(args.keys().asSequence().toSet() == setOf("session_id", "set_id"))
                val record = history.records.singleOrNull { it.evidence.snapshot.sessionId == args.getString("session_id") }
                    ?: error("Record is not linked to this profile")
                val setId = nullableString(args, "set_id")
                require(setId == null || record.evidence.snapshot.plannedSets.any { it.setId == setId && it.completed })
                val row = AiCoachContextBuilder(app).recordJson(record)
                val sensor = row.getJSONArray("sensor_summary")
                val selected = JSONArray((0 until sensor.length()).map { sensor.getJSONObject(it) }.filter { setId == null || it.getString("set_id") == setId })
                JSONObject().put("evidence_id", row.getString("evidence_id")).put("source_revision", record.sourceRevision)
                    .put("sensor_summary", selected).put("sensor_rows_omitted", row.getInt("sensor_rows_omitted"))
                    .put("capture_status", WorkoutCaptureStatus(app).read(record.evidence.snapshot))
                    .also { evidenceIds.add(row.getString("evidence_id")) }
            }
            else -> error("Unknown read tool")
        }
        require(result.toString().length <= 30_000)
        outputs.put(JSONObject().put("tool", name).put("arguments", args).put("result", result))
        return result
    }
    private fun whole(args: JSONObject, key: String): Long {
        val number = args.get(key) as? Number ?: error("Expected integer")
        val value = number.toDouble(); require(value.isFinite() && value in 0.0..1e15 && value == number.toLong().toDouble())
        return number.toLong()
    }
    private fun nullableString(args: JSONObject, key: String): String? {
        if (args.isNull(key)) return null
        return (args.get(key) as? String ?: error("Expected string")).also { require(it.isNotBlank() && it.length <= 160) }
    }
    companion object {
        fun definitions(): JSONArray {
            fun nullable(type: String) = JSONObject().put("type", JSONArray(listOf(type, "null")))
            fun tool(name: String, description: String, properties: JSONObject) = JSONObject().put("type", "function")
                .put("name", name).put("description", description).put("strict", true)
                .put("parameters", JSONObject().put("type", "object").put("properties", properties)
                    .put("required", JSONArray(properties.keys().asSequence().toList())).put("additionalProperties", false))
            return JSONArray().put(JSONObject().put("type", "namespace").put("name", "coach_records")
                .put("description", "현재 프로필이 직접 연결한 운동 기록만 읽는 도구. 수정 권한 없음.")
                .put("tools", JSONArray().put(tool("read_profile", "사용자가 입력한 현재 프로필", JSONObject()))
                    .put(tool("read_workouts", "루틴·운동·시작 날짜 범위의 완료 기록. offset으로 더 오래된 기록 조회", JSONObject()
                        .put("routine_id", nullable("string")).put("exercise_id", nullable("string"))
                        .put("from_ms", nullable("integer")).put("to_ms", nullable("integer"))
                        .put("limit", JSONObject().put("type", "integer")).put("offset", JSONObject().put("type", "integer"))))
                    .put(tool("read_sensor_summary", "소유 세션·완료 세트의 손목 특징과 실제 원본 수신 상태", JSONObject()
                        .put("session_id", JSONObject().put("type", "string")).put("set_id", nullable("string"))))))
        }
    }
}

/** At most two read rounds (six calls), then one answer. A failed call is never retried. */
internal class AiCoachToolHttp(private val delegate: ChatGptWorkHttp, private val reads: AiCoachReadTools) : ChatGptWorkHttp {
    override fun execute(accessToken: String, body: JSONObject, cancellation: ChatGptWorkCancellation,
        onDelta: (String) -> Unit): ChatGptResponseResult {
        val input = JSONArray(body.getJSONArray("input").toString())
        val seen = mutableSetOf<String>()
        val started = System.nanoTime()
        repeat(3) { round ->
            cancellation.check()
            if (System.nanoTime() - started > 180_000_000_000L) throw ChatGptWorkError("상담 조회 시간이 초과됐어요", "timeout")
            val request = JSONObject(body.toString()).put("input", input).put("include", JSONArray(listOf("reasoning.encrypted_content")))
                .put("tools", AiCoachReadTools.definitions()).put("tool_choice", if (round == 2) "none" else "auto")
            require(request.toString().length <= 240_000)
            val response = delegate.execute(accessToken, request, cancellation, onDelta)
            val output = JSONArray(response.outputJson)
            val calls = (0 until output.length()).map { output.getJSONObject(it) }.filter { it.optString("type") == "function_call" }
            if (calls.isEmpty()) return response
            require(round < 2 && calls.size in 1..3)
            (0 until output.length()).forEach { input.put(output.getJSONObject(it)) }
            calls.forEach { call ->
                cancellation.check()
                require(call.optString("namespace") == "coach_records")
                val id = call.getString("call_id"); require(id.isNotBlank() && seen.add(id))
                val arguments = call.getString("arguments"); require(arguments.length <= 4_000)
                val result = reads.execute(call.getString("name"), JSONObject(arguments))
                input.put(JSONObject().put("type", "function_call_output").put("call_id", id).put("output", result.toString()))
            }
        }
        error("Unfinished read request")
    }
}
