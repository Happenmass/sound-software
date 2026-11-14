package dev.soundsoftware.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.soundsoftware.core.model.TranscribeOptions
import dev.soundsoftware.core.model.SceneTemplate
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class BoundDevice(val address: String, val name: String, val serial: String? = null, val white: Boolean = true, val firmwareBound: Boolean = false)

@Serializable data class RemoteAsrSettings(val endpoint: String = "", val model: String = "", val segmentTimestamps: Boolean = false)

@Serializable data class LlmSettings(val baseUrl: String = "", val model: String = "", val repairTranscript: Boolean = false)

@Serializable
data class AppSettings(
    val defaults: TranscribeOptions = TranscribeOptions(template = SceneTemplate.AUTO),
    val autoTranscribe: Boolean = true,
    val deleteAfterSync: Boolean = false,
    val boundDevice: BoundDevice? = null,
    val remoteAsr: RemoteAsrSettings = RemoteAsrSettings(),
    val llm: LlmSettings = LlmSettings(),
    val findMyEnabled: Boolean? = null,
    val recentSpeakerNames: List<String> = emptyList(),
    val autoSync: Boolean = true,
)

/** Non-secret preferences as one JSON document; API keys go through [SecretStore]. */
class SettingsStore(private val context: Context) {
    fun text(@androidx.annotation.StringRes id: Int, vararg args: Any?): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    fun outputLanguage(): String = if (context.resources.configuration.locales[0].language == "zh") "zh" else "en"
    private val prefs = context.getSharedPreferences("app-settings", 0)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    val secrets = SecretStore(context)

    private fun load(): AppSettings {
        val saved = prefs.getString("settings", null)?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() }
            ?: return AppSettings()
        return saved.copy(defaults = saved.defaults.withCurrentAsrProfile())
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        mutable.update(transform)
        prefs.edit().putString("settings", json.encodeToString(mutable.value)).apply()
    }
}

/** AES-GCM with a non-exportable Android Keystore key. Values never enter logs or exports. */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", 0)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }
    fun get(name: String): String? = runCatching {
        val sealed = Base64.decode(prefs.getString(name, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12)) }
        String(cipher.doFinal(sealed, 12, sealed.size - 12))
    }.getOrNull()
    fun has(name: String) = prefs.contains(name)
    private companion object { const val ALIAS = "sound-software-secrets" }
}
