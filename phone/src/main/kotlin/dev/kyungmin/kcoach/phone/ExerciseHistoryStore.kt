package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.ExerciseHistory
import dev.kyungmin.kcoach.core.ExerciseHistoryCodec
import dev.kyungmin.kcoach.core.ExerciseLog

/** Keeps what was actually done per exercise, on this phone only. */
class ExerciseHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("exercise-history", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): ExerciseHistory =
        runCatching { ExerciseHistory(ExerciseHistoryCodec.decode(prefs.getString(KEY, "").orEmpty())) }
            .getOrDefault(ExerciseHistory())

    @Synchronized
    fun add(log: ExerciseLog): ExerciseHistory {
        val updated = load().with(log)
        prefs.edit().putString(KEY, ExerciseHistoryCodec.encode(updated.all)).apply()
        return updated
    }

    private companion object {
        const val KEY = "logs"
    }
}
