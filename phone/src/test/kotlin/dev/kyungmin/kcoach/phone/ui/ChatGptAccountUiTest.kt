package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kyungmin.kcoach.phone.ChatGptConnectionUi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class ChatGptAccountUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun menuSettingsButtonCallsSettingsAction() {
        var opened = false
        compose.setContent { MenuScreen("0.7.3", MenuActions({}, { null }, {}, { opened = true })) }
        compose.onNodeWithContentDescription("설정").performClick()
        assertTrue(opened)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/menu_settings.png")
    }

    @Test fun signInPermissionAndCancelAreSeparateActions() {
        var requestedPlan: Boolean? = null
        val actions = ChatGptAccountActions({}, { plan, _ -> requestedPlan = plan }, {}, {}, {}, {}, {}, {})
        compose.setContent { ChatGptAccountScreen(ChatGptConnectionUi(), actions) }
        compose.onNodeWithText("연결할 때 구독 한도 사용도 요청").performClick()
        compose.onNodeWithText("Continue with ChatGPT").performClick()
        assertEquals(false, requestedPlan)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chatgpt_signed_out.png")
    }

    @Test fun pendingSignInDisablesDuplicateLoginAndOffersCancel() {
        var cancelled = false
        val actions = ChatGptAccountActions({}, { _, _ -> fail("Duplicate login") }, { cancelled = true }, {}, {}, {}, {}, {})
        compose.setContent { ChatGptAccountScreen(ChatGptConnectionUi(busy = true, signingIn = true), actions) }
        compose.onNodeWithText("Continue with ChatGPT").assertIsNotEnabled()
        compose.onNodeWithText("로그인 취소").assertIsEnabled().performClick()
        assertTrue(cancelled)
    }

    @Test fun identityOnlyDoesNotClaimPlanPermissionAndUsageLinkWorks() {
        var managed = false
        val actions = ChatGptAccountActions({}, { _, _ -> }, {}, {}, {}, { managed = true }, {}, {})
        compose.setContent {
            ChatGptAccountScreen(ChatGptConnectionUi(connected = true, accountLabel = "test@example.invalid", planAllowed = false), actions)
        }
        compose.onNodeWithText("허용되지 않음").assertExists()
        compose.onNodeWithText("ChatGPT 사용량 관리").performScrollTo().performClick()
        assertTrue(managed)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chatgpt_identity_only.png")
    }
}
