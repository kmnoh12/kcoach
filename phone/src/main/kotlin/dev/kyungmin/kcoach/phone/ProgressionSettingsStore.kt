package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*

/** Shared by settings and workout menus. No per-routine toggle. */
class ProgressionSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("progression-settings", Context.MODE_PRIVATE)
    fun load(): Result<ProgressionSettings> = synchronized(lock) { runCatching {
        ProgressionSettings(prefs.getBoolean("enabled", true), ProgressionSpeed.valueOf(prefs.getString("speed", "NORMAL")!!))
    } }
    fun save(settings: ProgressionSettings): Result<Unit> = synchronized(lock) { runCatching {
        check(prefs.edit().putBoolean("enabled", settings.enabled).putString("speed", settings.speed.name).commit())
    } }
    companion object { private val lock = Any() }
}

class ProgressionIncrementStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("progression-increments", Context.MODE_PRIVATE)
    fun candidate(exerciseId: String, equipment: String, observedWeights: List<Double> = emptyList()): Result<IncrementCandidate> = synchronized(lock) { runCatching {
        ProgressionDefaults.increment(equipment, observedWeights, prefs.getString(exerciseId, null)?.toDouble())
    } }
    fun save(exerciseId: String, kg: Double): Result<Unit> = synchronized(lock) { runCatching {
        require(exerciseId.isNotBlank() && kg.isFinite() && kg > 0)
        check(prefs.edit().putString(exerciseId, kg.toString()).commit())
    } }
    fun clear(exerciseId: String): Result<Unit> = synchronized(lock) { runCatching {
        check(prefs.edit().remove(exerciseId).commit())
    } }
    companion object { private val lock = Any() }
}
