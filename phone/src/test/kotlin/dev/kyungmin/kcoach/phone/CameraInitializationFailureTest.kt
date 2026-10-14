package dev.kyungmin.kcoach.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraInitializationFailureTest {
    @Test fun nativeStartupCauseSurvivesTheGenericInitializerWrapperWithoutCredentials() {
        val cause = UnsatisfiedLinkError("No implementation found for Packet.create; bearer fixture-token")
        val error = ExceptionInInitializerError(cause)
        val app = ApplicationProvider.getApplicationContext<Context>()
        val text = CameraInitializationFailure.save(app, "POSE_INIT", error)
        val report = JSONObject(text)
        assertEquals("POSE_INIT", report.getString("phase"))
        assertEquals(2, report.getJSONArray("causes").length())
        assertEquals("java.lang.UnsatisfiedLinkError", report.getJSONArray("causes").getJSONObject(1).getString("type"))
        assertTrue(text.contains("Packet.create")); assertFalse(text.contains("fixture-token"))
        assertEquals(text, app.getSharedPreferences("camera-initialization", Context.MODE_PRIVATE).getString("last_error", null))
        assertFalse(report.has("image")); assertFalse(report.has("profile"))
    }
    @Test fun largeOrCyclicExceptionChainsAreBounded() {
        val first = RuntimeException("x".repeat(10_000))
        val second = RuntimeException("second", first)
        first.initCause(second)
        val report = JSONObject(CameraInitializationFailure.report("CAMERA_BIND", second))
        assertEquals(2, report.getJSONArray("causes").length())
        assertTrue(report.getJSONArray("causes").getJSONObject(1).getString("detail").length <= 500)
    }
}
