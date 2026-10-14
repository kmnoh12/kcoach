package dev.kyungmin.kcoach.phone.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kyungmin.kcoach.phone.ChatGptConnectionUi
import dev.kyungmin.kcoach.phone.ChatGptModelOption
import dev.kyungmin.kcoach.phone.ChatGptModelSettingsUi
import dev.kyungmin.kcoach.phone.ChatGptReasoningOption
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
class SettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val levels = listOf("low", "medium", "high", "xhigh", "max", "ultra").map { ChatGptReasoningOption(it) }
    private val models = listOf(ChatGptModelOption("gpt-test", "GPT Test", levels, "low", true),
        ChatGptModelOption("future-model", "Future Model", listOf(ChatGptReasoningOption("minimal")), "minimal", true))
    private val connected = ChatGptConnectionUi(connected = true, planAllowed = true, accountLabel = "test@example.invalid")
    private val modelUi = ChatGptModelSettingsUi(loaded = true, models = models, selectedSlug = "gpt-test")

    @Test fun settingsContainsAccountAndModelChoicesAndOpensAccount() {
        var account = false
        compose.setContent { SettingsScreen(connected, modelUi, SettingsActions({}, { account = true }, {}, {}, {})) }
        compose.onNodeWithText("ChatGPT 계정 관리").performClick()
        assertTrue(account)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings_ai.png")
    }

    @Test fun allReturnedModelsAreSelectable() {
        var selected: String? = null
        compose.setContent { SettingsScreen(connected, modelUi, SettingsActions({}, {}, {}, { selected = it }, {})) }
        compose.onNodeWithText("GPT Test").performClick()
        compose.onNode(isRoot() and hasAnyDescendant(hasText("작업 모델 선택")))
            .captureRoboImage("build/outputs/roborazzi/settings_models.png")
        compose.onNodeWithText("Future Model").performClick()
        assertEquals("future-model", selected)
    }

    @Test fun allReturnedReasoningEffortsIncludingUltraAreSelectable() {
        var selected: String? = null
        compose.setContent { SettingsScreen(connected, modelUi, SettingsActions({}, {}, {}, {}, { selected = it })) }
        compose.onNodeWithText("모델 기본값 · 낮음").performClick()
        compose.onNode(isRoot() and hasAnyDescendant(hasText("추론 강도 선택")))
            .captureRoboImage("build/outputs/roborazzi/settings_reasoning.png")
        compose.onNodeWithText("울트라 · ultra").performClick()
        assertEquals("ultra", selected)
    }

    @Test fun signedOutAndMissingCapabilityMetadataNeverOfferInventedSettings() {
        val unavailable = ChatGptModelSettingsUi(loaded = false)
        compose.setContent { SettingsScreen(ChatGptConnectionUi(), unavailable, SettingsActions({}, {}, {}, {}, {})) }
        compose.onNodeWithText("모델 선택").assertIsNotEnabled()
        compose.onNodeWithText("모델 기본값").assertIsNotEnabled()
    }

    @Test fun missingReasoningMetadataOffersOnlyModelDefault() {
        val unknown = ChatGptModelOption("unknown", "Unknown Model", emptyList(), null, false)
        compose.setContent {
            SettingsScreen(connected, ChatGptModelSettingsUi(loaded = true, models = listOf(unknown), selectedSlug = "unknown"),
                SettingsActions({}, {}, {}, {}, {}))
        }
        compose.onNodeWithText("모델 기본값").performClick()
        compose.onNodeWithText("높음 · high").assertDoesNotExist()
        compose.onNodeWithText("울트라 · ultra").assertDoesNotExist()
    }
}
