package dev.kyungmin.kcoach.phone

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

internal data class WorkoutProcessExit(val reason: Int, val atMs: Long, val processName: String)

/** Runs synchronously in Application, before services/activities can restore or resync START. */
internal class WorkoutLaunchRecovery(
    private val context: Context,
    private val exits: () -> List<WorkoutProcessExit> = { systemExits(context) },
) {
    fun recover(nowMs: Long = System.currentTimeMillis()): Result<Unit> = runCatching {
        val termination = WorkoutTermination(context)
        val prefs = context.getSharedPreferences("workout-launch-recovery", Context.MODE_PRIVATE)
        val snapshot = SessionStore(context).loadActive().getOrThrow()
        if (snapshot != null) {
            // Only the latest main-process death matters. An older user stop must never
            // override a newer crash or discard a workout started after that stop.
            val exit = runCatching { exits().filter { it.processName == context.packageName }.maxByOrNull { it.atMs } }
                .getOrNull()
            val since = maxOf(snapshot.startedAtMs, prefs.getLong("checkedAtMs", 0L))
            if (exit != null && exit.atMs >= since && exit.atMs < nowMs &&
                exit.reason in setOf(ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED)) {
                termination.stop(saveCompleted = false).getOrThrow()
            }
        }
        termination.recover(nowMs).getOrThrow()
        check(prefs.edit().putLong("checkedAtMs", nowMs).commit())
    }

    companion object {
        private fun systemExits(context: Context): List<WorkoutProcessExit> {
            if (Build.VERSION.SDK_INT < 30) return emptyList()
            return context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 32)
                .map { WorkoutProcessExit(it.reason, it.timestamp, it.processName) }
        }
    }
}
