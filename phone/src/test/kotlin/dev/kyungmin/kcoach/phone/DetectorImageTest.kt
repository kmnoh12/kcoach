package dev.kyungmin.kcoach.phone

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetectorImageTest {
    private fun frame() = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    /** Pins the library behavior behind the "Can't call getPixels() on a recycled bitmap" capture crash. */
    @Test fun closingAnMpImageRecyclesTheBitmapItWraps() {
        val bitmap = frame()
        BitmapImageBuilder(bitmap).build().use { }
        assertTrue(bitmap.isRecycled)
    }

    @Test fun detectorCopyKeepsTheFrameUsableForVideoEncoding() {
        val bitmap = frame()
        var seen: Bitmap? = null
        bitmap.withDetectorImage { seen = com.google.mediapipe.framework.image.BitmapExtractor.extract(it) }
        assertFalse(bitmap.isRecycled)
        assertNotSame(bitmap, seen)
        bitmap.getPixels(IntArray(64), 0, 8, 0, 0, 8, 8)
    }

    @Test fun detectorFailureStillLeavesTheFrameUsable() {
        val bitmap = frame()
        assertThrows(IllegalStateException::class.java) { bitmap.withDetectorImage<Unit> { error("detector failed") } }
        assertFalse(bitmap.isRecycled)
    }
}
