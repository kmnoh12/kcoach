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
            UserProfileStore(context).deviceProfile()?.profile?.let { json.put("deviceBodyProfile", it.json()) }
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

    /** An explicit unset default stays unset; legacy confirmations only seed unsaved defaults. */
    fun defaultSelection(): Result<WorkoutWearingSelection?> = runCatching {
        val defaults = UserProfileStore(context).deviceProfile()
        if (defaults != null) defaults.wearing else lastSelection().getOrThrow()?.copy(deviceTop = null, startVariants = emptyMap())
    }

    /** Remember declared exercise variants across routines; these are never sensor validation. */
    internal fun preparationAnswers(): Result<Map<String, String>> = synchronized(lock) {
        runCatching {
            val saved = prefs.getString("preparation-answers", null)
            val answers = if (saved == null) lastSelection().getOrThrow()?.startVariants.orEmpty() else variants(JSONObject(saved))
            val checked = answers.filterKeys { StartWristContext.expectedVariant(ExerciseMotionCatalog.normalizeExerciseId(it)) != null }
                .also { checked -> checked.forEach { (id, answer) -> require(answer in StartWristContext.variantsFor(id)) } }
            // Persist legacy choices before another routine can replace the old last-session fields.
            if (saved == null && checked.isNotEmpty()) {
                check(prefs.edit().putString("preparation-answers", JSONObject(checked).toString()).commit()) {
                    "Could not preserve previous preparation answers"
                }
            }
            checked
        }
    }

    internal fun savePreparationAnswer(exerciseId: String, answer: String): Result<Map<String, String>> = synchronized(lock) {
        runCatching {
            require(exerciseId == ExerciseMotionCatalog.normalizeExerciseId(exerciseId))
            require(answer in StartWristContext.variantsFor(exerciseId))
            val updated = preparationAnswers().getOrThrow() + (exerciseId to answer)
            check(prefs.edit().putString("preparation-answers", JSONObject(updated).toString()).commit()) {
                "Could not save preparation answer"
            }
            updated
        }
    }

    /** New local facts belong to the workout that captured them, not to older records. */
    internal fun frozenBodyProfile(sessionId: String, legacyProfile: WorkoutProfile?): Result<WorkoutProfile?> = synchronized(lock) {
        runCatching {
            val json = read(sessionId)
            if (json == null || !json.has("deviceBodyProfile")) legacyProfile
            else WorkoutProfile.fromJson(json.getJSONObject("deviceBodyProfile"))
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
                    .put("startDirectionSnapshot", direction?.let(StartDirectionRuntimeCodec::encode).orEmpty())
                    .put("tempoSettings", if (ExerciseTrackingProfiles.resolve(normalized).mode == TrackingMode.MANUAL_REQUIRED) "" else
                        WorkoutTempoSettingsCodec.encode(WorkoutTempoSettingsStore(context).load(normalized).getOrDefault(WorkoutTempoSettings(normalized)))))
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
            val withDirection = if (snapshot.isEmpty()) withContext else WearSync.withStartDirectionProfile(withContext, StartDirectionRuntimeCodec.decode(snapshot))
            val tempo = frozen.optString("tempoSettings")
            require(fields.getOrNull(21).isNullOrEmpty() || fields[21] == tempo) { "Set tempo settings changed" }
            if (tempo.isEmpty()) withDirection else WearSync.withTempoSettings(withDirection, WorkoutTempoSettingsCodec.decode(tempo))
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
