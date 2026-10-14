package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONObject

data class WorkoutWearingSelection(val side: String, val placement: String, val deviceTop: String? = null,
    val startVariants: Map<String, String> = emptyMap()) {
    init {
        require(side in setOf("LEFT", "RIGHT") && placement in setOf("OUTSIDE", "INSIDE"))
        require(deviceTop == null || deviceTop in setOf("ELBOW", "HAND"))
        require(startVariants.size <= 3)
        startVariants.forEach { (id, variant) ->
            require(id == ExerciseMotionCatalog.normalizeExerciseId(id) && variant in StartWristContext.variantsFor(id))
        }
    }
}

/** The user confirms wearing once per workout; every attempt keeps its initial binding, including absence. */
class WorkoutWearingStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("workout-wearing", Context.MODE_PRIVATE)

    fun beginSession(sessionId: String, selection: WorkoutWearingSelection?): Result<Unit> = synchronized(lock) {
        runCatching {
            val key = sessionKey(sessionId)
            val previous = read(sessionId)
            if (previous != null) {
                require(selection(previous) == selection) { "Wearing is fixed for this workout; start a new workout to change it" }
                return@runCatching
            }
            val json = JSONObject().put("schemaVersion", 2).put("owner", PatternCandidateStore(context).localUserId())
                .put("side", selection?.side ?: JSONObject.NULL).put("placement", selection?.placement ?: JSONObject.NULL)
                .put("deviceTop", selection?.deviceTop ?: JSONObject.NULL)
                .put("startVariants", JSONObject(selection?.startVariants.orEmpty()))
                .put("bindings", JSONObject())
            val edit = prefs.edit().putString(key, json.toString())
            if (selection != null) {
                edit.putString("last-side", selection.side).putString("last-placement", selection.placement)
                    .putString("last-start-variants", JSONObject(selection.startVariants).toString())
                if (selection.deviceTop == null) edit.remove("last-top") else edit.putString("last-top", selection.deviceTop)
            }
            check(edit.commit()) { "Could not save wearing confirmation" }
        }
    }

    fun selection(sessionId: String): Result<WorkoutWearingSelection?> = synchronized(lock) { runCatching { read(sessionId)?.let(::selection) } }

    fun lastSelection(): Result<WorkoutWearingSelection?> = synchronized(lock) {
        runCatching {
            val side = prefs.getString("last-side", null) ?: return@runCatching null
            WorkoutWearingSelection(side, requireNotNull(prefs.getString("last-placement", null)),
                prefs.getString("last-top", null), variants(JSONObject(prefs.getString("last-start-variants", "{}")!!)))
        }
    }

    fun bind(payload: String): Result<String> = synchronized(lock) {
        runCatching {
            val fields = WearSync.parse(payload)
            if (fields.firstOrNull() != "2" || fields.getOrNull(1) !in setOf("START", "SET", "SYNC")) return@runCatching payload
            val session = fields.getOrNull(2).orEmpty()
            if (CapturePurpose.forSession(session) != CapturePurpose.WORKOUT) return@runCatching payload
            if (fields[1] == "SYNC" && (fields.getOrNull(11) == "RESTING" || (fields.getOrNull(12)?.toLongOrNull() ?: 0) > 0))
                return@runCatching payload
            val json = read(session) ?: return@runCatching payload // Existing workouts retain the legacy path.
            val set = fields.getOrNull(13).orEmpty(); val attempt = fields.getOrNull(14).orEmpty()
            if (!set.matches(ID) || !attempt.matches(ID)) return@runCatching payload
            val exercise = fields[3]
            val identity = "$set:$attempt"
            val bindings = json.getJSONObject("bindings")
            val previous = bindings.optJSONObject(identity)
            if (previous != null) {
                require(previous.getString("exerciseId") == exercise) { "Set identity was reused for a different exercise" }
            } else {
                val wearing = selection(json)
                val binding = wearing?.let { PatternWearingContext(json.getString("owner"), it.side, it.placement).encode() }.orEmpty()
                require(bindings.length() < 10_000) { "Existing set bindings retained; workout binding limit reached" }
                val normalized = ExerciseMotionCatalog.normalizeExerciseId(exercise)
                val startContext = if (wearing != null && StartWristContext.expectedVariant(normalized) != null)
                    StartWristContextCodec.encode(StartWristContext(normalized, json.getString("owner"), wearing.side,
                        wearing.placement, wearing.deviceTop ?: "UNKNOWN", wearing.startVariants[normalized] ?: "UNKNOWN")) else ""
                val direction = startContext.takeIf { it.isNotEmpty() }?.let(StartWristContextCodec::decode)
                    ?.takeIf { it.completeForDirectionValidation }?.let {
                        // Invalidated evidence falls back to an explicitly unconfirmed start; it never learns from that set.
                        StartDirectionReviewStore(context).selected(it).getOrNull()
                    }
                bindings.put(identity, JSONObject().put("exerciseId", exercise).put("binding", binding).put("startContext", startContext)
                    .put("startDirectionSnapshot", direction?.let(StartDirectionRuntimeCodec::encode).orEmpty()))
                check(prefs.edit().putString(sessionKey(session), json.toString()).commit()) { "Could not freeze the set binding" }
            }
            val frozen = bindings.getJSONObject(identity)
            val encoded = frozen.getString("binding")
            val startContext = frozen.optString("startContext") // Old attempts keep absence, rather than acquiring new facts.
            val explicit = fields.getOrNull(17).orEmpty()
            require(explicit.isEmpty() || explicit == encoded) { "Set wearing binding changed" }
            require(fields.getOrNull(16).isNullOrEmpty()) { "Normal workouts select the active device on the watch" }
            val explicitStart = fields.getOrNull(19).orEmpty()
            require(explicitStart.isEmpty() || explicitStart == startContext) { "Set start direction context changed" }
            val bound = if (encoded.isEmpty()) payload else WearSync.withPatternWearing(payload, PatternWearingContext.decode(encoded))
            val selected = WearSync.withVerifiedPatternSelection(bound)
            val withContext = if (startContext.isEmpty()) selected else WearSync.withStartWristContext(selected, StartWristContextCodec.decode(startContext))
            val snapshot = frozen.optString("startDirectionSnapshot")
            require(fields.getOrNull(20).isNullOrEmpty() || fields[20] == snapshot) { "Set start direction profile changed" }
            if (snapshot.isEmpty()) withContext else WearSync.withStartDirectionProfile(withContext, StartDirectionRuntimeCodec.decode(snapshot))
        }
    }

    private fun variants(json: JSONObject): Map<String, String> = json.keys().asSequence().associateWith { json.getString(it) }
    private fun selection(json: JSONObject): WorkoutWearingSelection? =
        if (json.isNull("side") && json.isNull("placement")) null else WorkoutWearingSelection(json.getString("side"), json.getString("placement"),
            json.optString("deviceTop").takeIf { json.has("deviceTop") && !json.isNull("deviceTop") && it.isNotBlank() },
            variants(json.optJSONObject("startVariants") ?: JSONObject()))
    private fun read(session: String): JSONObject? = prefs.getString(sessionKey(session), null)?.let(::JSONObject)
        ?.also { require(it.getInt("schemaVersion") in 1..2) }
    private fun sessionKey(session: String): String { require(session.matches(ID)); return "session:$session" }
    companion object { private val lock = Any(); private val ID = Regex("[A-Za-z0-9._-]{1,160}") }
}
