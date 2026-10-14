package dev.kyungmin.kcoach.core

enum class ExerciseKind {
    RESISTANCE,
    INCLINE_TREADMILL,
}

enum class RepSource {
    SAMSUNG_HEALTH_SERVICES,
    CUSTOM_IMU,
    MANUAL,
}

enum class WorkoutSessionStatus {
    IDLE,
    PREPARING,
    STARTING,
    WORKING,
    SET_COMPLETE_PENDING,
    RESTING,
    PAUSED,
    SYNC_PENDING,
    ANALYZING,
    COMPLETED,
    ABORTED,
    DISCONNECTED,
}

enum class CaptureSyncState {
    LOCAL_ONLY,
    PENDING_UPLOAD,
    UPLOADING,
    ACKED,
    FAILED,
}

data class SetPlan(
    val targetReps: Int,
    val weightKg: Double?,
    val restSeconds: Int,
    val warmup: Boolean = false,
)

data class ExercisePlan(
    val id: String,
    val name: String,
    val kind: ExerciseKind = ExerciseKind.RESISTANCE,
    val sets: List<SetPlan>,
    val repRange: IntRange,
)

data class RoutinePlan(
    val id: String,
    val name: String,
    val exercises: List<ExercisePlan>,
)

data class CompletedSet(
    val exerciseId: String,
    val setIndex: Int,
    val reps: Int,
    val weightKg: Double?,
    val rir: Double?,
    val averageHeartRate: Int?,
    val repSource: RepSource,
    val completedAtEpochMillis: Long,
)

data class HeartRateSnapshot(
    val currentBpm: Int,
    val peakBpm: Int?,
    val secondsSinceSet: Int,
)

data class SensorSample(
    val timestampNs: Long,
    val accelerationX: Float,
    val accelerationY: Float,
    val accelerationZ: Float,
    val gyroscopeX: Float,
    val gyroscopeY: Float,
    val gyroscopeZ: Float,
    val sensorAccuracy: Int? = null,
)

data class HeartRateSample(
    val timestampNs: Long,
    val bpm: Int,
    val source: String,
)

data class RepEvent(
    val repIndex: Int,
    val startTimestampNs: Long,
    val turnaroundTimestampNs: Long,
    val endTimestampNs: Long,
    val durationMs: Long,
    val concentricMs: Long?,
    val eccentricMs: Long?,
    val amplitude: Double,
    val peakAngularVelocity: Double,
    val confidence: Double,
)

data class SensorCaptureManifest(
    val captureId: String,
    val setId: String,
    val formatVersion: Int,
    val compression: String,
    val sampleCount: Int,
    val sampleRateRequestedHz: Double,
    val sampleRateObservedHz: Double,
    val startMonotonicNs: Long,
    val endMonotonicNs: Long,
    val sha256: String,
    val assetSizeBytes: Long,
    val syncState: CaptureSyncState = CaptureSyncState.LOCAL_ONLY,
)

data class SetAnalysisResult(
    val watchDetectedReps: Int?,
    val phoneReanalyzedReps: Int?,
    /** Algorithm-selected estimate eligible for recording, not user confirmation or proven accuracy. */
    val confirmedReps: Int?,
    val repSource: RepSource,
    val averageRepDurationMs: Double?,
    val tempoVariance: Double?,
    val amplitudeVariance: Double?,
    val stabilityScore: Int?,
    val consistencyScore: Int?,
    val lateSetDegradation: Double?,
    val averageHeartRate: Int?,
    val peakHeartRate: Int?,
    /** Heuristic analysis score, not a calibrated probability of a correct count. */
    val analysisConfidence: Double,
    val algorithmVersion: String,
    val trackingMode: TrackingMode = TrackingMode.WRIST_IMU_CAUTION,
    val trackingProfileId: String = "generic-wrist-v2",
    val trackingStatus: String = "검증 세트 필요",
    val averageAngularSpeed: Double? = null,
    /** Legacy threshold ratio. Retained for old-record decoding; new analysis leaves it null. */
    val relativeRom: Double? = null,
    /** Confidence of the wrist-observed repetition signal, not whole-body form. */
    val repConfidence: Double? = null,
    val capabilityTier: CapabilityTier = CapabilityTier.C_PERSONALIZED_AUTO_COUNT_BETA,
    val wristMotionSpeed: Double? = null,
    /** Legacy threshold ratio, never a joint-ROM percentage. */
    val wristMotionRange: Double? = null,
    val tempoConsistency: Int? = null,
    val signalQuality: Double? = null,
    val motionMetrics: ObservedMotionMetrics? = null,
)
