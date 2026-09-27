package dev.kyungmin.kcoach.core

import kotlin.math.max

data class EnergyEstimate(
    val grossKilocalories: Double,
    val activeKilocalories: Double,
    val averageMet: Double,
    val confidence: Double,
    val notes: List<String>,
)

/**
 * ACSM steady-state walking equation:
 * VO2 (ml/kg/min) = 0.1 * speed(m/min) + 1.8 * speed * grade + 3.5.
 */
class InclineEnergyEstimator {
    fun estimateWalking(
        weightKg: Double,
        speedKmh: Double,
        gradePercent: Double,
        durationMinutes: Double,
        averageHeartRate: Int? = null,
        expectedHeartRate: Int? = null,
        armSwingRatio: Double? = null,
    ): EnergyEstimate {
        require(weightKg > 0)
        require(speedKmh > 0)
        require(gradePercent in 0.0..40.0)
        require(durationMinutes > 0)
        require(armSwingRatio == null || armSwingRatio in 0.0..1.0)

        val speedMetersPerMinute = speedKmh * 1000.0 / 60.0
        val grade = gradePercent / 100.0
        val predictedVo2 = 0.1 * speedMetersPerMinute +
            1.8 * speedMetersPerMinute * grade +
            3.5

        val heartRateFactor = when {
            averageHeartRate == null || expectedHeartRate == null || expectedHeartRate <= 0 -> 1.0
            else -> (averageHeartRate.toDouble() / expectedHeartRate).coerceIn(0.85, 1.15)
        }

        // Reduced arm swing can indicate rail support, but it is not proof. Keep correction conservative.
        val railSupportFactor = armSwingRatio?.let { ratio ->
            if (ratio < 0.25) 0.92 else 1.0
        } ?: 1.0

        val adjustedVo2 = predictedVo2 * heartRateFactor * railSupportFactor
        val grossPerMinute = adjustedVo2 * weightKg / 1000.0 * 5.0
        val restingPerMinute = 3.5 * weightKg / 1000.0 * 5.0
        val notes = buildList {
            add("경사도·속도·체중 기반 보행 대사식")
            if (averageHeartRate != null && expectedHeartRate != null) add("개인 심박 반응 보정")
            if (armSwingRatio != null && armSwingRatio < 0.25) add("손잡이 의존 가능성 보수적 감산")
        }

        return EnergyEstimate(
            grossKilocalories = grossPerMinute * durationMinutes,
            activeKilocalories = max(0.0, (grossPerMinute - restingPerMinute) * durationMinutes),
            averageMet = adjustedVo2 / 3.5,
            confidence = when {
                averageHeartRate != null && armSwingRatio != null -> 0.75
                averageHeartRate != null -> 0.68
                else -> 0.58
            },
            notes = notes,
        )
    }
}
