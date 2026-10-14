package dev.kyungmin.kcoach.core

enum class ReferenceSource { REVIEWED_VIDEO, CAMERA_CANDIDATE, WATCH_CANDIDATE }

/** Human video annotations, never inferred repetitions or positive training examples. */
enum class NonRepReason(val label: String) {
    PREPARATION("준비 동작"), PARTIAL_REPETITION("불완전 반복 · 복귀 안 됨"),
    HOLD("중간에 멈춘 구간"), PUTTING_DOWN("중량 내려놓기"), OCCLUDED("가려져 확인 불가")
}

data class NonRepInterval(val startNs: Long, val endNs: Long, val reason: NonRepReason) {
    init { require(startNs >= 0 && endNs > startNs) }
}

data class RepPause(val startNs: Long, val endNs: Long) {
    init { require(startNs >= 0 && endNs > startNs) }
}

/** A cycle uses one source's monotonic clock. Cross-device conversion must preserve the source clock mapping. */
data class RepCycle(
    val startNs: Long,
    val turnaroundNs: Long,
    val endNs: Long,
    val departurePhase: MusclePhase,
    val pauses: List<RepPause> = emptyList(),
) {
    init {
        require(startNs >= 0 && startNs < turnaroundNs && turnaroundNs < endNs)
        require(pauses.all { it.startNs >= startNs && it.endNs <= endNs })
        require(pauses.zipWithNext().all { (a, b) -> a.endNs <= b.startNs })
    }
    val durationMs get() = (endNs - startNs) / 1_000_000L
    val pauseMs get() = pauses.sumOf { it.endNs - it.startNs } / 1_000_000L
    private val departureNs get() = turnaroundNs - startNs - pausedNs(startNs, turnaroundNs)
    private val returnNs get() = endNs - turnaroundNs - pausedNs(turnaroundNs, endNs)
    val concentricMs: Long? get() = when (departurePhase) {
        MusclePhase.CONCENTRIC -> departureNs / 1_000_000L
        MusclePhase.ECCENTRIC -> returnNs / 1_000_000L
        MusclePhase.UNKNOWN -> null
    }
    val eccentricMs: Long? get() = when (departurePhase) {
        MusclePhase.CONCENTRIC -> returnNs / 1_000_000L
        MusclePhase.ECCENTRIC -> departureNs / 1_000_000L
        MusclePhase.UNKNOWN -> null
    }
    fun split(firstTurnNs: Long, betweenNs: Long, secondTurnNs: Long): List<RepCycle> {
        fun within(from: Long, until: Long) = pauses.mapNotNull { pause ->
            val start = maxOf(from, pause.startNs); val end = minOf(until, pause.endNs)
            if (start < end) RepPause(start, end) else null
        }
        return listOf(RepCycle(startNs, firstTurnNs, betweenNs, departurePhase, within(startNs, betweenNs)),
            RepCycle(betweenNs, secondTurnNs, endNs, departurePhase, within(betweenNs, endNs)))
    }
    fun merge(next: RepCycle, turnNs: Long): RepCycle {
        require(endNs <= next.startNs && departurePhase == next.departurePhase)
        return RepCycle(startNs, turnNs, next.endNs, departurePhase, pauses + next.pauses)
    }
    private fun pausedNs(from: Long, until: Long): Long = pauses.sumOf {
        (minOf(it.endNs, until) - maxOf(it.startNs, from)).coerceAtLeast(0L)
    }
}

/** A count-only correction is deliberately not a repetition boundary or pose label. */
data class SetCountReference(val setId: String, val attemptId: String, val reps: Int, val reviewedAtMs: Long) {
    init { require(setId.isNotBlank() && attemptId.isNotBlank() && reps >= 0 && reviewedAtMs > 0) }
}

data class RepReference(
    val captureId: String,
    val setId: String,
    val attemptId: String,
    val cycle: RepCycle,
    val source: ReferenceSource,
    val alignmentUncertaintyNs: Long? = null,
    val alignmentVerified: Boolean = false,
    val sensorCoverageComplete: Boolean = false,
    val reviewedAtMs: Long? = null,
    val exclusionReason: String? = null,
    val revision: Long = 0L,
) {
    init {
        require(captureId.isNotBlank() && setId.isNotBlank() && attemptId.isNotBlank())
        require(alignmentUncertaintyNs == null || alignmentUncertaintyNs >= 0)
        require(reviewedAtMs == null || reviewedAtMs > 0)
        require(revision >= 0)
    }
    val eligibleForLearning: Boolean get() = source == ReferenceSource.REVIEWED_VIDEO &&
        reviewedAtMs != null && alignmentVerified && sensorCoverageComplete && exclusionReason == null &&
        alignmentUncertaintyNs != null && alignmentUncertaintyNs <= MAX_LABEL_ALIGNMENT_UNCERTAINTY_NS

    companion object {
        const val MAX_LABEL_ALIGNMENT_UNCERTAINTY_NS = 50_000_000L
    }
}
