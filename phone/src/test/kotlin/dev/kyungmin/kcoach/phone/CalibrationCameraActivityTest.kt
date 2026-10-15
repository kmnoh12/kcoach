package dev.kyungmin.kcoach.phone

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real activity wiring only (no camera frames); native pose and on-device behavior are not covered here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalibrationCameraActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun candidateWithUnusableWearingLeavesThroughBackInsteadOfSettings() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), CalibrationCameraActivity::class.java)
            .putExtra("exerciseId", "dumbbell_curl").putExtra("exerciseName", "덤벨 컬")
            .putExtra("watchOnLeft", true).putExtra("watchOrientation", "SIDEWAYS")
        ActivityScenario.launch<CalibrationCameraActivity>(intent).use { scenario ->
            compose.onNodeWithText("후보의 워치 착용 정보가 올바르지 않아요").assertExists()
            compose.onNodeWithText("뒤로 가서 후보 시험 다시 선택").performClick()
            var finishing = false
            scenario.onActivity { finishing = it.isFinishing }
            assertTrue(finishing)
        }
    }
}
