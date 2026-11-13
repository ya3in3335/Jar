package com.jarvis.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.ai.core.audio.GroqTtsProvider
import com.jarvis.ai.core.audio.VoiceEngine
import com.jarvis.ai.core.audio.VoiceOption
import com.jarvis.ai.core.data.JarvisSettings
import com.jarvis.ai.core.data.SecureStore
import com.jarvis.ai.core.data.SettingsStore
import com.jarvis.ai.core.audio.AzureTtsProvider
import com.jarvis.ai.core.net.JarvisBackend
import com.jarvis.ai.core.net.ModelCapabilities
import com.jarvis.ai.core.net.ModelProvider
import com.jarvis.ai.core.stt.AutoSttSessionProvider
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
    val hasDeepgramKey: Boolean = false,
    val hasAzureKey: Boolean = false,
    val azureRegion: String = "",
    val sttCheck: String? = null,
    val sttCheckOk: Boolean? = null,
    val speaking: Boolean = false,
    /** Outcome of the last voice preview, shown beside its button. */
    val voiceResult: String? = null,
    val voiceOk: Boolean? = null,
    val notice: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val secureStore: SecureStore,
    private val openRouter: OpenRouterClient,
    private val voice: VoiceEngine,
    private val backend: JarvisBackend,
    private val sessions: AutoSttSessionProvider,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        SettingsUiState(
            hasApiKey = !secureStore.modelKey(ModelProvider.GROQ).isNullOrBlank(),
            secureStorageAvailable = secureStore.isAvailable,
            backendPaired = backend.isPaired,
            hasDeepgramKey = !secureStore.deepgramKey.isNullOrBlank(),
            hasAzureKey = !secureStore.azureSpeechKey.isNullOrBlank(),
            azureRegion = secureStore.azureRegion.orEmpty(),
        ),
    )
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    val settings: StateFlow<JarvisSettings> =
        settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, JarvisSettings())

    private val provider: ModelProvider get() = settings.value.modelProvider

    init {
        // Everything on this screen about "the key" means the current provider's.
        viewModelScope.launch {
            settings.collect { _ui.value = _ui.value.copy(hasApiKey = !secureStore.modelKey(provider).isNullOrBlank()) }
        }
    }

    // -------------------------------------------------------- the provider

    fun setProvider(value: ModelProvider) {
        if (value == provider) return
        _ui.value = _ui.value.copy(
            hasApiKey = !secureStore.modelKey(value).isNullOrBlank(),
            testResult = null,
            testOk = null,
            toolCapableModels = emptyList(),
        )
        viewModelScope.launch { settingsStore.setProvider(value) }
    }

    // ------------------------------------------------------------- the key

    fun saveApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        val target = provider

        if (!trimmed.startsWith(target.keyPrefix)) {
            _ui.value = _ui.value.copy(
                notice = "مفتاح ${target.label} يبدأ عادةً بـ ${target.keyPrefix} — تأكّد أنك نسخته كاملًا",
            )
            return
        }
        if (!secureStore.isAvailable) {
            _ui.value = _ui.value.copy(
                notice = "التخزين المشفّر غير متاح على هذا الجهاز، فلن يُحفَظ المفتاح",
            )
            return
        }

        secureStore.setModelKey(target, trimmed)
        _ui.value = _ui.value.copy(hasApiKey = true, notice = "حُفظ المفتاح مشفّرًا على هذا الجهاز", testResult = null)
    }

    fun clearApiKey() {
        secureStore.setModelKey(provider, null)
        _ui.value = _ui.value.copy(hasApiKey = false, testResult = null, toolCapableModels = emptyList())
    }

    fun testConnection() {
        val target = provider
        val key = secureStore.modelKey(target)
        if (key.isNullOrBlank()) {
            _ui.value = _ui.value.copy(testOk = false, testResult = "لا يوجد مفتاح محفوظ")
            return
        }
        _ui.value = _ui.value.copy(testing = true, testResult = null, testOk = null)

        viewModelScope.launch {
            val result = openRouter.testConnection(target, key, settings.value.model)
            _ui.value = _ui.value.copy(
                testing = false,
                testOk = result.isSuccess,
                testResult = result.getOrElse { it.message ?: "فشل الاتصال" },
            )
        }
    }

    /** Load the models that actually support tool calling. */
    fun loadModels() {
        val target = provider
        val key = secureStore.modelKey(target) ?: return
        _ui.value = _ui.value.copy(loadingModels = true)

        viewModelScope.launch {
            val models = runCatching { openRouter.toolCapableModels(target, key) }.getOrDefault(emptyList())
            _ui.value = _ui.value.copy(
                loadingModels = false,
                toolCapableModels = models.sortedBy { it.id },
                notice = if (models.isEmpty()) "تعذّر تحميل قائمة النماذج" else null,
            )
        }
    }

    // -------------------------------------------- keys for the no-server mode

    fun saveDeepgramKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        if (!secureStore.isAvailable) {
            _ui.value = _ui.value.copy(notice = "التخزين المشفّر غير متاح، فلن يُحفَظ المفتاح")
            return
        }
        secureStore.deepgramKey = trimmed
        _ui.value = _ui.value.copy(
            hasDeepgramKey = true,
            notice = "حُفظ مفتاح ديبڨرام مشفّرًا على هذا الجهاز",
            sttCheck = null,
        )
    }

    fun clearDeepgramKey() {
        secureStore.deepgramKey = null
        _ui.value = _ui.value.copy(hasDeepgramKey = false, sttCheck = null)
    }

    fun saveAzureCredentials(key: String, region: String) {
        val trimmedKey = key.trim()
        val trimmedRegion = region.trim().lowercase()
        if (trimmedKey.isEmpty() || trimmedRegion.isEmpty()) {
            _ui.value = _ui.value.copy(notice = "المفتاح والمنطقة مطلوبان معًا")
            return
        }
        if (!secureStore.isAvailable) {
            _ui.value = _ui.value.copy(notice = "التخزين المشفّر غير متاح، فلن يُحفَظ المفتاح")
            return
        }
        secureStore.azureSpeechKey = trimmedKey
        secureStore.azureRegion = trimmedRegion
        _ui.value = _ui.value.copy(
            hasAzureKey = true,
            azureRegion = trimmedRegion,
            notice = "حُفظت بيانات أزور مشفّرة على هذا الجهاز",
        )
        loadVoices()
    }

    fun clearAzureCredentials() {
        secureStore.azureSpeechKey = null
        secureStore.azureRegion = null
        _ui.value = _ui.value.copy(hasAzureKey = false, azureRegion = "")
    }

    // ------------------------------------------------- speech recognition

    fun loadSttLanguages() {
        viewModelScope.launch {
            val fromBackend = if (backend.isPaired) backend.sttLanguages() else emptyList()
            _ui.value = _ui.value.copy(
                sttLanguages = fromBackend.ifEmpty { BUILT_IN_LANGUAGES },
                backendPaired = backend.isPaired,
            )
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
            val result = runCatching { sessions.session(settings.value.sttLanguage) }
            _ui.value = result.fold(
                onSuccess = { session ->
                    if (session.isUsable) {
                        val how = if (sessions.mode == "backend") {
                            "عبر الخادم، رمز صالح ${session.expiresInSeconds} ثانية"
                        } else {
                            "مباشرة بمفتاح هذا الجهاز"
                        }
                        _ui.value.copy(sttCheckOk = true, sttCheck = "جاهز — $how")
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
        _ui.value = _ui.value.copy(speaking = true, notice = null, voiceResult = "جاري طلب الصوت…", voiceOk = null)

        viewModelScope.launch {
            val current = settings.value
            val result = runCatching {
                voice.speak(
                    text = if (GroqTtsProvider.isEnglishVoice(current.voice)) {
                        "Hello, I am Jarvis. Ready when you are."
                    } else {
                        "مرحبًا، أنا جارفيس. جاهز لتنفيذ أوامرك."
                    },
                    voice = current.voice,
                    pitch = current.voicePitch,
                    rate = current.voiceRate,
                    preferredProviderId = current.voiceProvider,
                )
            }
            val message = result.fold(
                onSuccess = { spoken ->
                    if (spoken.fellBack) {
                        listOfNotNull(spoken.reason, "نطق بدلًا منه: ${spoken.providerId}").joinToString(" — ")
                    } else {
                        // Proof of what was played: if this shows and nothing
                        // was heard, the fault is the phone's volume, not the voice.
                        spoken.detail?.let { "تمّ التشغيل: $it — إن لم تسمع شيئًا فارفع صوت الوسائط" }
                            ?: "تمّ التشغيل عبر ${spoken.providerId}"
                    }
                },
                onFailure = { it.message ?: "تعذّر النطق" },
            )
            _ui.value = _ui.value.copy(
                speaking = false,
                voiceResult = message,
                voiceOk = result.getOrNull()?.fellBack == false,
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

    /** Offered when no backend is paired, so the picker is never empty. */
    private val BUILT_IN_LANGUAGES = listOf(
        JarvisBackend.Language("ar", "العربية", "تشمل اللهجات المغاربية"),
        JarvisBackend.Language("multi", "متعدّد اللغات", "عربي وإنجليزي وفرنسي معًا"),
        JarvisBackend.Language("en", "الإنجليزية", ""),
        JarvisBackend.Language("fr", "الفرنسية", ""),
    )

    val azureVoices = AzureTtsProvider.ARABIC_VOICES

    fun dismissNotice() {
        _ui.value = _ui.value.copy(notice = null)
    }
}
