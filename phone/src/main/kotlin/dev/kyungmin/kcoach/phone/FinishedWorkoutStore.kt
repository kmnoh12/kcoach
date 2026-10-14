package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Durable finished sessions allow late native analysis and interrupted log writes to be replayed. */
internal class FinishedWorkoutStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("finished-workouts", Context.MODE_PRIVATE)

    internal data class Evidence(val snapshot: WorkoutSnapshot, val title: String, val minutes: Int, val analysisRevision: Long, val finishedAtMs: Long = snapshot.startedAtMs + minutes * 60_000L + snapshot.totalPausedMs)

    /** Read current snapshots, rather than stale projected totals, for a bounded AI context. */
    fun recentEvidence(limit: Int = 30, sessionIds: Set<String>? = null): List<Evidence> = synchronized(lock) {
        require(limit in 1..100)
        readEvidence(sessionIds).take(limit)
    }

    fun allEvidence(): List<Evidence> = synchronized(lock) { readEvidence(null) }

    /** Personal summaries and routine baselines use the whole owned history, not a recent window. */
    fun evidenceForSessions(sessionIds: Set<String>): List<Evidence> = synchronized(lock) { readEvidence(sessionIds) }

    private fun readEvidence(sessionIds: Set<String>?): List<Evidence> =
        prefs.all.filterKeys { it.startsWith("session:") && (sessionIds == null || it.removePrefix("session:") in sessionIds) }.mapNotNull { (key, value) ->
            runCatching {
                val record = JSONObject(value as String)
                val snapshot = WorkoutSnapshotCodec.decode(record.getString("snapshot"))
                require(key == "session:${snapshot.sessionId}")
                Evidence(snapshot, record.getString("title"),
                    record.getInt("minutes"), record.optLong("analysisRevision", 0L), record.optLong("finishedAtMs", snapshot.startedAtMs + record.getInt("minutes") * 60_000L + snapshot.totalPausedMs))
            }.getOrNull()
        }.sortedWith(compareByDescending<Evidence> { it.snapshot.startedAtMs }.thenBy { it.snapshot.sessionId })

    fun finish(snapshot: WorkoutSnapshot, title: String, minutes: Int, usePounds: Boolean = false) = synchronized(lock) {
        if (snapshot.plannedSets.none { it.completed }) return@synchronized
        val key = "session:${snapshot.sessionId}"
        val record = prefs.getString(key, null)?.let(::JSONObject) ?: JSONObject()
            .put("snapshot", WorkoutSnapshotCodec.encode(snapshot))
            .put("title", title).put("minutes", minutes).put("usePounds", usePounds)
            .put("epochDay", Instant.ofEpochMilli(snapshot.startedAtMs).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay())
            .put("projected", false).put("finishedAtMs", System.currentTimeMillis())
        val read = SetAnalysisStore(context).readForSession(snapshot.sessionId)
        val corrected = applyAnalyses(WorkoutSnapshotCodec.decode(record.getString("snapshot")), read.rows)
        if (record.getString("snapshot") != WorkoutSnapshotCodec.encode(corrected)) record.put("projected", false)
        record.put("snapshot", WorkoutSnapshotCodec.encode(corrected)).put("analysisRevision", read.revision)
        check(prefs.edit().putString(key, record.toString()).commit()) { "Failed to save finished session" }
        WorkoutTimeStore(context).close(snapshot.sessionId, record.optLong("finishedAtMs", snapshot.startedAtMs + minutes * 60_000L + snapshot.totalPausedMs))
        project(key, record)
        AutomaticWorkoutReview.finished(context, snapshot.sessionId)
    }

    fun reconcile(sessionId: String, indexedOnly: Boolean = false) = synchronized(lock) {
        val read = SetAnalysisStore(context).readForSession(sessionId, allowLegacyScan = !indexedOnly)
        SessionStore(context).confirmReps(sessionId, read.rows).getOrThrow()
        val key = "session:$sessionId"
        val record = prefs.getString(key, null)?.let(::JSONObject) ?: return@synchronized
        val previous = record.getString("snapshot")
        val snapshot = applyAnalyses(WorkoutSnapshotCodec.decode(previous), read.rows)
        val encoded = WorkoutSnapshotCodec.encode(snapshot)
        if (encoded != previous) record.put("snapshot", encoded).put("projected", false)
        record.put("analysisRevision", read.revision)
        check(prefs.edit().putString(key, record.toString()).commit()) { "Failed to save corrected session" }
        project(key, record)
    }

    /** Startup recovery runs on the listener's worker and touches only unprojected or changed sessions. */
    fun replay(): Int {
        val analyses = SetAnalysisStore(context)
        analyses.refreshIndex()
        return synchronized(lock) {
            var recovered = 0
            val unchanged = prefs.edit()
            var needsCommit = false
            prefs.all.filterKeys { it.startsWith("session:") }.forEach { (key, value) ->
                runCatching {
                    val record = JSONObject(value as String)
                    val session = key.removePrefix("session:")
                    if (!record.optBoolean("projected") || record.optLong("analysisRevision", -1L) != analyses.revision(session)) {
                        val snapshot = WorkoutSnapshotCodec.decode(record.getString("snapshot"))
                        val read = analyses.readForSession(session, allowLegacyScan = false, savedSnapshot = snapshot)
                        val corrected = applyAnalyses(snapshot, read.rows)
                        if (record.optBoolean("projected") && corrected == snapshot) {
                            // Batch migration stamps; thousands of no-op per-record commits copy the entire prefs map.
                            record.put("analysisRevision", read.revision)
                            unchanged.putString(key, record.toString())
                            needsCommit = true
                        } else reconcile(session, indexedOnly = true)
                        recovered++
                    }
                }.onFailure { android.util.Log.e("KCoachEvent", "finished session recovery failed key=$key", it) }
            }
            if (needsCommit) check(unchanged.commit()) { "Failed to persist finished recovery revisions" }
            recovered
        }
    }

    fun recordHits(sessionId: String): List<Pair<String, RecordHit>> = synchronized(lock) {
        val record = prefs.getString("session:$sessionId", null)?.let(::JSONObject) ?: return@synchronized emptyList()
        val snapshot = WorkoutSnapshotCodec.decode(record.getString("snapshot"))
        val history = ExerciseHistoryStore(context).load()
        snapshot.plannedSets.filter { it.completed }.groupBy { it.exerciseId }.flatMap { (id, sets) ->
            val measured = sets.mapNotNull { set -> set.performedReps?.let { LoggedSet(set.weightKg, it) } }
            val log = ExerciseLog(id, record.getLong("epochDay"), measured, sessionId, snapshot.startedAtMs)
            PersonalRecords.detect(history, log).map { sets.first().exerciseName.ifBlank { id } to it }
        }
    }

    fun usePounds(sessionId: String): Boolean = synchronized(lock) {
        prefs.getString("session:$sessionId", null)?.let(::JSONObject)?.optBoolean("usePounds") ?: false
    }

    private fun applyAnalyses(snapshot: WorkoutSnapshot, analyses: Map<String, SetAnalysisUi>) =
        analyses.values.fold(snapshot) { current, analysis ->
            WorkoutSessionReducer.confirmReps(current, analysis.exerciseId, analysis.setIndex, analysis.confirmedReps, analysis.setId, analysis.attemptId)
        }

    private fun project(key: String, record: JSONObject) {
        if (record.optBoolean("projected")) return
        val snapshot = WorkoutSnapshotCodec.decode(record.getString("snapshot"))
        val done = snapshot.plannedSets.filter { it.completed }
        val day = record.getLong("epochDay")
        done.groupBy { it.exerciseId }.forEach { (exerciseId, sets) ->
            val measured = sets.mapNotNull { set -> set.performedReps?.let { LoggedSet(set.weightKg, it) } }
            if (measured.isNotEmpty()) ExerciseHistoryStore(context).add(ExerciseLog(exerciseId, day, measured, snapshot.sessionId, snapshot.startedAtMs))
        }
        WorkoutLogStore(context).add(WorkoutLog(day, record.getString("title"), record.getInt("minutes"), done.size,
            done.sumOf { (it.weightKg ?: 0.0) * (it.performedReps ?: 0) }.toInt(), snapshot.sessionId))
        record.put("projected", true)
        check(prefs.edit().putString(key, record.toString()).commit()) { "Failed to confirm workout projections" }
    }

    companion object { private val lock = Any() }
}
