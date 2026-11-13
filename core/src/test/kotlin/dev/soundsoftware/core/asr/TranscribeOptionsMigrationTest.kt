package dev.soundsoftware.core.asr

import dev.soundsoftware.core.model.SceneTemplate
import dev.soundsoftware.core.model.TranscribeOptions
import org.junit.Test
import org.junit.Assert.assertEquals

class TranscribeOptionsMigrationTest {
    @Test fun migratesOnlyLegacyLocalProfiles() {
        val saved = TranscribeOptions(
            template = SceneTemplate.AUTO,
            profileId = "qwen-cpu",
            language = "en",
            mixedChineseEnglish = false,
            speakerCount = 2,
            hotwords = listOf("test term"),
        )
        assertEquals(saved.copy(profileId = "qwen-htp"), saved.withCurrentAsrProfile())
        assertEquals(
            saved.copy(profileId = "sensevoice-qnn"),
            saved.copy(profileId = "sensevoice-cpu").withCurrentAsrProfile(),
        )
        for (profile in listOf("qwen-htp", "sensevoice-qnn", "http-api")) {
            val current = saved.copy(profileId = profile)
            assertEquals(current, current.withCurrentAsrProfile())
        }
        assertEquals("qwen-htp", TranscribeOptions().profileId)
    }
}
