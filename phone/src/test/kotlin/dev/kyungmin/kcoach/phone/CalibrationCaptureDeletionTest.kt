package dev.kyungmin.kcoach.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalibrationCaptureDeletionTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun deletingAStoppedCaptureRemovesItsFolderAndListEntryButNeverTouchesOthers() {
        val captures = CalibrationCaptureStore(app)
        val first = captures.begin("dumbbell_curl", "컬 하나", true, "phone").getOrThrow()
        captures.finish(first.id, false, "fixture").getOrThrow()
        val second = captures.begin("dumbbell_curl", "컬 둘", true, "phone").getOrThrow()
        captures.finish(second.id, false, "fixture").getOrThrow()
        val outcome = CalibrationCaptureDeletion(app).delete(listOf(first.id))
        assertEquals(listOf(first.id), outcome.deleted); assertTrue(outcome.blocked.isEmpty())
        assertFalse(File(app.filesDir, "calibration-captures/${first.id}").exists())
        assertEquals(listOf(second.id), captures.list().map { it.id })
    }

    @Test fun aCaptureThatIsStillRecordingIsKeptWithAReason() {
        val captures = CalibrationCaptureStore(app)
        val recording = captures.begin("dumbbell_curl", "촬영 중", true, "phone").getOrThrow()
        val outcome = CalibrationCaptureDeletion(app).delete(listOf(recording.id))
        assertTrue(outcome.deleted.isEmpty())
        assertEquals("촬영 중이라", outcome.blocked[recording.id])
        assertTrue(File(app.filesDir, "calibration-captures/${recording.id}").isDirectory)
    }

    @Test fun anUnknownOrMalformedIdIsRefusedWithoutDeletingAnything() {
        val result = CalibrationCaptureStore(app).delete("../files")
        assertTrue(result.isFailure)
        assertTrue(app.filesDir.isDirectory)
    }
}
