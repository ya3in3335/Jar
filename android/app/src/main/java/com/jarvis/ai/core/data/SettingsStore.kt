package com.jarvis.ai.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_settings")

/** Non-secret user settings. Secrets live in [SecureStore]. */
data class JarvisSettings(
    val model: String = "anthropic/claude-3.5-haiku",
    val routingMode: String = "balanced", // economy | balanced | quality
    /** "deepgram" for live streaming recognition, "off" to type only. */
    val sttEngine: String = "deepgram",
    /** Recognition language: "ar", "multi", "en", "fr". */
    val sttLanguage: String = "ar",
    /**
     * "azure" talks to Azure directly with a key on this device (no server
     * needed), "relay" goes through a paired backend, "system" is the
     * phone's own engine.
     */
    val voiceProvider: String = "azure",
    val voice: String = "ar-DZ-IsmaelNeural",
    val voicePitch: String = "-15Hz",
    val voiceRate: String = "-8%",
    val autoSpeak: Boolean = true,
    val wakeWordEnabled: Boolean = false,
    val reducedMotion: Boolean = false,
    val confirmSensitiveActions: Boolean = true,
    val maxToolCalls: Int = 8,
    // Lower than it looks: a two-sentence reply is the goal, and a
    // high cap mostly buys a longer wait.
    val maxOutputTokens: Int = 400,
    val language: String = "ar",
)

@Singleton
class SettingsStore @Inject constructor(private val context: Context) {

    val settings: Flow<JarvisSettings> = context.dataStore.data.map { p ->
        JarvisSettings(
            model = p[Keys.MODEL] ?: JarvisSettings().model,
            routingMode = p[Keys.ROUTING] ?: JarvisSettings().routingMode,
            sttEngine = p[Keys.STT_ENGINE] ?: JarvisSettings().sttEngine,
            sttLanguage = p[Keys.STT_LANGUAGE] ?: JarvisSettings().sttLanguage,
            voiceProvider = p[Keys.VOICE_PROVIDER] ?: JarvisSettings().voiceProvider,
            voice = p[Keys.VOICE] ?: JarvisSettings().voice,
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

    suspend fun setModel(value: String) = edit { it[Keys.MODEL] = value }
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
        val MODEL = stringPreferencesKey("model")
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
