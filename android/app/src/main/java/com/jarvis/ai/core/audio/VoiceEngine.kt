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
    private val azure = AzureTtsProvider(http, secureStore, context.cacheDir)
    private val deepgram = DeepgramTtsProvider(http, secureStore, context.cacheDir)
    private val relay = RelayTtsProvider(http, secureStore, context.cacheDir)

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null

    @Volatile private var active: TtsProvider? = null

    /**
     * Providers for the settings picker, best first.
     *
     * Azure-direct leads because the no-server setup is the default: it
     * gives the Algerian neural voice with only a key. The relay is for a
     * paired backend, and the device engine always works as a last resort.
     */
    val providers: List<TtsProvider> get() = listOf(azure, deepgram, relay, system)

    suspend fun availableProviders(): List<TtsProvider> = providers.filter { it.isAvailable() }

    data class SpokenBy(val providerId: String, val fellBack: Boolean)

    /**
     * Speak [text]. Returns which provider was used.
     *
     * @param preferredProviderId "relay", "system", or null to pick the best.
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

        val ordered = when (preferredProviderId) {
            "system" -> listOf(system, azure, deepgram, relay)
            "azure" -> listOf(azure, relay, deepgram, system)
            "deepgram" -> listOf(deepgram, azure, system)
            "relay" -> listOf(relay, azure, deepgram, system)
            else -> listOf(azure, deepgram, relay, system)
        }

        requestFocus()
        try {
            var fellBack = false
            var lastError: Throwable? = null

            for (provider in ordered) {
                if (!provider.isAvailable()) {
                    fellBack = true
                    continue
                }
                try {
                    active = provider
                    provider.speak(text, voice, pitch, rate, onAmplitude)
                    return SpokenBy(provider.id, fellBack)
                } catch (e: CancellationException) {
                    throw e // a cancelled utterance is not a provider failure
                } catch (e: Throwable) {
                    lastError = e
                    fellBack = true
                } finally {
                    active = null
                }
            }

            throw TtsException(
                lastError?.message ?: "لا يوجد محرّك نطق متاح على هذا الجهاز",
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
        abandonFocus()
    }

    fun release() {
        providers.forEach { it.release() }
        abandonFocus()
    }

    /**
     * Duck other audio while speaking, rather than fighting it. Music drops
     * in volume and comes back on its own once focus is abandoned.
     */
    private fun requestFocus() {
        val manager = audioManager ?: return
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

    private fun abandonFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
