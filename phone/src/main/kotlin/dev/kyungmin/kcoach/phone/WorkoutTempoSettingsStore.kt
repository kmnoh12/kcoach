package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.WorkoutTempoSettings
import dev.kyungmin.kcoach.core.WorkoutTempoSettingsCodec

internal class WorkoutTempoSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("workout-tempo-settings", Context.MODE_PRIVATE)
    fun load(exerciseId: String): Result<WorkoutTempoSettings> = synchronized(lock) { runCatching {
        val fallback = WorkoutTempoSettings(exerciseId)
        prefs.getString(exerciseId, null)?.let(WorkoutTempoSettingsCodec::decode)?.also {
            require(it.exerciseId == exerciseId)
        } ?: fallback
    } }
    fun save(settings: WorkoutTempoSettings): Result<Unit> = synchronized(lock) { runCatching {
        check(prefs.edit().putString(settings.exerciseId, WorkoutTempoSettingsCodec.encode(settings)).commit())
    } }
    companion object { private val lock = Any() }
}
