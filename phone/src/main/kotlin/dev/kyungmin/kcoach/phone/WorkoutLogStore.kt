package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.WorkoutLog
import dev.kyungmin.kcoach.core.WorkoutLogCodec

/** Keeps every finished workout, on this phone only. */
class WorkoutLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("workout-log", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<WorkoutLog> =
        runCatching { WorkoutLogCodec.decode(prefs.getString(KEY, "").orEmpty()) }.getOrDefault(emptyList())

    @Synchronized
    fun add(log: WorkoutLog): List<WorkoutLog> {
        val updated = load() + log
        prefs.edit().putString(KEY, WorkoutLogCodec.encode(updated)).apply()
        return updated
    }

    private companion object {
        const val KEY = "logs"
    }
}
