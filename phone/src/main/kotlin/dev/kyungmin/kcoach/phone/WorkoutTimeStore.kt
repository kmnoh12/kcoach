package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.WorkoutSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** Projection of committed session phase boundaries, not another workout source. */
internal class WorkoutTimeStore(context: Context) {
    private val prefs = context.getSharedPreferences("workout-phase-times", Context.MODE_PRIVATE)
    fun record(snapshot: WorkoutSnapshot, nowMs: Long) = synchronized(lock) {
        if (snapshot.startedAtMs <= 0 || nowMs < snapshot.startedAtMs) return@synchronized
        val row = prefs.getString(snapshot.sessionId, null)?.let(::JSONObject)
            ?: JSONObject().put("start", snapshot.startedAtMs).put("partial", nowMs - snapshot.startedAtMs > 2_000).put("boundaries", JSONArray())
        if (row.has("end")) return@synchronized
        val rows = row.getJSONArray("boundaries")
        val phase = if (snapshot.paused) "PAUSED" else snapshot.phase.name
        if (rows.length() > 0 && rows.getJSONObject(rows.length() - 1).getString("phase") == phase) return@synchronized
        val at = if (rows.length() == 0 && !row.getBoolean("partial")) snapshot.startedAtMs else nowMs
        if (rows.length() > 0 && at <= rows.getJSONObject(rows.length() - 1).getLong("at")) return@synchronized
        rows.put(JSONObject().put("at", at).put("phase", phase))
        check(prefs.edit().putString(snapshot.sessionId, row.toString()).commit())
    }
    fun discard(sessionId: String) = synchronized(lock) {
        check(prefs.edit().remove(sessionId).commit())
    }
    fun close(sessionId: String, nowMs: Long) = synchronized(lock) {
        val row = prefs.getString(sessionId, null)?.let(::JSONObject) ?: return@synchronized
        if (!row.has("end")) check(prefs.edit().putString(sessionId, row.put("end", nowMs).toString()).commit())
    }
    fun read(sessionId: String): JSONObject? = synchronized(lock) {
        val row = prefs.getString(sessionId, null)?.let(::JSONObject) ?: return@synchronized null
        val rows = row.getJSONArray("boundaries")
        val result = JSONObject().put("partial", row.getBoolean("partial"))
        for (i in 0 until rows.length()) {
            val a = rows.getJSONObject(i)
            val end = if (i + 1 < rows.length()) rows.getJSONObject(i + 1).getLong("at") else row.optLong("end", a.getLong("at"))
            val key = a.getString("phase").lowercase() + "_ms"
            result.put(key, result.optLong(key) + (end - a.getLong("at")).coerceAtLeast(0))
        }
        result
    }
    companion object { private val lock = Any() }
}
