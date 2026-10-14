package dev.kyungmin.kcoach.phone

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

data class ReferenceVideoEvidence(val presentationTimesUs: List<Long>, val width: Int, val height: Int) {
    companion object {
        fun inspect(file: File): Result<ReferenceVideoEvidence> = runCatching {
            require(file.isFile && file.length() in 1..100_000_000L)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
                } ?: error("No reference video track")
                val format = extractor.getTrackFormat(track)
                extractor.selectTrack(track)
                val times = mutableListOf<Long>()
                while (extractor.sampleTime >= 0) {
                    require(times.size < 6_000)
                    times.add(extractor.sampleTime)
                    if (!extractor.advance()) break
                }
                require(times.isNotEmpty() && times.zipWithNext().all { (a, b) -> b > a })
                ReferenceVideoEvidence(times, format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
            } finally { extractor.release() }
        }
    }
}
