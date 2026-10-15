package dev.kyungmin.kcoach.phone

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage

/**
 * MediaPipe's MPImage.close() recycles the bitmap it wraps. The frame bitmap is still needed afterwards
 * (reference video encoding, overlay size), so the detector gets its own copy and owns its lifetime.
 */
internal inline fun <T> Bitmap.withDetectorImage(block: (MPImage) -> T): T {
    val copy = requireNotNull(copy(config ?: Bitmap.Config.ARGB_8888, false)) { "Could not copy frame for the pose detector" }
    return try { BitmapImageBuilder(copy).build().use(block) } finally { if (!copy.isRecycled) copy.recycle() }
}
