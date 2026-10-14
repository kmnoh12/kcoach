package dev.kyungmin.kcoach.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlin.math.sqrt

/** Times describe an observed wrist cycle, not a verified whole-body movement. */
data class ObservedRepMotion(
    val startNs: Long, val turnaroundNs: Long, val endNs: Long,
    val departureMs: Double, val returnMs: Double,
    val concentricMs: Double?, val eccentricMs: Double?, val pauseMs: Double?,
    val angularTravelRad: Double, val averageAngularSpeedRadS: Double,
) {
    init {
        require(startNs >= 0 && startNs < turnaroundNs && turnaroundNs < endNs)
        require(listOfNotNull(departureMs, returnMs, concentricMs, eccentricMs, pauseMs, angularTravelRad, averageAngularSpeedRadS)
            .all { it.isFinite() && it >= 0 })
        require(departureMs <= (turnaroundNs - startNs) / 1e6 && returnMs <= (endNs - turnaroundNs) / 1e6)
    }
    val durationMs get() = (endNs - startNs) / 1_000_000.0
}

data class ObservedMotionMetrics(val source: String, val reps: List<ObservedRepMotion>,
    val wristMotionComparison: WristMotionComparison? = null) {
    init {
        require(source.matches(Regex("[A-Z_]{1,64}")) && reps.size in 1..600)
        require(reps.zipWithNext().all { (a, b) -> a.endNs <= b.startNs })
        require(wristMotionComparison == null || source == "PERSONAL_WRIST_CANDIDATE" && wristMotionComparison.repetitionRatios.size == reps.size)
    }
    val averageCycleMs get() = reps.map { it.durationMs }.average()
    val averageDepartureMs get() = reps.map { it.departureMs }.average()
    val averageReturnMs get() = reps.map { it.returnMs }.average()
    val averageConcentricMs get() = averageComplete { it.concentricMs }
    val averageEccentricMs get() = averageComplete { it.eccentricMs }
    val averagePauseMs get() = averageComplete { it.pauseMs }
    val averageAngularSpeedRadS get() = reps.sumOf { it.angularTravelRad } / (reps.sumOf { it.durationMs } / 1000.0)
    val tempoCoefficientOfVariation: Double? get() {
        if (reps.size < 2) return null
        val mean = averageCycleMs
        return sqrt(reps.map { (it.durationMs - mean) * (it.durationMs - mean) }.average()) / mean
    }
    /** A descriptive duration consistency score, never an accuracy or posture score. */
    val tempoConsistency get() = tempoCoefficientOfVariation?.let { (100 * (1 - it)).toInt().coerceIn(0, 100) }
    val lateCycleChangeRatio: Double? get() {
        if (reps.size < 4) return null
        val split = reps.size / 2
        val first = reps.take(split).map { it.durationMs }.average()
        val last = reps.drop(split).map { it.durationMs }.average()
        return (last - first) / first
    }
    /** Change in integrated wrist gyro magnitude, separate from elapsed repetition time. */
    val lateWristAngularTravelChangeRatio: Double? get() {
        if (reps.size < 4) return null
        val split = reps.size / 2
        val first = reps.take(split).map { it.angularTravelRad }.average()
        if (first <= 0) return null
        val last = reps.drop(split).map { it.angularTravelRad }.average()
        return ((last - first) / first).takeIf { it.isFinite() }
    }
    private fun averageComplete(value: (ObservedRepMotion) -> Double?): Double? =
        reps.map(value).takeIf { values -> values.all { it != null } }?.map { requireNotNull(it) }?.average()
}

object ObservedMotionAnalyzer {
    /** Unknown pauses stay unknown; a plain turnaround does not prove a zero-duration pause. */
    fun analyze(document: SensorCaptureDocument, cycles: List<RepCycle>, source: String,
        verifiedPauseLabels: Boolean = false): ObservedMotionMetrics? {
        if (cycles.isEmpty() || cycles.size > 600 || !source.matches(Regex("[A-Z_]{1,64}")) ||
            !document.sampleRateRequestedHz.isFinite() || document.sampleRateRequestedHz <= 0 ||
            document.validateTimestamps().isNotEmpty() || document.records.size !in 3..30_000) return null
        val records = document.records
        val coverage = (records.size - 1) * 1e9 / document.sampleRateRequestedHz /
            (records.last().sample.timestampNs - records.first().sample.timestampNs)
        if (coverage < .85) return null
        val maxGapNs = minOf(60_000_000.0, 2.5e9 / document.sampleRateRequestedHz)
        if (records.any { it.sample.sensorAccuracy == 0 || SensorRecordQuality.hasAccelerationFault(it) ||
                SignalAxis.entries.any { axis -> !axis.value(it.sample).isFinite() } } ||
            records.zipWithNext().any { (a, b) -> b.sample.timestampNs - a.sample.timestampNs > maxGapNs }) return null
        if (cycles.zipWithNext().any { (a, b) -> a.endNs > b.startNs }) return null
        fun rate(sample: SensorSample): Double = sqrt(sample.gyroscopeX.toDouble() * sample.gyroscopeX +
            sample.gyroscopeY.toDouble() * sample.gyroscopeY + sample.gyroscopeZ.toDouble() * sample.gyroscopeZ)
        val angularRates = records.map { rate(it.sample) }
        var intervalIndex = 0
        val reps = cycles.map { cycle ->
            if (cycle.startNs < records.first().sample.timestampNs || cycle.endNs > records.last().sample.timestampNs) return null
            // Clip each adjacent observed interval to the cycle. Never integrate a whole interval outside it.
            var travel = 0.0
            while (intervalIndex + 1 < records.size && records[intervalIndex + 1].sample.timestampNs <= cycle.startNs) intervalIndex++
            var current = intervalIndex
            while (current + 1 < records.size && records[current].sample.timestampNs < cycle.endNs) {
                val a = records[current].sample; val b = records[current + 1].sample
                val from = maxOf(a.timestampNs, cycle.startNs)
                val until = minOf(b.timestampNs, cycle.endNs)
                if (until > from) {
                    val span = (b.timestampNs - a.timestampNs).toDouble()
                    val ra = angularRates[current]; val rb = angularRates[current + 1]
                    val first = ra + (rb - ra) * (from - a.timestampNs) / span
                    val last = ra + (rb - ra) * (until - a.timestampNs) / span
                    travel += (first + last) * .5 * (until - from) / 1e9
                }
                if (b.timestampNs > cycle.endNs) break
                current++
            }
            intervalIndex = current
            val pauses = if (verifiedPauseLabels) cycle.pauses else emptyList()
            fun pauseWithin(from: Long, until: Long) = pauses.sumOf { (minOf(until, it.endNs) - maxOf(from, it.startNs)).coerceAtLeast(0) } / 1e6
            val departure = (cycle.turnaroundNs - cycle.startNs) / 1e6 - pauseWithin(cycle.startNs, cycle.turnaroundNs)
            val returning = (cycle.endNs - cycle.turnaroundNs) / 1e6 - pauseWithin(cycle.turnaroundNs, cycle.endNs)
            val concentric = when (cycle.departurePhase) { MusclePhase.CONCENTRIC -> departure; MusclePhase.ECCENTRIC -> returning; MusclePhase.UNKNOWN -> null }
            val eccentric = when (cycle.departurePhase) { MusclePhase.CONCENTRIC -> returning; MusclePhase.ECCENTRIC -> departure; MusclePhase.UNKNOWN -> null }
            ObservedRepMotion(cycle.startNs, cycle.turnaroundNs, cycle.endNs, departure, returning, concentric, eccentric,
                if (verifiedPauseLabels) pauses.sumOf { it.endNs - it.startNs } / 1e6 else null,
                travel, travel / ((cycle.endNs - cycle.startNs) / 1e9))
        }
        return ObservedMotionMetrics(source, reps)
    }
}

/** Durable versioned phone-local encoding; no raw waveform, image, or inferred joint ROM. */
object ObservedMotionMetricsCodec {
    fun encode(value: ObservedMotionMetrics): String {
        validate(value)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(2); out.writeUTF(value.source); out.writeInt(value.reps.size)
            value.reps.forEach { r ->
                out.writeLong(r.startNs); out.writeLong(r.turnaroundNs); out.writeLong(r.endNs)
                out.writeDouble(r.departureMs); out.writeDouble(r.returnMs)
                listOf(r.concentricMs, r.eccentricMs, r.pauseMs).forEach { v -> out.writeBoolean(v != null); if (v != null) out.writeDouble(v) }
                out.writeDouble(r.angularTravelRad); out.writeDouble(r.averageAngularSpeedRadS)
            }
            val comparison = value.wristMotionComparison
            out.writeBoolean(comparison != null)
            if (comparison != null) {
                out.writeUTF(comparison.candidateHash); out.writeInt(comparison.reviewedRepCount)
                out.writeDouble(comparison.baselineMedianAngularTravelRad); out.writeDouble(comparison.baselineMedianAbsoluteDeviationRad)
                out.writeDouble(comparison.medianCurrentToBaselineRatio); out.writeInt(comparison.repetitionRatios.size)
                comparison.repetitionRatios.forEach(out::writeDouble)
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
    fun decode(encoded: String): ObservedMotionMetrics {
        require(encoded.length <= 120_000)
        val input = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
        return input.use { data ->
            val version = data.readInt(); require(version in 1..2)
            val source = data.readUTF(); val size = data.readInt(); require(size in 1..600)
            fun nullable() = if (data.readBoolean()) data.readDouble() else null
            val reps = List(size) { ObservedRepMotion(data.readLong(), data.readLong(), data.readLong(),
                data.readDouble(), data.readDouble(), nullable(), nullable(), nullable(), data.readDouble(), data.readDouble()) }
            val comparison = if (version == 2 && data.readBoolean()) {
                val hash = data.readUTF(); val count = data.readInt(); val center = data.readDouble(); val dispersion = data.readDouble()
                val ratio = data.readDouble(); val ratioCount = data.readInt(); require(ratioCount == size)
                WristMotionComparison(hash, count, center, dispersion, ratio, List(ratioCount) { data.readDouble() })
            } else null
            require(data.available() == 0)
            ObservedMotionMetrics(source, reps, comparison).also(::validate)
        }
    }
    private fun validate(value: ObservedMotionMetrics) {
        require(value.source.matches(Regex("[A-Z_]{1,64}")) && value.reps.size in 1..600)
        require(value.reps.zipWithNext().all { (a, b) -> a.endNs <= b.startNs })
        value.reps.forEach { r ->
            require(r.startNs >= 0 && r.startNs < r.turnaroundNs && r.turnaroundNs < r.endNs)
            require(listOfNotNull(r.departureMs, r.returnMs, r.concentricMs, r.eccentricMs, r.pauseMs,
                r.angularTravelRad, r.averageAngularSpeedRadS).all { it.isFinite() && it >= 0 })
            require(r.departureMs <= (r.turnaroundNs - r.startNs) / 1e6 && r.returnMs <= (r.endNs - r.turnaroundNs) / 1e6)
        }
    }
}
