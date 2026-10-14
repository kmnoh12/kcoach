package dev.kyungmin.kcoach.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class WorkoutHeartPoint(val receivedAtMs: Long, val bpm: Int, val phase: String)
internal data class WorkoutHeartSummary(val points: List<WorkoutHeartPoint>, val average: Double?, val maximum: Int?, val hasGaps: Boolean)

/** Lightweight new sensor events only. Cached live-state heart values are never treated as new samples. */
internal class WorkoutVitalsStore(context: Context) {
    private val prefs = context.getSharedPreferences("workout-vitals", Context.MODE_PRIVATE)
    fun receive(payload: String, receivedAtMs: Long) = synchronized(lock) {
        val fields = payload.split('|')
        require(fields.size == 7 && fields[0] == "1")
        val session = fields[1]; require(session.matches(Regex("[A-Za-z0-9._-]{1,160}")))
        val sourceNs = fields[2].toLong(); val bpm = fields[3].toInt(); val ageMs = fields[4].toLong()
        val accuracy = fields[5].toInt(); val phase = fields[6]
        require(sourceNs > 0 && bpm in 20..240 && ageMs in 0..10_000 && accuracy in 1..3)
        require(phase in setOf("WORKING", "RESTING", "PAUSED"))
        val row = prefs.getString(session, null)?.let(::JSONObject) ?: JSONObject().put("points", JSONArray()).put("last_source_ns", 0)
        if (sourceNs <= row.getLong("last_source_ns")) return@synchronized
        val at = receivedAtMs - ageMs
        val points = row.getJSONArray("points")
        if (points.length() > 0 && at - points.getJSONObject(points.length() - 1).getLong("at") < 2_000) return@synchronized
        if (points.length() >= 12_000) return@synchronized
        points.put(JSONObject().put("at", at).put("bpm", bpm).put("phase", phase))
        row.put("last_source_ns", sourceNs).put("revision", row.optLong("revision") + 1)
        check(prefs.edit().putString(session, row.toString()).commit())
    }
    fun summary(sessionId: String, startAtMs: Long, endAtMs: Long): WorkoutHeartSummary = synchronized(lock) {
        val raw = prefs.getString(sessionId, null)?.let(::JSONObject)?.getJSONArray("points") ?: JSONArray()
        val points = (0 until raw.length()).map { raw.getJSONObject(it) }
            .filter { it.getLong("at") in startAtMs..endAtMs }
            .map { WorkoutHeartPoint(it.getLong("at"), it.getInt("bpm"), it.getString("phase")) }
        var weighted = 0.0; var duration = 0L
        points.zipWithNext().forEach { (a, b) ->
            val gap = b.receivedAtMs - a.receivedAtMs
            if (gap in 1..15_000) { weighted += a.bpm * gap.toDouble(); duration += gap }
        }
        WorkoutHeartSummary(points, if (duration > 0) weighted / duration else null, points.maxOfOrNull { it.bpm },
            points.isEmpty() || points.first().receivedAtMs - startAtMs > 15_000 || endAtMs - points.last().receivedAtMs > 15_000 ||
                points.zipWithNext().any { (a, b) -> b.receivedAtMs - a.receivedAtMs > 15_000 })
    }
    companion object { private val lock = Any() }
}
