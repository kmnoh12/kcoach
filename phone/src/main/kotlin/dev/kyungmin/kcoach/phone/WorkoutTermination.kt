package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.WearSync

/** Journal the user's intent before I/O. A failed archive is retried before session restoration. */
internal class WorkoutTermination(private val context: Context) {
    private val prefs = context.getSharedPreferences("workout-termination", Context.MODE_PRIVATE)
    fun pending(): Boolean = prefs.contains("session")

    fun stop(nowMs: Long = System.currentTimeMillis()): Result<Unit> = synchronized(WorkoutRuntimeLock.monitor) {
        runCatching {
            val snapshot = SessionStore(context).loadActive().getOrThrow()
            if (snapshot != null) {
                check(prefs.edit().putString("session", snapshot.sessionId).commit())
                // Queue STOP independently of archival success. A reconnect must never resync START.
                PhoneCommandOutbox(context).enqueue(WearSync.command("STOP", snapshot.sessionId, "", 0, 0))
                archiveAndClear(nowMs)
            }
        }
    }

    fun recover(nowMs: Long = System.currentTimeMillis()): Result<Unit> = synchronized(WorkoutRuntimeLock.monitor) {
        runCatching { if (pending()) archiveAndClear(nowMs) }
    }

    private fun archiveAndClear(nowMs: Long) {
        val id = prefs.getString("session", null) ?: return
        val store = SessionStore(context)
        val snapshot = store.loadActive().getOrThrow()
        if (snapshot?.sessionId == id) {
            val pausedMs = snapshot.totalPausedMs + if (snapshot.pausedSinceMs > 0)
                (nowMs - snapshot.pausedSinceMs).coerceAtLeast(0) else 0
            val minutes = ((nowMs - snapshot.startedAtMs - pausedMs).coerceAtLeast(0) / 60_000)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            FinishedWorkoutStore(context).finish(snapshot, snapshot.routineName.ifBlank { "운동" }, minutes)
            check(store.clearActive(id)) { "Failed to close stopped workout" }
        }
        check(prefs.edit().remove("session").commit())
    }
}
