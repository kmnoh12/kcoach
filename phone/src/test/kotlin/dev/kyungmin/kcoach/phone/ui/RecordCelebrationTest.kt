package dev.kyungmin.kcoach.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class RecordCelebrationTest {
    @get:Rule val compose = createComposeRule()

    private val max = RecordUi("벤치 프레스", RecordTier.MAX_WEIGHT, "85kg", "이전 80kg · +5kg")
    private val reps = RecordUi("벤치 프레스", RecordTier.REPS, "80kg 6회", "이 무게 최고 횟수 · 이전 5회")
    private val estimate = RecordUi("벤치 프레스", RecordTier.ESTIMATE, "추정 1RM 101 → 103kg", "")

    @Test fun eachTierHasItsOwnWeightOnTheResultList() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Column(androidx.compose.ui.Modifier.background(KColors.Bg).padding(20.dp)) { RecordList(listOf(estimate, reps, max)) }
        }
        compose.onNodeWithText("최고 중량 갱신 · 벤치 프레스").assertExists()
        compose.onNodeWithText("85kg").assertExists()
        compose.onNodeWithText("이전 80kg · +5kg").assertExists()
        compose.onNodeWithText("벤치 프레스 · 80kg 6회").assertExists()
        compose.onNodeWithText("이 무게 최고 횟수 · 이전 5회").assertExists()
        compose.onNodeWithText("벤치 프레스 · 추정 1RM 101 → 103kg").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/record-list.png")
    }

    @Test fun theBannerShowsRightAfterASetAndGoesAwayByItself() {
        compose.mainClock.autoAdvance = false
        compose.setContent { RecordBanner(RecordBannerUi(1, max)) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("85kg").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/record-banner.png")
        compose.mainClock.advanceTimeBy(5_500)
        compose.waitForIdle()
        compose.onNodeWithText("85kg").assertDoesNotExist()
    }

    @Test fun aNewRecordShowsAgainAfterTheOldOneWentAway() {
        compose.mainClock.autoAdvance = false
        val banner = androidx.compose.runtime.mutableStateOf<RecordBannerUi?>(RecordBannerUi(1, reps))
        compose.setContent { RecordBanner(banner.value) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("벤치 프레스 · 80kg 6회").assertExists()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithText("벤치 프레스 · 80kg 6회").assertDoesNotExist()
        compose.runOnUiThread {
            banner.value = RecordBannerUi(2, max)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("85kg").assertExists()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithText("85kg").assertDoesNotExist()
    }

    @Test fun noBannerMeansNothingOnScreen() {
        compose.setContent { RecordBanner(null) }
        compose.onAllNodes(hasText("갱신", substring = true)).assertCountEquals(0)
    }
}
