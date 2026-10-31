package com.jarvis.ai.core.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The device's own speech engine.
 *
 * Works offline, costs nothing and needs no key, so this is the default:
 * the assistant can talk the moment the app is installed. Quality depends
 * on whichever engine and Arabic voice data the phone has, which is why the
 * cloud provider exists as an upgrade rather than a requirement.
 */
class SystemTtsProvider(private val context: Context) : TtsProvider {

    override val id = "system"
    override val label = "محرّك الهاتف (بلا إنترنت)"

    private var engine: TextToSpeech? = null
    @Volatile private var ready = false

    /** Initialise once; returns false when no engine or no Arabic voice. */
    private suspend fun ensureReady(): Boolean {
        engine?.let { return ready }
        return suspendCoroutine { continuation ->
            var settled = false
            // A holder, because the init callback can fire before the
            // constructor's return value has been assigned anywhere.
            val holder = arrayOfNulls<TextToSpeech>(1)

            holder[0] = TextToSpeech(context) { status ->
                if (settled) return@TextToSpeech
                settled = true

                var ok = status == TextToSpeech.SUCCESS
                if (ok) {
                    val result = holder[0]?.setLanguage(Locale("ar"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "no Arabic voice data in the device engine")
                        ok = false
                    }
                }
                ready = ok
                engine = holder[0]
                continuation.resume(ok)
            }
        }
    }

    override suspend fun isAvailable(): Boolean = ensureReady()

    override suspend fun speak(
        text: String,
        voice: String,
        pitch: String,
        rate: String,
        onAmplitude: ((Float) -> Unit)?,
    ) {
        if (!ensureReady()) throw TtsException("لا يوجد صوت عربي في محرّك الهاتف")
        val tts = engine ?: throw TtsException("محرّك النطق غير مهيّأ")

        // The platform engine takes multipliers, not the SSML Hz/% the cloud
        // voice uses, so the same settings are translated rather than ignored.
        tts.setPitch(pitchMultiplier(pitch))
        tts.setSpeechRate(rateMultiplier(rate))

        suspendCancellableCoroutine { continuation ->
            val utteranceId = "jarvis-${System.nanoTime()}"

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit

                override fun onDone(id: String?) {
                    if (id == utteranceId && continuation.isActive) continuation.resume(Unit)
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(id: String?) {
                    if (id == utteranceId && continuation.isActive) {
                        continuation.resumeWith(Result.failure(TtsException("فشل النطق")))
                    }
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && continuation.isActive) {
                        continuation.resumeWith(Result.failure(TtsException("فشل النطق (رمز $errorCode)")))
                    }
                }
            })

            continuation.invokeOnCancellation { tts.stop() }

            val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (queued != TextToSpeech.SUCCESS && continuation.isActive) {
                continuation.resumeWith(Result.failure(TtsException("تعذّر بدء النطق")))
            }
        }
        // No amplitude: this engine plays straight to the audio device and
        // exposes no PCM, so inventing a level would be a fake waveform.
    }

    override fun stop() {
        runCatching { engine?.stop() }
    }

    override fun release() {
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        engine = null
        ready = false
    }

    override suspend fun voices(): List<VoiceOption> {
        if (!ensureReady()) return emptyList()
        return engine?.voices.orEmpty()
            .filter { it.locale.language == "ar" }
            .map { VoiceOption(it.name, it.name, it.locale.toString(), it.locale.displayCountry) }
            .sortedBy { it.locale }
    }

    private companion object {
        const val TAG = "SystemTts"

        /** "-15Hz" is a lowered voice; map to a sub-1.0 multiplier. */
        fun pitchMultiplier(pitch: String): Float {
            val hz = pitch.removeSuffix("Hz").toFloatOrNull() ?: return 1f
            return (1f + hz / 100f).coerceIn(0.5f, 2.0f)
        }

        /** "-8%" means 8 percent slower. */
        fun rateMultiplier(rate: String): Float {
            val percent = rate.removeSuffix("%").toFloatOrNull() ?: return 1f
            return (1f + percent / 100f).coerceIn(0.5f, 2.0f)
        }
    }
}
