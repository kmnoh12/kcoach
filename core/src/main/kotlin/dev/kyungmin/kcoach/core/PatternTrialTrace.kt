package dev.kyungmin.kcoach.core

import java.io.*
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

enum class PatternTrialSource { WATCH_SENSOR_EVENT, EMULATED }
data class PatternTrialEvent(val cycle: RepCycle, val sourceEmissionNs: Long, val processedAtNs: Long, val distance: Double,
    val feedbackAtNs: Long? = null) {
    init {
        require(cycle.pauses.isEmpty() && sourceEmissionNs >= cycle.endNs && processedAtNs >= sourceEmissionNs)
        require(distance.isFinite() && distance >= 0)
        require(feedbackAtNs == null || feedbackAtNs >= processedAtNs)
    }
}

data class PatternTrialTrace(val runtime: PatternRuntimeProfile, val sessionId: String, val setId: String,
    val attemptId: String, val watchClockId: String, val epochVerified: Boolean, val source: PatternTrialSource,
    val sampleCalls: Long, val processingHistogram: List<Long>, val events: List<PatternTrialEvent>, val interrupted: Boolean) {
    init {
        require(listOf(sessionId, setId, attemptId, watchClockId).all { it.matches(Regex("[A-Za-z0-9._-]{1,160}")) })
        require(sampleCalls in 0..30_000 && processingHistogram.size == 64 && processingHistogram.all { it in 0..30_000 })
        require(processingHistogram.sum() == sampleCalls && events.size <= 800 && events.size <= sampleCalls)
        require(events.zipWithNext().all { (a, b) -> a.cycle.endNs <= b.cycle.startNs && a.processedAtNs <= b.processedAtNs })
    }
    /** Conservative histogram bucket upper bound; zero samples is unmeasured. */
    val processingP95UpperNs: Long? get() {
        if (sampleCalls == 0L) return null
        val rank = (sampleCalls * 95 + 99) / 100
        var seen = 0L
        processingHistogram.forEachIndexed { index, count ->
            seen += count
            if (seen >= rank) return when (index) { 0 -> 0L; 63 -> Long.MAX_VALUE; else -> (1L shl index) - 1 }
        }
        return null
    }
}

/** Bounded compressed sidecar carried with the immutable raw manifest; no raw sample is replaced. */
object PatternTrialTraceCodec {
    private const val MAGIC_V1 = 0x50545431
    private const val MAGIC_V2 = 0x50545432
    fun encode(trace: PatternTrialTrace, formatVersion: Int = 2): String {
        require(formatVersion in 1..2)
        require(formatVersion != 1 || trace.events.all { it.feedbackAtNs == null })
        val raw = ByteArrayOutputStream().apply { DataOutputStream(this).use { out ->
            out.writeInt(if (formatVersion == 1) MAGIC_V1 else MAGIC_V2)
            val runtime = PatternRuntimeCodec.encode(trace.runtime); out.writeInt(runtime.size); out.write(runtime)
            out.writeUTF(trace.sessionId); out.writeUTF(trace.setId); out.writeUTF(trace.attemptId); out.writeUTF(trace.watchClockId)
            out.writeBoolean(trace.epochVerified); out.writeUTF(trace.source.name); out.writeBoolean(trace.interrupted)
            out.writeLong(trace.sampleCalls); trace.processingHistogram.forEach(out::writeLong)
            out.writeInt(trace.events.size)
            trace.events.forEach { event ->
                val cycle = event.cycle
                out.writeLong(cycle.startNs); out.writeLong(cycle.turnaroundNs); out.writeLong(cycle.endNs)
                out.writeByte(cycle.departurePhase.ordinal); out.writeLong(event.sourceEmissionNs)
                out.writeLong(event.processedAtNs); out.writeDouble(event.distance)
                if (formatVersion == 2) out.writeLong(event.feedbackAtNs ?: -1L)
            }
        } }.toByteArray()
        require(raw.size <= 58_000)
        val compressed = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(raw) } }.toByteArray()
        require(compressed.size <= 45_000)
        return Base64.getEncoder().encodeToString(compressed)
    }

    fun decode(encoded: String): PatternTrialTrace {
        require(encoded.length <= 60_000)
        val bytes = Base64.getDecoder().decode(encoded); require(bytes.size <= 45_000)
        val raw = GZIPInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer); if (count < 0) break
                require(output.size() + count <= 58_000); output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            val magic = input.readInt().also { require(it == MAGIC_V1 || it == MAGIC_V2) }
            val size = input.readInt().also { require(it in 1..4096) }
            val runtimeBytes = ByteArray(size); input.readFully(runtimeBytes)
            val runtime = PatternRuntimeCodec.decode(runtimeBytes)
            val session = input.readUTF(); val set = input.readUTF(); val attempt = input.readUTF(); val clock = input.readUTF()
            val verified = input.readBoolean(); val source = PatternTrialSource.valueOf(input.readUTF()); val interrupted = input.readBoolean()
            val calls = input.readLong(); val histogram = List(64) { input.readLong() }
            val count = input.readInt().also { require(it in 0..800) }
            val events = List(count) {
                val start = input.readLong(); val turn = input.readLong(); val end = input.readLong()
                val phase = input.readUnsignedByte().also { require(it in MusclePhase.entries.indices) }
                val emitted = input.readLong(); val processed = input.readLong(); val distance = input.readDouble()
                val feedback = if (magic == MAGIC_V2) input.readLong().also { require(it >= -1) }.takeIf { it >= 0 } else null
                PatternTrialEvent(RepCycle(start, turn, end, MusclePhase.entries[phase]), emitted, processed, distance, feedback)
            }
            require(input.available() == 0)
            PatternTrialTrace(runtime, session, set, attempt, clock, verified, source, calls, histogram, events, interrupted)
        }
    }
}
