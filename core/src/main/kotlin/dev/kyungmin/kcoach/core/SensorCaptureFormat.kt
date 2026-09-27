package dev.kyungmin.kcoach.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** One synchronized motion/heart-rate record in a set capture. */
data class SensorCaptureRecord(
    val sample: SensorSample,
    val heartRateBpm: Int? = null,
)

data class SensorCaptureDocument(
    val formatVersion: Int,
    val sampleRateRequestedHz: Double,
    val records: List<SensorCaptureRecord>,
) {
    val sampleCount: Int get() = records.size
    val startMonotonicNs: Long? get() = records.firstOrNull()?.sample?.timestampNs
    val endMonotonicNs: Long? get() = records.lastOrNull()?.sample?.timestampNs

    fun validateTimestamps(): List<String> {
        val errors = mutableListOf<String>()
        records.zipWithNext().forEachIndexed { index, (previous, current) ->
            if (current.sample.timestampNs <= previous.sample.timestampNs) {
                errors += "timestamp[$index] is not strictly increasing"
            }
        }
        records.forEachIndexed { index, record ->
            val sample = record.sample
            if (
                !sample.accelerationX.isFinite() ||
                !sample.accelerationY.isFinite() ||
                !sample.accelerationZ.isFinite() ||
                !sample.gyroscopeX.isFinite() ||
                !sample.gyroscopeY.isFinite() ||
                !sample.gyroscopeZ.isFinite()
            ) {
                errors += "sensor[$index] contains a non-finite value"
            }
            if (record.heartRateBpm != null && record.heartRateBpm !in 20..240) {
                errors += "heartRate[$index] is outside 20..240 bpm"
            }
        }
        return errors
    }
}

/**
 * Dependency-free KCoach Sensor (KCS) binary format.
 *
 * Header:
 *   magic[4] = KCS1
 *   formatVersion: int
 *   recordCount: int
 *   requestedSampleRateHz: double
 *
 * Record (fixed 40 bytes):
 *   timestampNs: long
 *   acceleration x/y/z: float x 3
 *   gyroscope x/y/z: float x 3
 *   sensorAccuracy: int (-1 means unavailable)
 *   heartRateBpm: int (-1 means unavailable)
 */
object SensorCaptureFormat {
    private val MAGIC = byteArrayOf('K'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
    const val FORMAT_VERSION = 1
    private const val RECORD_BYTES = 40

    fun write(
        output: OutputStream,
        records: List<SensorCaptureRecord>,
        sampleRateRequestedHz: Double,
    ) {
        require(sampleRateRequestedHz > 0.0) { "Requested sample rate must be positive" }
        require(records.size <= Int.MAX_VALUE) { "Too many capture records" }
        val errors = SensorCaptureDocument(FORMAT_VERSION, sampleRateRequestedHz, records).validateTimestamps()
        require(errors.isEmpty()) { errors.joinToString("; ") }

        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeInt(FORMAT_VERSION)
            data.writeInt(records.size)
            data.writeDouble(sampleRateRequestedHz)
            records.forEach { record ->
                val sample = record.sample
                data.writeLong(sample.timestampNs)
                data.writeFloat(sample.accelerationX)
                data.writeFloat(sample.accelerationY)
                data.writeFloat(sample.accelerationZ)
                data.writeFloat(sample.gyroscopeX)
                data.writeFloat(sample.gyroscopeY)
                data.writeFloat(sample.gyroscopeZ)
                data.writeInt(sample.sensorAccuracy ?: -1)
                data.writeInt(record.heartRateBpm ?: -1)
            }
        }
    }

    fun read(input: InputStream): SensorCaptureDocument {
        DataInputStream(input).use { data ->
            val magic = ByteArray(MAGIC.size)
            data.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "Invalid KCS capture magic" }
            val version = data.readInt()
            require(version == FORMAT_VERSION) { "Unsupported KCS format version: $version" }
            val recordCount = data.readInt()
            require(recordCount >= 0) { "Negative KCS record count" }
            val sampleRate = data.readDouble()
            require(sampleRate > 0.0) { "Invalid KCS sample rate" }
            require(recordCount.toLong() * RECORD_BYTES <= Int.MAX_VALUE) { "KCS capture is too large" }

            val records = ArrayList<SensorCaptureRecord>(recordCount)
            repeat(recordCount) {
                try {
                    val sample = SensorSample(
                        timestampNs = data.readLong(),
                        accelerationX = data.readFloat(),
                        accelerationY = data.readFloat(),
                        accelerationZ = data.readFloat(),
                        gyroscopeX = data.readFloat(),
                        gyroscopeY = data.readFloat(),
                        gyroscopeZ = data.readFloat(),
                        sensorAccuracy = data.readInt().takeUnless { it < 0 },
                    )
                    val heartRate = data.readInt().takeUnless { it < 0 }
                    records += SensorCaptureRecord(sample, heartRate)
                } catch (error: EOFException) {
                    throw IllegalArgumentException("Truncated KCS capture at record $it", error)
                }
            }
            require(data.read() == -1) { "Trailing bytes in KCS capture" }
            return SensorCaptureDocument(version, sampleRate, records).also { document ->
                require(document.validateTimestamps().isEmpty()) {
                    document.validateTimestamps().joinToString("; ")
                }
            }
        }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
