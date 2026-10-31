package com.jarvis.ai.core.audio

/**
 * Speech output behind a replaceable interface.
 *
 * Two implementations ship: the device's own engine (offline, no key) and
 * the cloud relay (the ar-DZ-IsmaelNeural voice). A third engine can be
 * added later without touching anything that calls [speak].
 */
interface TtsProvider {

    val id: String

    /** Shown in settings, in Arabic. */
    val label: String

    /** False when this provider cannot run right now (no key, no engine). */
    suspend fun isAvailable(): Boolean

    /**
     * Speak [text], suspending until playback finishes or is cancelled.
     *
     * [onAmplitude] receives a 0..1 level for the visualiser. Providers that
     * cannot measure real amplitude must not invent one — they leave it
     * untouched, and the visualiser falls back to its working animation.
     */
    suspend fun speak(
        text: String,
        voice: String,
        pitch: String,
        rate: String,
        onAmplitude: ((Float) -> Unit)? = null,
    )

    /** Stop immediately. Safe to call when nothing is playing. */
    fun stop()

    fun release()

    /** Voices this provider can offer, for the settings picker. */
    suspend fun voices(): List<VoiceOption> = emptyList()
}

data class VoiceOption(
    val id: String,
    val name: String,
    val locale: String,
    val localeName: String,
)

/** Expressive modes. Mapped to prosody only — see the backend's voice relay. */
enum class SpeechStyle(val id: String, val label: String) {
    NEUTRAL("neutral", "محايد"),
    CALM("calm", "هادئ"),
    SERIOUS("serious", "جادّ"),
    FRIENDLY("friendly", "ودّي"),
    EXCITED("excited", "متحمّس"),
    CONCERNED("concerned", "قلق"),
}

class TtsException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
