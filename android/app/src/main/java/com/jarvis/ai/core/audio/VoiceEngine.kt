package com.jarvis.ai.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.jarvis.ai.core.data.SecureStore
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses a speech provider and owns audio focus.
 *
 * The cloud voice is used when it is configured and reachable; otherwise the
 * device engine speaks. A failed cloud call falls back rather than leaving
 * the assistant mute, and the caller is told which provider actually spoke.
 */
@Singleton
class VoiceEngine @Inject constructor(
    private val context: Context,
    http: OkHttpClient,
    secureStore: SecureStore,
) {

    private val system = SystemTtsProvider(context)
    private val azureStream = AzureStreamingTts(http, secureStore)
    private val azure = AzureTtsProvider(http, secureStore, context.cacheDir)
    private val groq = GroqTtsProvider(http, secureStore)
    private val relay = RelayTtsProvider(http, secureStore, context.cacheDir)

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private val focusLock = Any()
    private var speaking = 0

    @Volatile private var active: TtsProvider? = null

    /**
     * Providers for the settings picker, best first.
     *
     * Groq leads because it speaks Arabic and English on the key the model
     * already needs. Azure-direct gives the Algerian neural voice with its
     * own key, the relay is for a paired backend, and the device engine
     * always works as a last resort.
     *
     * Deepgram is not here: it is used for recognition only.
     */
    val providers: List<TtsProvider>
        get() = listOf(groq, azureStream, azure, relay, system)

    suspend fun availableProviders(): List<TtsProvider> = providers.filter { it.isAvailable() }

    /**
     * [reason] says why the chosen provider did not speak when another one
     * had to. Without it a broken cloud voice is invisible: the phone's
     * engine takes over, or nothing is heard, and nothing says why.
     */
    data class SpokenBy(val providerId: String, val fellBack: Boolean, val reason: String? = null)

    /**
     * Speak [text]. Returns which provider was used.
     *
     * @param preferredProviderId "groq", "azure", "relay", "system", or null to pick the best.
     */
    suspend fun speak(
        text: String,
        voice: String = "ar-DZ-IsmaelNeural",
        pitch: String = "-15Hz",
        rate: String = "-8%",
        preferredProviderId: String? = null,
        onAmplitude: ((Float) -> Unit)? = null,
    ): SpokenBy {
        if (text.isBlank()) return SpokenBy("none", false)

        // The chosen provider first, then the others, so a failed call
        // still speaks. For Azure the streaming endpoint is tried before the
        // file-based one: same voice, but the first sound arrives sooner.
        val ordered = when (preferredProviderId) {
            "system" -> listOf(system, groq, azureStream, azure, relay)
            "azure", "azure-stream" -> listOf(azureStream, azure, relay, groq, system)
            "relay" -> listOf(relay, azureStream, azure, groq, system)
            else -> listOf(groq, azureStream, azure, relay, system)
        }

        requestFocus()
        try {
            var fellBack = false
            var lastError: Throwable? = null
            // Why the first choice did not speak — the one the user picked,
            // so the one whose failure is worth showing.
            var reason: String? = null

            for ((index, provider) in ordered.withIndex()) {
                if (!provider.isAvailable()) {
                    if (index == 0) reason = "${provider.label}: غير مهيّأ (لا مفتاح محفوظ له)"
                    fellBack = true
                    continue
                }
                try {
                    active = provider
                    provider.speak(text, voice, pitch, rate, onAmplitude)
                    return SpokenBy(provider.id, fellBack, reason)
                } catch (e: CancellationException) {
                    throw e // a cancelled utterance is not a provider failure
                } catch (e: Throwable) {
                    lastError = e
                    if (reason == null) reason = e.message
                    fellBack = true
                } finally {
                    active = null
                }
            }

            throw TtsException(
                reason ?: lastError?.message ?: "لا يوجد محرّك نطق متاح على هذا الجهاز",
                lastError,
            )
        } finally {
            abandonFocus()
        }
    }

    /** Stop whatever is speaking. Safe at any time. */
    fun stop() {
        active?.stop()
        active = null
        providers.forEach { it.stop() }
        releaseFocus()
    }

    fun release() {
        providers.forEach { it.release() }
        releaseFocus()
    }

    /**
     * Duck other audio while speaking, rather than fighting it. Music drops
     * in volume and comes back on its own once focus is abandoned.
     *
     * Reference-counted, because a reply is spoken sentence by sentence:
     * taking and dropping focus per sentence would make background music
     * surge between every one.
     */
    private fun requestFocus() {
        val manager = audioManager ?: return
        synchronized(focusLock) {
            if (speaking++ > 0) return
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        synchronized(focusLock) {
            if (speaking > 0) speaking--
            if (speaking > 0) return
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    /** Drop focus outright, whatever the count — for stop(). */
    private fun releaseFocus() {
        val manager = audioManager ?: return
        synchronized(focusLock) {
            speaking = 0
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }
}
