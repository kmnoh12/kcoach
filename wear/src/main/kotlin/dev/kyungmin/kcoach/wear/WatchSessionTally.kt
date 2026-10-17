package dev.kyungmin.kcoach.wear

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Running totals for the workout result screen, built only from what this watch measured while the workout ran:
 * when the session was first seen, every set the watch saw end (reps and load), the heart rate it read, and the
 * records the phone announced. Nothing here is estimated; a total the watch never saw stays null.
 *
 * The workout service feeds it every state it broadcasts ([observe]); the result is taken when the phone completes
 * the workout ([finish], called from [WatchHomeStore.complete]).
 */
internal class WatchSessionTally private constructor(private val app: Context) {
    private val prefs = app.getSharedPreferences("watch-session-tally", Context.MODE_PRIVATE)
    private data class SetEntry(val key: String, val reps: Int, val weightKg: Double?)

    private var sessionId = ""
    private var startedAtMs = 0L
    private val sets = LinkedHashMap<String, SetEntry>()
    private var open: SetEntry? = null
    private var lastKey = ""
    private val records = LinkedHashSet<String>()
    private var heartWeighted = 0.0
    private var heartMs = 0L
    private var lastHeart = 0
    private var lastHeartAtMs = 0L
    private var savedAtMs = 0L

    init { restore() }

    /** Called with every state the workout service broadcasts. Cheap: it only writes when something changed. */
    @Synchronized
    fun observe(session: String, setId: String, attemptId: String, running: Boolean, resting: Boolean, paused: Boolean,
        reps: Int, weightKg: Double?, heartRate: Int, nowMs: Long = System.currentTimeMillis()) {
        if (session.isBlank() || session.startsWith("calibration-")) return
        if (session != sessionId) {
            if (!running && !resting && !paused) return
            reset(session, nowMs)
        }
        var changed = false
        val key = "$setId|$attemptId"
        val current = open
        if (running && !paused && setId.isNotBlank()) {
            // The phone moved straight on to another set: the one measured until now is over.
            if (current != null && current.key != key && current.reps > 0) { sets[current.key] = current; lastKey = current.key; changed = true }
            open = SetEntry(key, reps, weightKg)
        } else if (current != null && !paused) {
            // The set this watch was measuring is over. When the state still names that set, its final count
            // (the automatic count, or the count the user submitted) is the one to keep.
            val finalEntry = if (current.key == key) SetEntry(key, reps, weightKg) else current
            if (finalEntry.reps > 0) { sets[finalEntry.key] = finalEntry; lastKey = finalEntry.key }
            open = null; changed = true
        }
        if (heartRate > 0 && lastHeart > 0 && lastHeartAtMs > 0L && nowMs > lastHeartAtMs) {
            // Weighted by time, so the average does not depend on how often states were broadcast.
            val dt = (nowMs - lastHeartAtMs).coerceAtMost(5_000L)
            heartWeighted += lastHeart.toDouble() * dt; heartMs += dt
        }
        if (heartRate != lastHeart || heartRate > 0) { lastHeart = heartRate; lastHeartAtMs = nowMs }
        if (changed || nowMs - savedAtMs > 15_000L) save(nowMs)
    }

    /** The user changed the set just finished (rest editor on the watch). */
    @Synchronized
    fun editLast(reps: Int? = null, weightKg: Double? = null) {
        val entry = sets[lastKey] ?: return
        sets[lastKey] = entry.copy(reps = reps ?: entry.reps, weightKg = weightKg ?: entry.weightKg)
        if (sets[lastKey]!!.reps <= 0) sets.remove(lastKey)
        save(System.currentTimeMillis())
    }

    /** The phone announced a record for [exerciseId] in [session]; one exercise counts once. */
    @Synchronized
    fun noteRecord(session: String, exerciseId: String) {
        if (session != sessionId || exerciseId.isBlank() || !records.add(exerciseId)) return
        save(System.currentTimeMillis())
    }

    /** Closes [session] and returns what the watch knows about it, or null when it never saw that session. */
    @Synchronized
    fun finish(session: String, nowMs: Long = System.currentTimeMillis()): WatchHomeStore.Summary? {
        if (session != sessionId || startedAtMs <= 0L) return null
        open?.takeIf { it.reps > 0 }?.let { sets[it.key] = it }
        open = null
        if (lastHeart > 0 && lastHeartAtMs > 0L && nowMs > lastHeartAtMs) {
            val dt = (nowMs - lastHeartAtMs).coerceAtMost(5_000L)
            heartWeighted += lastHeart.toDouble() * dt; heartMs += dt
        }
        val loaded = sets.values.filter { (it.weightKg ?: 0.0) > 0.0 }
        val summary = WatchHomeStore.Summary(
            elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0L),
            sets = sets.size.takeIf { it > 0 },
            volumeKg = loaded.takeIf { it.isNotEmpty() }?.sumOf { it.reps * (it.weightKg ?: 0.0) },
            // Under half a minute of readings is not an average worth showing.
            averageHeartRate = if (heartMs >= 30_000L) Math.round(heartWeighted / heartMs).toInt() else null,
            recordsBroken = records.size.takeIf { it > 0 },
        )
        reset("", 0L)
        return summary
    }

    private fun reset(session: String, nowMs: Long) {
        sessionId = session; startedAtMs = nowMs
        sets.clear(); open = null; lastKey = ""; records.clear()
        heartWeighted = 0.0; heartMs = 0L; lastHeart = 0; lastHeartAtMs = 0L
        save(nowMs)
    }

    private fun save(nowMs: Long) {
        savedAtMs = nowMs
        val json = JSONObject().put("session", sessionId).put("startedAt", startedAtMs).put("lastKey", lastKey)
            .put("heartWeighted", heartWeighted).put("heartMs", heartMs)
            .put("records", JSONArray(records.toList()))
            .put("sets", JSONArray(sets.values.map { JSONObject().put("key", it.key).put("reps", it.reps).put("weight", it.weightKg ?: JSONObject.NULL) }))
        open?.let { json.put("open", JSONObject().put("key", it.key).put("reps", it.reps).put("weight", it.weightKg ?: JSONObject.NULL)) }
        prefs.edit().putString("tally", json.toString()).apply()
    }

    private fun restore() {
        val json = prefs.getString("tally", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        fun entry(o: JSONObject) = SetEntry(o.getString("key"), o.getInt("reps"), if (o.isNull("weight")) null else o.getDouble("weight"))
        runCatching {
            sessionId = json.getString("session"); startedAtMs = json.getLong("startedAt"); lastKey = json.optString("lastKey")
            heartWeighted = json.getDouble("heartWeighted"); heartMs = json.getLong("heartMs")
            val list = json.getJSONArray("records"); for (i in 0 until list.length()) records += list.getString(i)
            val array = json.getJSONArray("sets"); for (i in 0 until array.length()) entry(array.getJSONObject(i)).let { sets[it.key] = it }
            open = json.optJSONObject("open")?.let(::entry)
        }.onFailure { sessionId = ""; startedAtMs = 0L; sets.clear(); records.clear(); open = null }
    }

    companion object {
        @Volatile private var instance: WatchSessionTally? = null
        /** One tally per process; the workout service and the screen share it. */
        fun get(context: Context): WatchSessionTally {
            val app = context.applicationContext
            instance?.takeIf { it.app === app }?.let { return it }
            return synchronized(this) { instance?.takeIf { it.app === app } ?: WatchSessionTally(app).also { instance = it } }
        }
    }
}
