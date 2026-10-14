package dev.kyungmin.kcoach.phone

import android.content.Context
import dev.kyungmin.kcoach.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.UUID

data class PatternBenchmarkEntry(val captureId: String, val rawSha256: String, val referenceDigest: String,
    val count: SetCountReference, val exerciseId: String, val dayId: String, val baselineSnapshot: String)
data class FrozenPatternBenchmark(val id: String, val compatibility: PatternCompatibility,
    val createdAtMs: Long, val entries: List<PatternBenchmarkEntry>, val sha256: String)

/** Immutable source/label pins. Originals remain in the capture inbox rather than being copied. */
class PatternBenchmarkStore(context: Context) {
    private val root = File(context.filesDir, "pattern-benchmarks")
    private val owner = PatternCandidateStore(context).localUserId()

    fun freeze(sets: List<HeldOutPatternSet>): Result<FrozenPatternBenchmark> = synchronized(lock) {
        runCatching {
            require(sets.isNotEmpty() && sets.size <= 500)
            val key = sets.first().set.compatibility
            require(key.localUserId == owner && sets.all { it.set.compatibility == key })
            require(sets.map { it.set.captureId }.distinct().size == sets.size && sets.map { it.set.rawSha256 }.distinct().size == sets.size)
            sets.forEach { held ->
                val raw = ByteArrayOutputStream().apply { SensorCaptureFormat.write(this, held.set.document.records,
                    held.set.document.sampleRateRequestedHz, held.set.document.formatVersion) }.toByteArray()
                require(SensorCaptureFormat.sha256(raw) == held.set.rawSha256)
            }
            val id = "benchmark-${UUID.randomUUID()}"
            val entries = sets.sortedBy { it.set.captureId }.map(::entry)
            val body = JSONObject().put("schemaVersion", 1).put("id", id).put("createdAtMs", System.currentTimeMillis())
                .put("compatibility", PatternCompatibilityCodec.encode(key)).put("entries", JSONArray(entries.map(::encodeEntry)))
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            val hash = SensorCaptureFormat.sha256(bytes)
            writeNew(file(id), JSONObject().put("sha256", hash).put("body", Base64.getEncoder().encodeToString(bytes)).toString().toByteArray())
            load(id).getOrThrow()
        }
    }

    fun load(id: String): Result<FrozenPatternBenchmark> = synchronized(lock) {
        runCatching {
            val file = file(id); require(file.length() in 1..2_000_000)
            val wrapper = JSONObject(file.readText())
            val bytes = Base64.getDecoder().decode(wrapper.getString("body")); val hash = wrapper.getString("sha256")
            require(SensorCaptureFormat.sha256(bytes) == hash)
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.getInt("schemaVersion") == 1 && json.getString("id") == id)
            val key = PatternCompatibilityCodec.decode(json.getString("compatibility")); require(key.localUserId == owner)
            val array = json.getJSONArray("entries"); require(array.length() in 1..500)
            val entries = (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                PatternBenchmarkEntry(row.getString("captureId"), row.getString("rawSha256"), row.getString("referenceDigest"),
                    SetCountReference(row.getString("setId"), row.getString("attemptId"), row.getInt("reps"), row.getLong("reviewedAtMs")),
                    row.getString("exerciseId"), row.getString("dayId"), row.getString("baselineSnapshot"))
            }
            require(entries.map { it.captureId }.distinct().size == entries.size)
            FrozenPatternBenchmark(id, key, json.getLong("createdAtMs"), entries, hash)
        }
    }

    fun list(key: PatternCompatibility): Result<List<FrozenPatternBenchmark>> = synchronized(lock) {
        runCatching { root.listFiles().orEmpty().filter { it.name.endsWith(".benchmark.json") }
            .map { load(it.name.removeSuffix(".benchmark.json")).getOrThrow() }.filter { it.compatibility == key }.sortedBy { it.createdAtMs } }
    }

    fun compare(id: String, candidate: PatternRuntimeProfile, previous: PatternRuntimeProfile?,
        current: List<HeldOutPatternSet>): Result<PatternRegressionReport> = synchronized(lock) {
        runCatching {
            val benchmark = load(id).getOrThrow()
            require(candidate.compatibility == benchmark.compatibility && current.all { it.set.compatibility == benchmark.compatibility })
            val rows = current.associateBy { it.set.captureId }
            require(rows.size == current.size && rows.keys == benchmark.entries.map { it.captureId }.toSet()) { "Fixed regression material missing or replaced" }
            val ordered = benchmark.entries.map { pinned ->
                val held = rows.getValue(pinned.captureId)
                require(entry(held) == pinned) { "Fixed regression original, labels or conditions changed" }
                held
            }
            PatternRegressionValidation.compare(candidate, previous, ordered).getOrThrow()
        }
    }

    private fun entry(held: HeldOutPatternSet) = PatternBenchmarkEntry(held.set.captureId, held.set.rawSha256,
        ReviewedPatternBuilder.referenceDigest(held.set.references), held.countReference, held.set.exerciseId, held.set.dayId,
        TrackingProfileSnapshot.encode(held.baselineProfile))
    private fun encodeEntry(row: PatternBenchmarkEntry) = JSONObject().put("captureId", row.captureId).put("rawSha256", row.rawSha256)
        .put("referenceDigest", row.referenceDigest).put("setId", row.count.setId).put("attemptId", row.count.attemptId)
        .put("reps", row.count.reps).put("reviewedAtMs", row.count.reviewedAtMs).put("exerciseId", row.exerciseId)
        .put("dayId", row.dayId).put("baselineSnapshot", row.baselineSnapshot)
    private fun file(id: String): File { require(id.matches(Regex("[A-Za-z0-9._-]{1,100}"))); return File(root, "$id.benchmark.json") }
    private fun writeNew(file: File, bytes: ByteArray) {
        check(root.mkdirs() || root.isDirectory); require(!file.exists())
        val temp = File(root, file.name + ".incoming")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }; check(temp.renameTo(file))
    }
    companion object { private val lock = Any() }
}
