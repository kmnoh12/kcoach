package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.WorkoutLog
import dev.kyungmin.kcoach.core.WorkoutLogCodec

/** Keeps every finished workout, on this phone only. */
class WorkoutLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("workout-log", Context.MODE_PRIVATE)

    fun load(): List<WorkoutLog> = synchronized(lock) {
        runCatching { WorkoutLogCodec.decode(prefs.getString(KEY, "").orEmpty()) }.getOrDefault(emptyList())
    }

    fun add(log: WorkoutLog): List<WorkoutLog> = synchronized(lock) {
        val updated = load().filterNot { log.sessionId != null && it.sessionId == log.sessionId } + log
        check(prefs.edit().putString(KEY, WorkoutLogCodec.encode(updated)).commit()) { "Failed to save workout log" }
        updated
    }

    private companion object {
        val lock = Any()
        const val KEY = "logs"
    }
}
