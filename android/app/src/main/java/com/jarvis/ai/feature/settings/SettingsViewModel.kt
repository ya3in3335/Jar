package com.jarvis.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.ai.core.audio.VoiceEngine
import com.jarvis.ai.core.audio.VoiceOption
import com.jarvis.ai.core.data.JarvisSettings
import com.jarvis.ai.core.data.SecureStore
import com.jarvis.ai.core.data.SettingsStore
import com.jarvis.ai.core.net.JarvisBackend
import com.jarvis.ai.core.net.ModelCapabilities
import com.jarvis.ai.core.net.OpenRouterClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    /** Only whether a key exists — the key itself is never read back into the UI. */
    val hasApiKey: Boolean = false,
    val secureStorageAvailable: Boolean = true,
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean? = null,
    val toolCapableModels: List<ModelCapabilities.Caps> = emptyList(),
    val loadingModels: Boolean = false,
    val voices: List<VoiceOption> = emptyList(),
    val sttLanguages: List<JarvisBackend.Language> = emptyList(),
    val backendPaired: Boolean = false,
    val sttCheck: String? = null,
    val sttCheckOk: Boolean? = null,
    val speaking: Boolean = false,
    val notice: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val secureStore: SecureStore,
    private val openRouter: OpenRouterClient,
    private val voice: VoiceEngine,
    private val backend: JarvisBackend,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        SettingsUiState(
            hasApiKey = !secureStore.openRouterKey.isNullOrBlank(),
            secureStorageAvailable = secureStore.isAvailable,
            backendPaired = backend.isPaired,
        ),
    )
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    val settings: StateFlow<JarvisSettings> =
        settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, JarvisSettings())

    val suggestedModels: List<String> = ModelCapabilities.SUGGESTED_FREE

    // ------------------------------------------------------------- the key

    fun saveApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return

        if (!trimmed.startsWith("sk-or-")) {
            _ui.value = _ui.value.copy(
                notice = "مفتاح OpenRouter يبدأ عادةً بـ sk-or- — تأكّد أنك نسخته كاملًا",
            )
            return
        }
        if (!secureStore.isAvailable) {
            _ui.value = _ui.value.copy(
                notice = "التخزين المشفّر غير متاح على هذا الجهاز، فلن يُحفَظ المفتاح",
            )
            return
        }

        secureStore.openRouterKey = trimmed
        _ui.value = _ui.value.copy(hasApiKey = true, notice = "حُفظ المفتاح مشفّرًا على هذا الجهاز", testResult = null)
    }

    fun clearApiKey() {
        secureStore.openRouterKey = null
        _ui.value = _ui.value.copy(hasApiKey = false, testResult = null, toolCapableModels = emptyList())
    }

    fun testConnection() {
        val key = secureStore.openRouterKey
        if (key.isNullOrBlank()) {
            _ui.value = _ui.value.copy(testOk = false, testResult = "لا يوجد مفتاح محفوظ")
            return
        }
        _ui.value = _ui.value.copy(testing = true, testResult = null, testOk = null)

        viewModelScope.launch {
            val result = openRouter.testConnection(key, settings.value.model)
            _ui.value = _ui.value.copy(
                testing = false,
                testOk = result.isSuccess,
                testResult = result.getOrElse { it.message ?: "فشل الاتصال" },
            )
        }
    }

    /** Load the models that actually support tool calling. */
    fun loadModels() {
        val key = secureStore.openRouterKey ?: return
        _ui.value = _ui.value.copy(loadingModels = true)

        viewModelScope.launch {
            val models = runCatching { openRouter.toolCapableModels(key) }.getOrDefault(emptyList())
            _ui.value = _ui.value.copy(
                loadingModels = false,
                toolCapableModels = models.sortedBy { it.id },
                notice = if (models.isEmpty()) "تعذّر تحميل قائمة النماذج" else null,
            )
        }
    }

    // ------------------------------------------------- speech recognition

    fun loadSttLanguages() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(sttLanguages = backend.sttLanguages(), backendPaired = backend.isPaired)
        }
    }

    /**
     * Ask the backend for a recognition token.
     *
     * This checks the whole chain the user cannot see — pairing, the
     * backend's Deepgram key, and its permission to mint tokens — without
     * opening a microphone.
     */
    fun testRecognition() {
        _ui.value = _ui.value.copy(sttCheck = null, sttCheckOk = null)
        viewModelScope.launch {
            val result = runCatching { backend.sttSession(settings.value.sttLanguage) }
            _ui.value = result.fold(
                onSuccess = { session ->
                    if (session.isUsable) {
                        _ui.value.copy(
                            sttCheckOk = true,
                            sttCheck = "جاهز — رمز صالح ${session.expiresInSeconds} ثانية",
                        )
                    } else {
                        _ui.value.copy(sttCheckOk = false, sttCheck = "الخادم أرجع رمزًا غير صالح")
                    }
                },
                onFailure = { _ui.value.copy(sttCheckOk = false, sttCheck = it.message ?: "فشل الاتصال") },
            )
        }
    }

    fun setSttEngine(value: String) = viewModelScope.launch { settingsStore.setSttEngine(value) }
    fun setSttLanguage(value: String) = viewModelScope.launch { settingsStore.setSttLanguage(value) }

    // ----------------------------------------------------------- the voice

    fun loadVoices() {
        viewModelScope.launch {
            val provider = voice.providers.firstOrNull { it.id == settings.value.voiceProvider }
            _ui.value = _ui.value.copy(voices = provider?.voices().orEmpty())
        }
    }

    /** Speak a sample so the choice can be judged by ear, not by name. */
    fun previewVoice() {
        if (_ui.value.speaking) {
            voice.stop()
            _ui.value = _ui.value.copy(speaking = false)
            return
        }
        _ui.value = _ui.value.copy(speaking = true, notice = null)

        viewModelScope.launch {
            val current = settings.value
            val result = runCatching {
                voice.speak(
                    text = "مرحبًا، أنا جارفيس. جاهز لتنفيذ أوامرك.",
                    voice = current.voice,
                    pitch = current.voicePitch,
                    rate = current.voiceRate,
                    preferredProviderId = current.voiceProvider,
                )
            }
            _ui.value = _ui.value.copy(
                speaking = false,
                notice = result.fold(
                    onSuccess = { spoken ->
                        if (spoken.fellBack) "تحدّث بمحرّك بديل: ${spoken.providerId}" else null
                    },
                    onFailure = { it.message ?: "تعذّر النطق" },
                ),
            )
        }
    }

    // -------------------------------------------------------- plain setters

    fun setModel(value: String) = viewModelScope.launch { settingsStore.setModel(value) }
    fun setVoiceProvider(value: String) = viewModelScope.launch { settingsStore.setVoiceProvider(value) }
    fun setVoice(value: String) = viewModelScope.launch { settingsStore.setVoice(value) }
    fun setPitch(value: String) = viewModelScope.launch { settingsStore.setPitch(value) }
    fun setRate(value: String) = viewModelScope.launch { settingsStore.setRate(value) }
    fun setAutoSpeak(value: Boolean) = viewModelScope.launch { settingsStore.setAutoSpeak(value) }
    fun setReducedMotion(value: Boolean) = viewModelScope.launch { settingsStore.setReducedMotion(value) }
    fun setConfirmSensitive(value: Boolean) = viewModelScope.launch { settingsStore.setConfirmSensitive(value) }
    fun setMaxToolCalls(value: Int) = viewModelScope.launch { settingsStore.setMaxToolCalls(value.coerceIn(1, 20)) }
    fun setMaxOutputTokens(value: Int) =
        viewModelScope.launch { settingsStore.setMaxOutputTokens(value.coerceIn(128, 8192)) }

    fun dismissNotice() {
        _ui.value = _ui.value.copy(notice = null)
    }
}
