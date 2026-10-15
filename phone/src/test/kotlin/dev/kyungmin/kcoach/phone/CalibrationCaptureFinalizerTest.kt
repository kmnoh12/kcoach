package dev.kyungmin.kcoach.phone

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import dev.kyungmin.kcoach.core.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RetentionFixtureExtractor::class])
class CalibrationCaptureFinalizerTest {
    @Test fun failedMetadataSaveKeepsTheStoppedAttemptAndRetryNeverRestartsOrRewritesItsVideo() {
        val app = RuntimeEnvironment.getApplication()
        val store = CalibrationCaptureStore(app)
        val clip = store.begin("dumbbell_curl", "컬", true, "phone").getOrThrow()
        store.videoFile(clip.id).writeText("existing interrupted video bytes")
        val finalizer = CalibrationCaptureFinalizer(store)
        finalizer.prepare(clip, false, "frame write failed")
        // An actual filesystem write failure at the final metadata boundary.
        val obstacle = app.filesDir.resolve("calibration-captures/${clip.id}/capture.json.incoming")
        assertTrue(obstacle.mkdir())
        assertTrue(finalizer.save().isFailure)
        assertEquals(clip, finalizer.pending?.capture)
        assertThrows(IllegalStateException::class.java) { finalizer.prepare(clip, true, "another attempt") }
        assertEquals(clip, store.active().getOrThrow())
        assertTrue(store.begin("external_rotation", "회전", true, "phone").isFailure)
        assertEquals("existing interrupted video bytes", store.videoFile(clip.id).readText())
        assertTrue(obstacle.delete())
        val saved = finalizer.save().getOrThrow()
        assertEquals(clip.id, saved.id)
        assertEquals("frame write failed", saved.interruptionReason)
        assertEquals(CalibrationCaptureStatus.INTERRUPTED, saved.status)
        assertNull(finalizer.pending)
        assertNull(store.active().getOrThrow())
        assertEquals("existing interrupted video bytes", store.videoFile(clip.id).readText())
        assertNotEquals(clip.id, store.begin("external_rotation", "회전", true, "phone").getOrThrow().id)
    }

    @Test fun metadataCommittedBeforeReceiptCleanupKeepsTheCompletedVideoAndClearsOnlyItsOwnActiveReceipt() {
        val app = RuntimeEnvironment.getApplication()
        val store = CalibrationCaptureStore(app)
        val clip = store.begin("dumbbell_curl", "컬", true, "phone").getOrThrow()
        val points = List(33) { CameraLandmark(.5, .4, .01, .9, .9) }
        repeat(2) { i ->
            val ns = 1_000_000_000L + i * 33_333_000L
            store.appendFrame(clip.id, CameraPoseFrame(i.toLong(), ns, ns + 1_000_000, ns / 1_000_000,
                ns + 2_000_000, i * 33_333L, 640, 480, 0, false, CameraTimestampSource.PHONE_ELAPSED_REALTIME,
                "phone", listOf(points), listOf(points), true)).getOrThrow()
        }
        store.videoFile(clip.id).writeText("synthetic-finalized-video")
        val saved = store.finish(clip.id, true).getOrThrow()
        assertTrue(saved.videoComplete)
        val prefs = app.getSharedPreferences("calibration-captures", 0)
        prefs.edit().putString("active", clip.id).commit()
        assertTrue(store.begin("external_rotation", "회전", true, "phone").isFailure)
        val finalizer = CalibrationCaptureFinalizer(store)
        finalizer.prepare(clip, false, "retry must not erase saved completion")
        assertEquals(saved, finalizer.save().getOrThrow())
        assertNull(prefs.getString("active", null))
        val another = store.begin("external_rotation", "회전", true, "phone").getOrThrow()
        assertEquals(saved, store.finish(clip.id, false, "late old stop").getOrThrow())
        assertEquals(another.id, prefs.getString("active", null))
        assertEquals(another, store.active().getOrThrow())
    }

    @Test fun performanceWriteFailureRetriesWithoutLosingTheAlreadyFinalizedAttempt() {
        val app = RuntimeEnvironment.getApplication()
        val store = CalibrationCaptureStore(app)
        val clip = store.begin("external_rotation", "외회전", true, "phone").getOrThrow()
        store.videoFile(clip.id).writeText("unchanged reference")
        val finalizer = CalibrationCaptureFinalizer(store)
        finalizer.prepare(clip, false, "interrupted fixture", performance = listOf("처리 8.5fps"))
        val obstacle = app.filesDir.resolve("calibration-captures/${clip.id}/performance.json.incoming")
        assertTrue(obstacle.mkdir())
        assertTrue(finalizer.save().isFailure)
        val saved = store.load(clip.id).getOrThrow()
        assertEquals(CalibrationCaptureStatus.INTERRUPTED, saved.status)
        assertNotNull(finalizer.pending)
        assertTrue(obstacle.delete())
        assertEquals(saved, finalizer.save().getOrThrow())
        assertNull(finalizer.pending)
        assertTrue(requireNotNull(store.performance(clip.id)).contains("8.5fps"))
        assertEquals("unchanged reference", store.videoFile(clip.id).readText())
    }

    @Test fun storageFullCauseGetsActionableCopyWhileOtherFailuresNeverPretendToBeDiskFull() {
        val errno = android.system.ErrnoException("write", android.system.OsConstants.ENOSPC)
        val wrapped = RuntimeException("finalization", errno)
        assertTrue(captureStorageFull(wrapped))
        assertTrue(captureSaveFailureText(wrapped).contains("저장 공간이 부족"))
        assertTrue(captureStorageFull(java.io.IOException("write failed: No space left on device")))
        val other = java.io.IOException("permission denied")
        assertFalse(captureStorageFull(other))
        assertFalse(captureSaveFailureText(other).contains("저장 공간이 부족"))
        assertFalse(captureStorageFull(OutOfMemoryError("image too large")))
    }
}
