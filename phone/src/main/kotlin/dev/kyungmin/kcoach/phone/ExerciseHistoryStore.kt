package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.ExerciseHistory
import dev.kyungmin.kcoach.core.ExerciseHistoryCodec
import dev.kyungmin.kcoach.core.ExerciseLog

/** Keeps what was actually done per exercise, on this phone only. */
class ExerciseHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("exercise-history", Context.MODE_PRIVATE)

    fun load(): ExerciseHistory = synchronized(lock) {
        runCatching { ExerciseHistory(ExerciseHistoryCodec.decode(prefs.getString(KEY, "").orEmpty())) }
            .getOrDefault(ExerciseHistory())
    }

    fun add(log: ExerciseLog): ExerciseHistory = synchronized(lock) {
        val updated = load().with(log)
        check(prefs.edit().putString(KEY, ExerciseHistoryCodec.encode(updated.all)).commit()) { "Failed to save exercise history" }
        updated
    }

    private companion object {
        val lock = Any()
        const val KEY = "logs"
    }
}
