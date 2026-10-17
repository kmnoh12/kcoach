package dev.kyungmin.kcoach.phone

import dev.kyungmin.kcoach.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalibrationCaptureStoreTest {
    @Test fun declaredCameraConditionsSurviveMetadataReloadWhileLegacyClipsKeepAbsence() {
        val app = RuntimeEnvironment.getApplication(); val store = CalibrationCaptureStore(app)
        val declared = StartWristContext("external_rotation", PatternCandidateStore(app).localUserId(), "LEFT", "OUTSIDE", "HAND", "PALM_UP_HORIZONTAL")
        val clip = store.begin("external_rotation", "회전", true, "clock", "OUTSIDE", declared).getOrThrow()
        assertTrue(clip.id.startsWith("calibration-paired-"))
        assertEquals(CapturePurpose.PAIRED_REFERENCE, CapturePurpose.forSession(clip.id))
        assertEquals(declared, CalibrationCaptureStore(app).load(clip.id).getOrThrow().startWristContext)
        store.finish(clip.id, false, "synthetic fixture").getOrThrow()
        val file = app.filesDir.resolve("calibration-captures/${clip.id}/capture.json")
        val old = org.json.JSONObject(file.readText()).apply { remove("startWristContext") }
        file.writeText(old.toString())
        assertNull(CalibrationCaptureStore(app).load(clip.id).getOrThrow().startWristContext)
    }
    @Test fun mismatchedUserHandOrPlacementCannotBeRecordedAsTheCamerasStartConditions() {
        val app = RuntimeEnvironment.getApplication(); val store = CalibrationCaptureStore(app)
        val declared = StartWristContext("external_rotation", PatternCandidateStore(app).localUserId(), "LEFT", "OUTSIDE", "ELBOW", "PALM_UP_HORIZONTAL")
        assertTrue(store.begin("external_rotation", "회전", true, "clock", "OUTSIDE", declared.copy(owner = "someone-else")).isFailure)
        assertTrue(store.begin("external_rotation", "회전", false, "clock", "OUTSIDE", declared).isFailure)
        assertTrue(store.begin("external_rotation", "회전", true, "clock", "INSIDE", declared).isFailure)
        assertNull(store.active().getOrThrow())
    }
    @Test fun nonVideoBytesCannotBecomeACompletedReferenceAndPhoneRuntimeRemainsAliveDuringCapture() {
        val app = RuntimeEnvironment.getApplication()
        val store = CalibrationCaptureStore(app)
        val clip = store.begin("dumbbell_curl", "컬", true, "phone").getOrThrow()
        val controller = org.robolectric.Robolectric.buildService(PhoneWorkoutService::class.java).create()
        assertEquals(android.app.Service.START_STICKY, controller.get().onStartCommand(android.content.Intent(), 0, 1))
        store.videoFile(clip.id).writeText("not a video")
        val ended = store.finish(clip.id, true).getOrThrow()
        assertEquals(CalibrationCaptureStatus.INTERRUPTED, ended.status)
        assertFalse(ended.videoComplete)
        assertNull(store.active().getOrThrow())
        assertEquals(android.app.Service.START_NOT_STICKY, controller.get().onStartCommand(android.content.Intent(), 0, 2))
        controller.destroy()
    }

    @Test fun interruptedCaptureKeepsOriginalFramesAndNeverJoinsANewCameraClock() {
        val app = RuntimeEnvironment.getApplication()
        val store = CalibrationCaptureStore(app)
        val capture = store.begin("dumbbell_curl", "덤벨 컬", true, "phone-epoch").getOrThrow()
        val points = List(33) { CameraLandmark(0.5, 0.4, 0.01, 0.9, 0.8) }
        val world = List(33) { CameraLandmark(0.2, -0.3, 0.4, 0.9, 0.8) }
        val frame = CameraPoseFrame(0, 10_000_000, 11_000_000, 10, 12_000_000, 0, 640, 480, 90,
            true, CameraTimestampSource.PHONE_ELAPSED_REALTIME, "phone-epoch", listOf(points), listOf(world), true)
        assertTrue(store.appendFrame(capture.id, frame).isSuccess)
        assertFalse(store.appendFrame(capture.id, frame.copy(frameIndex = 1, phoneClockId = "phone-after-boot")).isSuccess)
        assertFalse(store.begin("lateral_raise", "레이즈", true, "phone-epoch").isSuccess)
        assertTrue(CalibrationCaptureStore(app).recoverInterrupted().isSuccess)
        val recreated = CalibrationCaptureStore(app)
        assertEquals(CalibrationCaptureStatus.INTERRUPTED, recreated.load(capture.id).getOrThrow().status)
        assertEquals(listOf(frame), recreated.frames(capture.id).getOrThrow())
        val next = recreated.begin("lateral_raise", "레이즈", true, "phone-after-boot").getOrThrow()
        assertNotEquals(capture.id, next.id)
        assertTrue(recreated.frames(next.id).getOrThrow().isEmpty())
    }
}
