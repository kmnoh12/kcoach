package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var observedRevision: Long? = null

    /** Prevent an old Activity from overwriting a completion committed by a service. */
    fun saveActive(snapshot: WorkoutSnapshot): Boolean = synchronized(lock) {
        val revision = prefs.getLong(KEY_REVISION, 0)
        if (observedRevision != revision && (observedRevision != null || prefs.contains(KEY_ACTIVE_SNAPSHOT))) return@synchronized false
        commit(snapshot)
    }

    fun loadActive(): Result<WorkoutSnapshot?> = synchronized(lock) { runCatching { read() } }

    fun completeSet(event: SetCompletionEvent): Result<SetCompletionResult> = synchronized(lock) {
        runCatching { complete(read() ?: error("Missing active workout snapshot"), event) }
    }

    fun completeWatchSet(event: SetCompletionEvent, nowMs: Long, completionId: String? = null): Result<SetCompletionResult> = synchronized(lock) {
        runCatching {
            val current = read() ?: error("Missing active workout snapshot")
            val set = SetIdentity.resolve(current, event.exerciseId, event.setIndex, event.setId, event.attemptId)
            if (event.sessionId == current.sessionId && completionId != null && set?.completed == true) {
                check(prefs.edit().putString(KEY_RECEIPTS, receiptList(event.sessionId, completionId)).commit())
                return@runCatching SetCompletionResult.Duplicate(current)
            }
            val restMs = (set?.restSeconds ?: 90) * 1_000L
            complete(current, event.copy(restEndAtMs = nowMs + restMs, repSource = SetRepSource.WATCH), completionId, if (current.paused) restMs else null)
        }
    }

    /** Only one timer can claim a rest transition, including after a service restart. */
    fun advanceAfterRest(nowMs: Long, skip: Boolean = false): Result<WorkoutSnapshot?> = synchronized(lock) {
        runCatching {
            val current = read() ?: return@runCatching null
            val next = WorkoutSessionReducer.advanceAfterRest(current, nowMs, skip) ?: return@runCatching null
            check(commit(next)) { "Failed to commit rest transition" }
            next
        }
    }

    internal fun confirmReps(sessionId: String, analyses: Map<String, SetAnalysisUi>): Result<WorkoutSnapshot?> = synchronized(lock) {
        runCatching {
            val current = read()?.takeIf { it.sessionId == sessionId } ?: return@runCatching null
            val updated = analyses.values.fold(current) { snapshot, analysis ->
                WorkoutSessionReducer.confirmReps(snapshot, analysis.exerciseId, analysis.setIndex, analysis.confirmedReps, analysis.setId, analysis.attemptId)
            }
            if (updated != current) check(commit(updated)) { "Failed to persist confirmed reps" }
            updated
        }
    }

    fun clearActive(expectedSessionId: String? = null): Boolean = synchronized(lock) {
        if (expectedSessionId != null) {
            if (read()?.sessionId != expectedSessionId) return@synchronized false
        } else if (observedRevision != prefs.getLong(KEY_REVISION, 0)) return@synchronized false
        val revision = prefs.getLong(KEY_REVISION, 0) + 1
        val session = prefs.getString(KEY_ACTIVE_SNAPSHOT, null)?.let(WorkoutSnapshotCodec::decode)?.sessionId
        val edit = prefs.edit().remove(KEY_ACTIVE_SNAPSHOT).putLong(KEY_REVISION, revision)
        if (session != null) edit.putString(KEY_CLOSED_SESSIONS, closedList(session))
        edit.commit().also { if (it) observedRevision = revision }
    }

    fun rememberCompletionReceipt(sessionId: String, completionId: String): Boolean = synchronized(lock) {
        prefs.edit().putString(KEY_RECEIPTS, receiptList(sessionId, completionId)).commit()
    }

    fun hasCompletionReceipt(sessionId: String, completionId: String): Boolean = synchronized(lock) {
        prefs.getString(KEY_RECEIPTS, "").orEmpty().lineSequence().any { it == "$sessionId:$completionId" }
    }

    fun isSessionClosed(sessionId: String): Boolean = synchronized(lock) {
        prefs.getString(KEY_CLOSED_SESSIONS, "").orEmpty().lineSequence().any { it == sessionId }
    }

    private fun receiptList(sessionId: String, completionId: String) =
        (prefs.getString(KEY_RECEIPTS, "").orEmpty().lines().filter { it.isNotBlank() } + "$sessionId:$completionId").distinct().takeLast(256).joinToString("\n")

    private fun closedList(sessionId: String) =
        (prefs.getString(KEY_CLOSED_SESSIONS, "").orEmpty().lines().filter { it.isNotBlank() } + sessionId).distinct().takeLast(32).joinToString("\n")

    private fun read(): WorkoutSnapshot? {
        observedRevision = prefs.getLong(KEY_REVISION, 0)
        return prefs.getString(KEY_ACTIVE_SNAPSHOT, null)?.let(WorkoutSnapshotCodec::decode)
    }

    private fun complete(current: WorkoutSnapshot, event: SetCompletionEvent, completionId: String? = null, pausedRestMs: Long? = null): SetCompletionResult {
        val reduced = WorkoutSessionReducer.completeSet(current, event)
        val result = if (reduced is SetCompletionResult.Applied && pausedRestMs != null) {
            reduced.copy(snapshot = reduced.snapshot.copy(paused = true, phase = WorkoutPhase.PAUSED, restEndAtMs = 0, pausedRestRemainingMs = pausedRestMs))
        } else reduced
        if (result is SetCompletionResult.Applied) check(commit(result.snapshot, completionId)) { "Failed to commit set completion snapshot" }
        return result
    }

    private fun commit(snapshot: WorkoutSnapshot, completionId: String? = null): Boolean {
        val revision = prefs.getLong(KEY_REVISION, 0) + 1
        val edit = prefs.edit().putString(KEY_ACTIVE_SNAPSHOT, WorkoutSnapshotCodec.encode(snapshot)).putLong(KEY_REVISION, revision)
        if (completionId != null) edit.putString(KEY_RECEIPTS, receiptList(snapshot.sessionId, completionId))
        val previous = prefs.getString(KEY_ACTIVE_SNAPSHOT, null)?.let { runCatching { WorkoutSnapshotCodec.decode(it) }.getOrNull() }
        if (previous != null && previous.sessionId != snapshot.sessionId) edit.putString(KEY_CLOSED_SESSIONS, closedList(previous.sessionId))
        return edit.commit().also { if (it) observedRevision = revision }
    }

    companion object {
        private val lock = Any()
        private const val PREFS_NAME = "workout-session"
        private const val KEY_ACTIVE_SNAPSHOT = "activeSnapshot"
        private const val KEY_REVISION = "revision"
        private const val KEY_RECEIPTS = "completionReceipts"
        private const val KEY_CLOSED_SESSIONS = "closedSessions"
    }
}
