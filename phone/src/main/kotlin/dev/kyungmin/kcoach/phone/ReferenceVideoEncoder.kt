package dev.kyungmin.kcoach.phone

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File

/** Encode exactly the analyzed frames, using their original camera-derived PTS. */
internal class ReferenceVideoEncoder(private val output: File, private val width: Int, private val height: Int) {
    private val incoming = File(output.parentFile, output.name + ".incoming")
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(incoming.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var lastPtsUs = -1L
    private var writtenFrames = 0
    private var ended = false
    private var closed = false
    private val pixels = IntArray(width * height)

    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (error: Throwable) {
            runCatching { codec.release() }; runCatching { muxer.release() }
            throw error
        }
    }

    fun write(bitmap: Bitmap, presentationUs: Long): Boolean {
        check(!closed && !ended)
        require(bitmap.width == width && bitmap.height == height && presentationUs > lastPtsUs)
        drain(0)
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return false
        val image = codec.getInputImage(index) ?: error("Encoder does not expose writable YUV input")
        require(image.planes.size == 3)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        // Cache JNI-backed plane accessors once per frame, rather than for every pixel.
        val planes = image.planes
        val buffers = planes.map { it.buffer }
        val rowStrides = planes.map { it.rowStride }
        val pixelStrides = planes.map { it.pixelStride }
        fun put(plane: Int, x: Int, y: Int, value: Int) {
            buffers[plane].put(y * rowStrides[plane] + x * pixelStrides[plane], value.coerceIn(0, 255).toByte())
        }
        for (y in 0 until height) for (x in 0 until width) {
            val color = pixels[y * width + x]
            val r = (color shr 16) and 255; val g = (color shr 8) and 255; val b = color and 255
            put(0, x, y, ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
            if (x % 2 == 0 && y % 2 == 0) {
                val c1 = pixels[y * width + x + 1]; val c2 = pixels[(y + 1) * width + x]; val c3 = pixels[(y + 1) * width + x + 1]
                val rr = (((color shr 16) and 255) + ((c1 shr 16) and 255) + ((c2 shr 16) and 255) + ((c3 shr 16) and 255)) / 4
                val gg = (((color shr 8) and 255) + ((c1 shr 8) and 255) + ((c2 shr 8) and 255) + ((c3 shr 8) and 255)) / 4
                val bb = ((color and 255) + (c1 and 255) + (c2 and 255) + (c3 and 255)) / 4
                put(1, x / 2, y / 2, ((-38 * rr - 74 * gg + 112 * bb + 128) shr 8) + 128)
                put(2, x / 2, y / 2, ((112 * rr - 94 * gg - 18 * bb + 128) shr 8) + 128)
            }
        }
        image.close()
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, presentationUs, 0)
        lastPtsUs = presentationUs
        writtenFrames++
        drain(0)
        return true
    }

    fun finish(): Boolean {
        if (closed) return false
        try {
            if (writtenFrames == 0) return false
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var queuedEnd = false
            while (!queuedEnd && SystemClock.elapsedRealtime() < deadline) {
                drain(1_000)
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, lastPtsUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queuedEnd = true
                }
            }
            while (queuedEnd && !ended && SystemClock.elapsedRealtime() < deadline) drain(10_000)
            if (!ended || !muxerStarted) return false
            muxer.stop()
            muxerStarted = false
            require(incoming.length() > 0 && incoming.renameTo(output)) { "Reference video finalization failed" }
            return true
        } finally {
            closed = true
            runCatching { codec.stop() }; runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun drain(timeoutUs: Long) {
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted)
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start(); muxerStarted = true
                }
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted)
                            val buffer = requireNotNull(codec.getOutputBuffer(index))
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { ended = true; return }
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
        }
    }
}
