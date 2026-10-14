package dev.kyungmin.kcoach.core

import java.io.*
import java.util.Base64

enum class WorkoutTempoMetric { REPETITION, CONCENTRIC, ECCENTRIC }
data class WorkoutTempoRange(val minimumMs: Long, val maximumMs: Long) {
    init { require(minimumMs in 1..120_000 && maximumMs in minimumMs..120_000) }
}
/** User-selected ranges, separate from the repetition engine and physical posture assessment. */
data class WorkoutTempoSettings(val exerciseId: String, val enabled: Boolean = false,
    val targets: Map<WorkoutTempoMetric, WorkoutTempoRange> = emptyMap(), val consecutiveRepetitions: Int = 3,
    val minimumAlertIntervalMs: Long = 10_000) {
    init {
        require(exerciseId == ExerciseMotionCatalog.normalizeExerciseId(exerciseId) && ExerciseMotionCatalog.resolve(exerciseId) != null)
        require(ExerciseTrackingProfiles.resolve(exerciseId).mode != TrackingMode.MANUAL_REQUIRED)
        require(consecutiveRepetitions in 1..100 && minimumAlertIntervalMs in 1_000..120_000)
        require(!enabled || targets.isNotEmpty() && ExerciseTrackingProfiles.resolve(exerciseId).mode != TrackingMode.MANUAL_REQUIRED)
    }
    fun policyConfig(): TempoFeedbackPolicy.Config = TempoFeedbackPolicy.Config(enabled,
        targets.mapKeys { (metric, _) -> TempoFeedbackPolicy.Metric.valueOf(metric.name) }
            .mapValues { (_, range) -> TempoFeedbackPolicy.RangeMs(range.minimumMs, range.maximumMs) },
        consecutiveRepetitions, minimumAlertIntervalMs)
}
object WorkoutTempoSettingsCodec {
    fun encode(value: WorkoutTempoSettings): String = Base64.getEncoder().encodeToString(ByteArrayOutputStream().apply {
        DataOutputStream(this).use { out ->
            out.writeInt(0x57545331); out.writeUTF(value.exerciseId); out.writeBoolean(value.enabled)
            out.writeInt(value.consecutiveRepetitions); out.writeLong(value.minimumAlertIntervalMs)
            out.writeInt(value.targets.size)
            value.targets.toSortedMap(compareBy { it.ordinal }).forEach { (metric, range) ->
                out.writeInt(metric.ordinal); out.writeLong(range.minimumMs); out.writeLong(range.maximumMs)
            }
        }
    }.toByteArray()).also { require(it.length <= 2048) }
    fun decode(encoded: String): WorkoutTempoSettings {
        require(encoded.length in 1..2048)
        return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
            require(input.readInt() == 0x57545331)
            val exercise = input.readUTF(); val enabled = input.readBoolean(); val consecutive = input.readInt(); val interval = input.readLong()
            val count = input.readInt(); require(count in 0..3)
            val targets = linkedMapOf<WorkoutTempoMetric, WorkoutTempoRange>()
            repeat(count) {
                val ordinal = input.readInt(); require(ordinal in WorkoutTempoMetric.entries.indices)
                val metric = WorkoutTempoMetric.entries[ordinal]; require(metric !in targets)
                targets[metric] = WorkoutTempoRange(input.readLong(), input.readLong())
            }
            require(input.available() == 0)
            WorkoutTempoSettings(exercise, enabled, targets, consecutive, interval)
        }
    }
    fun hash(encoded: String): String {
        require(encode(decode(encoded)) == encoded)
        return SensorCaptureFormat.sha256(encoded.toByteArray())
    }
}
