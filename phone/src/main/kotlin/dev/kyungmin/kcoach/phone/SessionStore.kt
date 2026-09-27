package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.WorkoutSnapshot
import dev.kyungmin.kcoach.core.WorkoutSnapshotCodec
import dev.kyungmin.kcoach.core.SetCompletionEvent
import dev.kyungmin.kcoach.core.SetCompletionResult
import dev.kyungmin.kcoach.core.WorkoutSessionReducer

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun saveActive(snapshot: WorkoutSnapshot): Boolean =
        prefs.edit().putString(KEY_ACTIVE_SNAPSHOT, WorkoutSnapshotCodec.encode(snapshot)).commit()

    @Synchronized
    fun loadActive(): Result<WorkoutSnapshot?> = runCatching {
        prefs.getString(KEY_ACTIVE_SNAPSHOT, null)?.let(WorkoutSnapshotCodec::decode)
    }

    @Synchronized
    fun completeSet(event: SetCompletionEvent): Result<SetCompletionResult> = runCatching {
        val current = prefs.getString(KEY_ACTIVE_SNAPSHOT, null)
            ?.let(WorkoutSnapshotCodec::decode)
            ?: error("Missing active workout snapshot")
        val result = WorkoutSessionReducer.completeSet(current, event)
        if (result is SetCompletionResult.Applied) {
            check(
                prefs.edit()
                    .putString(KEY_ACTIVE_SNAPSHOT, WorkoutSnapshotCodec.encode(result.snapshot))
                    .commit(),
            ) { "Failed to commit set completion snapshot" }
        }
        result
    }

    @Synchronized
    fun clearActive(): Boolean = prefs.edit().remove(KEY_ACTIVE_SNAPSHOT).commit()

    companion object {
        private const val PREFS_NAME = "workout-session"
        private const val KEY_ACTIVE_SNAPSHOT = "activeSnapshot"
    }
}
