package dev.kyungmin.kcoach.core

/** Missing legacy metadata is unknown; explicitly unusable acceleration is never silently reliable. */
object SensorRecordQuality {
    fun hasAccelerationFault(record: SensorCaptureRecord): Boolean {
        if (record.accelerationAccuracy == 0) return true
        val accelerationNs = record.accelerationTimestampNs ?: return false
        val gyroNs = record.sample.timestampNs
        return accelerationNs < 0 || accelerationNs > gyroNs || gyroNs - accelerationNs > 40_000_000L
    }
}
