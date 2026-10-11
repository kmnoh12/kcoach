package dev.kyungmin.kcoach.core

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Binary frames for the measurement probe stream (watch ⇄ phone over a
 * Data Layer channel). Timestamps are each device's elapsedRealtimeNanos;
 * PING/PONG lets the phone estimate the clock offset and round trip.
 */
object ProbeWire {
    const val CHANNEL_PATH = "/kcoach/probe/stream"
    const val SENSOR_ACCEL = 0
    const val SENSOR_GYRO = 1

    private const val FRAME_SAMPLES = 1
    private const val FRAME_PING = 2
    private const val FRAME_PONG = 3

    data class Sample(val sensor: Int, val timestampNanos: Long, val x: Float, val y: Float, val z: Float)

    sealed interface Frame
    data class Samples(
        val seq: Int,
        val sentAtNanos: Long,
        val screenOn: Boolean,
        val samples: List<Sample>,
    ) : Frame
    data class Ping(val phoneNanos: Long) : Frame
    data class Pong(val phoneNanos: Long, val watchNanos: Long) : Frame

    fun write(out: DataOutputStream, frame: Frame) {
        when (frame) {
            is Samples -> {
                out.writeByte(FRAME_SAMPLES)
                out.writeInt(frame.seq)
                out.writeLong(frame.sentAtNanos)
                out.writeBoolean(frame.screenOn)
                out.writeShort(frame.samples.size)
                frame.samples.forEach {
                    out.writeByte(it.sensor)
                    out.writeLong(it.timestampNanos)
                    out.writeFloat(it.x)
                    out.writeFloat(it.y)
                    out.writeFloat(it.z)
                }
            }
            is Ping -> {
                out.writeByte(FRAME_PING)
                out.writeLong(frame.phoneNanos)
            }
            is Pong -> {
                out.writeByte(FRAME_PONG)
                out.writeLong(frame.phoneNanos)
                out.writeLong(frame.watchNanos)
            }
        }
    }

    /** Reads one frame; throws EOFException when the stream closes. */
    fun read(input: DataInputStream): Frame = when (val type = input.readUnsignedByte()) {
        FRAME_SAMPLES -> {
            val seq = input.readInt()
            val sentAt = input.readLong()
            val screenOn = input.readBoolean()
            val count = input.readUnsignedShort()
            Samples(seq, sentAt, screenOn, List(count) {
                Sample(input.readUnsignedByte(), input.readLong(), input.readFloat(), input.readFloat(), input.readFloat())
            })
        }
        FRAME_PING -> Ping(input.readLong())
        FRAME_PONG -> Pong(input.readLong(), input.readLong())
        else -> throw IllegalStateException("unknown probe frame $type")
    }
}
