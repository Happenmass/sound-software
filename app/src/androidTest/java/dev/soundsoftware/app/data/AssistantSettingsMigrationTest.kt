package dev.soundsoftware.app.data

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSettingsMigrationTest {
    @Test fun legacySelectionsKeepTheirModelProfiles() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "backend-upgrade-${UUID.randomUUID()}-"
        val name = prefix + "app-settings"
        val prefs = app.getSharedPreferences(name, 0)
        try {
            val pi = AssistantModelSettings("https://pi.example.test/v1", "test-pi")
            val codex = AssistantModelSettings("https://codex.example.test/v1", "test-codex")
            val profiles = buildJsonObject {
                put("autoSync", false)
                put("pi", Json.encodeToJsonElement(pi))
                put("codex", Json.encodeToJsonElement(codex))
            }
            val selections = listOf(
                null to AssistantBackend.PI,
                JsonNull to AssistantBackend.PI,
                JsonPrimitive("BUILT_IN") to AssistantBackend.PI,
                JsonPrimitive("PI") to AssistantBackend.PI,
                JsonPrimitive("CODEX") to AssistantBackend.CODEX,
            )
            for ((stored, expected) in selections) {
                val settings = if (stored == null) profiles else JsonObject(profiles + ("assistantBackend" to stored))
                assertTrue(prefs.edit().putString("settings", settings.toString()).commit())
                val loaded = SettingsStore(app, prefix).state.value
                assertEquals(expected, loaded.assistantBackend)
                assertEquals(pi, loaded.pi)
                assertEquals(codex, loaded.codex)
                assertFalse(loaded.autoSync)
            }
        } finally {
            app.deleteSharedPreferences(name)
        }
    }
}
