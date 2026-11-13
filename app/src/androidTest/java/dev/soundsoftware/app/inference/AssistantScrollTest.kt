package dev.soundsoftware.app.inference

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.soundsoftware.app.MainActivity
import dev.soundsoftware.app.R
import dev.soundsoftware.app.SoundApplication
import dev.soundsoftware.app.data.LlmSettings
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class AssistantScrollTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun streamingFollowsLatestUntilSwipedAndResumesOnJump() {
        val activity = compose.activity
        val app = activity.application as SoundApplication
        assumeTrue(app.packageName.endsWith(".pipoc"))
        compose.waitUntil(10_000) { app.assistant.state.value.ready }
        val previous = app.settings.state.value.llm
        val opening = (1..65).joinToString("\n", postfix = "\n") { "Line $it of the streaming answer." }
        val paused = (66..80).joinToString("\n", postfix = "\n") { "Line $it of the streaming answer." }
        val resumed = (81..95).joinToString("\n", postfix = "\n") { "Line $it of the streaming answer." }
        val answer = opening + paused + resumed + "End of the streaming answer."

        MockWebServer().use { server ->
            val body = listOf(
                chunk("{\"found\":true,\"answer\":\"" + JsonPrimitive(opening).toString().drop(1).dropLast(1)),
                chunk(JsonPrimitive(paused).toString().drop(1).dropLast(1)),
                chunk(JsonPrimitive(resumed).toString().drop(1).dropLast(1)),
                chunk("End of the streaming answer.\",\"citations\":[]}", finish = "stop") + "data: [DONE]\n\n",
            )
            // One complete SSE frame per throttled block, with enough time to scroll after the first one.
            val blockSize = body.maxOf { it.length } + 3
            val padded = body.joinToString("") { frame -> frame + ":" + " ".repeat(blockSize - frame.length - 3) + "\n\n" }
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(padded).throttleBody(blockSize.toLong(), 3, TimeUnit.SECONDS))
            server.start()
            app.settings.update { it.copy(llm = LlmSettings("http://localhost:${server.port}/v1", "fixture")) }
            try {
                val assistant = activity.getString(R.string.recording_assistant)
                compose.onAllNodes(hasClickAction() and hasContentDescription(assistant)).onFirst().performClick()
                val placeholder = activity.getString(R.string.ask_recordings_placeholder)
                compose.onNode(hasSetTextAction() and hasText(placeholder, substring = true)).performTextInput("Stream a long answer")
                compose.onNodeWithContentDescription(activity.getString(R.string.send)).performClick()
                compose.waitUntil(10_000) { app.assistant.state.value.draft == opening }

                val list = compose.onNode(hasScrollAction())
                val jump = compose.onNodeWithContentDescription(activity.getString(R.string.assistant_jump_to_latest))
                fun scrollRange() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                fun position() = scrollRange().value()
                fun assertAtBottom() = scrollRange().let {
                    assertEquals("The viewport should follow the end of the reply", it.maxValue(), it.value(), 0.0001f)
                }
                fun scrollBySwipe(fraction: Float) = list.performTouchInput {
                    val start = Offset(width * 0.03f, center.y)
                    swipeWithVelocity(start, start.copy(y = start.y + height * fraction), endVelocity = 0f)
                }
                assertAtBottom()
                jump.assertDoesNotExist()
                val openingBottom = position()
                scrollBySwipe(0.4f)
                val afterSwipe = position()
                assertTrue("The swipe must scroll back within the long draft", afterSwipe < openingBottom)
                jump.assertIsDisplayed()

                compose.waitUntil(10_000) { app.assistant.state.value.draft == opening + paused }
                assertEquals("A streamed chunk moved the viewport", afterSwipe, position(), 0.0001f)
                scrollBySwipe(-0.15f)
                assertTrue("The draft must scroll in both directions", position() > afterSwipe)
                jump.performClick()
                assertAtBottom()
                jump.assertDoesNotExist()
                val resumedBottom = position()
                compose.waitUntil(10_000) { app.assistant.state.value.draft == opening + paused + resumed }
                assertAtBottom()
                assertTrue("New content must advance the viewport after jumping", position() > resumedBottom)
                compose.waitUntil(10_000) { !app.assistant.state.value.busy }
                assertEquals(answer, app.assistant.state.value.session.turns.last().answer)
                assertAtBottom()
            } finally {
                compose.runOnIdle { app.assistant.cancel() }
                app.settings.update { it.copy(llm = previous) }
            }
        }
    }

    private fun chunk(content: String, finish: String? = null) = "data: " + buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("delta") { put("content", content) }
                finish?.let { put("finish_reason", it) }
            }
        }
    } + "\n\n"
}
