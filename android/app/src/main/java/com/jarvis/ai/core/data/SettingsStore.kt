package com.jarvis.ai.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jarvis.ai.core.net.ModelProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_settings")

/** Non-secret user settings. Secrets live in [SecureStore]. */
data class JarvisSettings(
    /** "groq" or "openrouter" — see [ModelProvider]. */
    val provider: String = ModelProvider.GROQ.id,
    /** The model for the current provider; each provider remembers its own. */
    val model: String = ModelProvider.GROQ.defaultModel,
    val routingMode: String = "balanced", // economy | balanced | quality
    /** "deepgram" for live streaming recognition, "off" to type only. */
    val sttEngine: String = "deepgram",
    /** Recognition language: "ar", "multi", "en", "fr". */
    val sttLanguage: String = "ar",
    /**
     * "groq" speaks Arabic and English on the Groq key, "azure" talks to
     * Azure directly with its own key, "relay" goes through a paired
     * backend, "system" is the phone's own engine.
     */
    val voiceProvider: String = "groq",
    val voice: String = "groq-ar-fahad",
    val voicePitch: String = "-15Hz",
    val voiceRate: String = "-8%",
    val autoSpeak: Boolean = true,
    val wakeWordEnabled: Boolean = false,
    val reducedMotion: Boolean = false,
    val confirmSensitiveActions: Boolean = true,
    // Multi-step instructions chain several tools, so the ceiling
    // is higher than a single action needs.
    // Screen automation is many small steps — open, wait, read, tap, read
    // again — so a chain that touches the UI needs far more headroom than
    // one that only fires intents.
    val maxToolCalls: Int = 24,
    // Lower than it looks: a two-sentence reply is the goal, and a
    // high cap mostly buys a longer wait.
    val maxOutputTokens: Int = 400,
    val language: String = "ar",
) {
    val modelProvider: ModelProvider get() = ModelProvider.fromId(provider)
}

@Singleton
class SettingsStore @Inject constructor(private val context: Context) {

    val settings: Flow<JarvisSettings> = context.dataStore.data.map { p ->
        val provider = ModelProvider.fromId(p[Keys.PROVIDER])
        JarvisSettings(
            provider = provider.id,
            model = when (provider) {
                ModelProvider.GROQ -> p[Keys.MODEL_GROQ]
                // A paid model saved by an older build is ignored, not sent.
                ModelProvider.OPENROUTER -> p[Keys.MODEL]?.takeIf { ModelProvider.isFreeOpenRouterModel(it) }
            } ?: provider.defaultModel,
            routingMode = p[Keys.ROUTING] ?: JarvisSettings().routingMode,
            sttEngine = p[Keys.STT_ENGINE] ?: JarvisSettings().sttEngine,
            sttLanguage = p[Keys.STT_LANGUAGE] ?: JarvisSettings().sttLanguage,
            // Deepgram no longer speaks (it is recognition-only), so a
            // saved Deepgram voice moves to Groq's English one.
            voiceProvider = p[Keys.VOICE_PROVIDER]?.takeUnless { it.startsWith("deepgram") }
                ?: JarvisSettings().voiceProvider,
            voice = when {
                p[Keys.VOICE_PROVIDER]?.startsWith("deepgram") == true -> "groq-en-troy"
                else -> p[Keys.VOICE] ?: JarvisSettings().voice
            },
            voicePitch = p[Keys.PITCH] ?: JarvisSettings().voicePitch,
            voiceRate = p[Keys.RATE] ?: JarvisSettings().voiceRate,
            autoSpeak = p[Keys.AUTO_SPEAK] ?: JarvisSettings().autoSpeak,
            wakeWordEnabled = p[Keys.WAKE_WORD] ?: JarvisSettings().wakeWordEnabled,
            reducedMotion = p[Keys.REDUCED_MOTION] ?: JarvisSettings().reducedMotion,
            confirmSensitiveActions = p[Keys.CONFIRM] ?: JarvisSettings().confirmSensitiveActions,
            maxToolCalls = p[Keys.MAX_TOOL_CALLS] ?: JarvisSettings().maxToolCalls,
            maxOutputTokens = p[Keys.MAX_OUTPUT] ?: JarvisSettings().maxOutputTokens,
            language = p[Keys.LANGUAGE] ?: JarvisSettings().language,
        )
    }

    suspend fun setProvider(value: ModelProvider) = edit { it[Keys.PROVIDER] = value.id }

    /** Stores [value] for the current provider; a model it does not allow is dropped. */
    suspend fun setModel(value: String) = edit {
        val provider = ModelProvider.fromId(it[Keys.PROVIDER])
        if (provider.allows(value)) {
            it[if (provider == ModelProvider.GROQ) Keys.MODEL_GROQ else Keys.MODEL] = value
        }
    }
    suspend fun setRoutingMode(value: String) = edit { it[Keys.ROUTING] = value }
    suspend fun setSttEngine(value: String) = edit { it[Keys.STT_ENGINE] = value }
    suspend fun setSttLanguage(value: String) = edit { it[Keys.STT_LANGUAGE] = value }
    suspend fun setVoiceProvider(value: String) = edit { it[Keys.VOICE_PROVIDER] = value }
    suspend fun setVoice(value: String) = edit { it[Keys.VOICE] = value }
    suspend fun setPitch(value: String) = edit { it[Keys.PITCH] = value }
    suspend fun setRate(value: String) = edit { it[Keys.RATE] = value }
    suspend fun setAutoSpeak(value: Boolean) = edit { it[Keys.AUTO_SPEAK] = value }
    suspend fun setWakeWord(value: Boolean) = edit { it[Keys.WAKE_WORD] = value }
    suspend fun setReducedMotion(value: Boolean) = edit { it[Keys.REDUCED_MOTION] = value }
    suspend fun setConfirmSensitive(value: Boolean) = edit { it[Keys.CONFIRM] = value }
    suspend fun setMaxToolCalls(value: Int) = edit { it[Keys.MAX_TOOL_CALLS] = value }
    suspend fun setMaxOutputTokens(value: Int) = edit { it[Keys.MAX_OUTPUT] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private object Keys {
        val PROVIDER = stringPreferencesKey("model_provider")
        /** OpenRouter's model; the key predates the provider choice. */
        val MODEL = stringPreferencesKey("model")
        val MODEL_GROQ = stringPreferencesKey("model_groq")
        val ROUTING = stringPreferencesKey("routing_mode")
        val STT_ENGINE = stringPreferencesKey("stt_engine")
        val STT_LANGUAGE = stringPreferencesKey("stt_language")
        val VOICE_PROVIDER = stringPreferencesKey("voice_provider")
        val VOICE = stringPreferencesKey("voice")
        val PITCH = stringPreferencesKey("voice_pitch")
        val RATE = stringPreferencesKey("voice_rate")
        val AUTO_SPEAK = booleanPreferencesKey("auto_speak")
        val WAKE_WORD = booleanPreferencesKey("wake_word")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val CONFIRM = booleanPreferencesKey("confirm_sensitive")
        val MAX_TOOL_CALLS = intPreferencesKey("max_tool_calls")
        val MAX_OUTPUT = intPreferencesKey("max_output_tokens")
        val LANGUAGE = stringPreferencesKey("language")
    }
}
