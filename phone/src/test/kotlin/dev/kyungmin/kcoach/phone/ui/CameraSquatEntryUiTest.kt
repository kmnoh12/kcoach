package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class CameraSquatEntryUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun wristManualPolicyDoesNotDisableTheSeparateFullBodyCameraEntry() {
        var opened = false
        compose.setContent {
            CalibrationScreen(CalibrationUi("스쿼트", "워치 단독 수동", false, "전신을 촬영해요", true, null,
                emptyList(), cameraObservationAvailable = true), { false }, { CalibrationButtonUi("전신 촬영", true) },
                CalibrationActions({ opened = true }, {}, {}))
        }
        compose.onNodeWithText("전신 촬영").assertIsEnabled().performClick()
        assertTrue(opened)
    }
}
