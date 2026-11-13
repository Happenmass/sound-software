package dev.soundsoftware.app.inference

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.soundsoftware.app.MainActivity
import dev.soundsoftware.app.R
import dev.soundsoftware.app.SoundApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AsrModelsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun modelsAndServicePickerShowOnlyCompatibleLocalNpuProfiles() {
        val activity = compose.activity
        val app = activity.application as SoundApplication
        val models = app.processor.localAsrProfiles()
        val expected = models.map { activity.getString(it.titleRes) }
        fun label(id: Int) = activity.getString(id)

        compose.onNodeWithContentDescription(label(R.string.settings)).performClick()
        if (expected.isEmpty()) {
            compose.onNodeWithText(label(R.string.no_local_npu_models)).performScrollTo().assertIsDisplayed()
        } else {
            for (name in expected) compose.onNodeWithText(name).performScrollTo().assertIsDisplayed()
            val installed = models.count { app.processor.modelReady(it.id) }
            assertEquals(installed, compose.onAllNodesWithText(label(R.string.installed)).fetchSemanticsNodes().size)
            assertEquals(models.size - installed, compose.onAllNodesWithText(label(R.string.not_installed)).fetchSemanticsNodes().size)
        }
        compose.onNodeWithText("Qwen3-ASR 0.6B").assertDoesNotExist()
        compose.onNodeWithText("Magic V6", substring = true).assertDoesNotExist()
        compose.onNodeWithText(app.modelDir.absolutePath, substring = true).assertDoesNotExist()
        compose.onNode(hasText(label(R.string.default_transcription_settings)) and hasClickAction()).performScrollTo().performClick()
        // The section label and the row can both be present. The row has the click action.
        compose.onNode(hasText(label(R.string.transcription_service)) and hasClickAction()).performScrollTo().performClick()
        val settingsRow = hasText(label(R.string.transcription_service)) and hasClickAction()
        for (title in listOf(R.string.asr_qwen_htp, R.string.asr_npu)) {
            val name = label(title)
            val option = compose.onNode(hasText(name) and hasClickAction() and !settingsRow)
            if (name in expected) option.assertExists() else option.assertDoesNotExist()
        }
        compose.onNodeWithText(label(R.string.asr_http)).assertIsDisplayed()
    }
}
