package dev.kyungmin.kcoach.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kyungmin.kcoach.core.MismatchCandidate
import dev.kyungmin.kcoach.core.MismatchSide
import dev.kyungmin.kcoach.core.RepSpan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MismatchAnswerStoreTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private val window = MismatchCandidate(RepSpan(1000, 3000), MismatchSide.VIDEO_ONLY)

    @Test fun anAnswerIsKeptPerWindowReplacedOnChangeAndNeverTouchesTheReviewLabels() {
        val captures = CalibrationCaptureStore(app)
        val capture = captures.begin("dumbbell_curl", "컬", true, "phone").getOrThrow()
        captures.finish(capture.id, false, "fixture").getOrThrow()
        val store = MismatchAnswerStore(captures)
        assertTrue(store.load(capture.id).isEmpty())
        store.save(capture.id, window, true).getOrThrow()
        store.save(capture.id, MismatchCandidate(RepSpan(5000, 7000), MismatchSide.WATCH_ONLY), false).getOrThrow()
        store.save(capture.id, window, false).getOrThrow()
        val answers = store.load(capture.id).associate { it.candidate to it.realRep }
        assertEquals(mapOf(window to false, MismatchCandidate(RepSpan(5000, 7000), MismatchSide.WATCH_ONLY) to false), answers)
        assertNull(CalibrationReviewStore(app).load(capture.id).getOrThrow().countReference)
    }

    @Test fun deletingTheCaptureDeletesItsAnswers() {
        val captures = CalibrationCaptureStore(app)
        val capture = captures.begin("dumbbell_curl", "컬", true, "phone").getOrThrow()
        captures.finish(capture.id, false, "fixture").getOrThrow()
        MismatchAnswerStore(captures).save(capture.id, window, true).getOrThrow()
        CalibrationCaptureDeletion(app).delete(listOf(capture.id))
        assertFalse(File(app.filesDir, "calibration-captures/${capture.id}").exists())
    }

    @Test fun onlyPlainJsonNamesInsideTheCaptureFolderAreAccepted() {
        val captures = CalibrationCaptureStore(app)
        assertTrue(runCatching { captures.userFile("paired-x", "../evil.json") }.isFailure)
    }
}
