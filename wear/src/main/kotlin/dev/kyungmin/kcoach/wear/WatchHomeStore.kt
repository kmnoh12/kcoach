package dev.kyungmin.kcoach.wear

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Display-only companion data. Missing data never becomes a measured zero. */
internal class WatchHomeStore(context: Context) {
    private val prefs = context.getSharedPreferences("watch-home", Context.MODE_PRIVATE)
    data class Completion(val sessionId: String, val endedAtMs: Long)
    fun completion(): Completion? = prefs.getString("completedSession", null)?.takeIf(::validSession)
        ?.let { Completion(it, prefs.getLong("completedAt", 0L)) }
    fun complete(session: String, nowMs: Long = System.currentTimeMillis()) {
        require(validSession(session))
        check(prefs.edit().putString("completedSession", session).putLong("completedAt", nowMs)
            .putBoolean("showCompletion", true).commit())
    }
    fun showsCompletion() = prefs.getBoolean("showCompletion", false) && completion() != null
    fun leaveCompletion() { prefs.edit().putBoolean("showCompletion", false).apply() }
    fun overview(): JSONObject? = prefs.getString("overview", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun receiveOverview(payload: String): Boolean = synchronized(lock) {
        runCatching {
            require(payload.length <= 8_192)
            val json = JSONObject(payload)
            require(json.getInt("schemaVersion") == 1)
            val stamp = json.getLong("generatedAtMs")
            require(stamp > 0 && stamp <= System.currentTimeMillis() + 300_000L)
            LocalDate.parse(json.getString("weekStartDate"))
            require(json.getInt("weekWorkoutCount") in 0..10_000)
            if (!json.isNull("recent")) {
                val recent = json.getJSONObject("recent")
                require(validSession(recent.getString("sessionId")))
                require(recent.getString("routineName").length in 1..120)
                require(recent.getLong("endedAtMs") in 1..stamp)
            }
            require(stamp >= (overview()?.optLong("generatedAtMs") ?: 0L))
            check(prefs.edit().putString("overview", json.toString()).commit())
        }.isSuccess
    }
    fun weekCount(today: LocalDate = LocalDate.now()): Int? = overview()?.takeIf {
        it.optString("weekStartDate") == today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
    }?.getInt("weekWorkoutCount")
    companion object {
        const val PATH_OVERVIEW = "/kcoach/watch/overview"
        const val ACTION_OVERVIEW = "dev.kyungmin.kcoach.WATCH_OVERVIEW"
        private val lock = Any()
        fun validSession(value: String) = value.matches(Regex("[A-Za-z0-9._-]{1,160}"))
    }
}
