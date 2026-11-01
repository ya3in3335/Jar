package com.jarvis.ai.core.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.jarvis.ai.core.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.coroutines.resume

/**
 * Azure neural speech, called straight from the phone.
 *
 * This is the no-server path to ar-DZ-IsmaelNeural: the user's own key,
 * encrypted at rest with an Android Keystore key. The backend relay stays
 * available for the paired setup, where the key never reaches the device.
 *
 * Audio is written to a cache file before playing: MediaPlayer needs a
 * seekable source, so a chunked HTTP body cannot feed it directly. That is
 * a whole-sentence wait, which is why AzureStreamingTts exists and is tried
 * first — it asks for raw PCM and needs no decoder at all. This provider
 * stays behind it as the fallback, since MP3 over a flaky connection is
 * more forgiving than raw PCM.
 */
class AzureTtsProvider(
    private val http: OkHttpClient,
    private val secureStore: SecureStore,
    private val cacheDir: File,
) : TtsProvider {

    override val id = "azure"
    override val label = "صوت إسماعيل الجزائري (أزور)"

    private val ssmlType = "application/ssml+xml".toMediaType()

    @Volatile private var player: MediaPlayer? = null

    override suspend fun isAvailable(): Boolean =
        !secureStore.azureSpeechKey.isNullOrBlank() && !secureStore.azureRegion.isNullOrBlank()

    override suspend fun speak(
        text: String,
        voice: String,
        pitch: String,
        rate: String,
        onAmplitude: ((Float) -> Unit)?,
    ) {
        val key = secureStore.azureSpeechKey ?: throw TtsException("أضف مفتاح أزور من الإعدادات")
        val region = secureStore.azureRegion ?: throw TtsException("أضف منطقة أزور من الإعدادات")

        val audio = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
                .header("Ocp-Apim-Subscription-Key", key)
                .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
                .header("User-Agent", "jarvis")
                .post(buildSsml(text, voice, pitch, rate).toRequestBody(ssmlType))
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw TtsException(describe(response.code))
                val bytes = response.body?.bytes() ?: throw TtsException("لم تُرجع الخدمة صوتًا")
                if (bytes.isEmpty()) throw TtsException("لم تُرجع الخدمة صوتًا")
                File(cacheDir, "jarvis-azure.mp3").apply { writeBytes(bytes) }
            }
        }

        play(audio)
    }

    private suspend fun play(file: File) = suspendCancellableCoroutine { continuation ->
        stop() // never let two utterances overlap

        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
            setOnErrorListener { _, what, extra ->
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(TtsException("تعذّر تشغيل الصوت ($what/$extra)")))
                }
                true
            }
        }
        player = mp
        continuation.invokeOnCancellation { stop() }

        runCatching {
            mp.prepare()
            mp.start()
        }.onFailure { e ->
            if (continuation.isActive) {
                continuation.resumeWith(Result.failure(TtsException("تعذّر تهيئة الصوت", e)))
            }
        }
    }

    override fun stop() {
        val current = player ?: return
        player = null
        runCatching { if (current.isPlaying) current.stop() }
        runCatching { current.release() }
    }

    override fun release() = stop()

    override suspend fun voices(): List<VoiceOption> = ARABIC_VOICES

    companion object {

        /**
         * Shared with the streaming provider: both talk to the same
         * endpoint, so they should explain its failures identically.
         */
        fun describe(code: Int): String = when (code) {
            401, 403 -> "أزور رفض المفتاح أو المنطقة"
            429 -> "تجاوزت حصّة الصوت، حاول بعد قليل"
            400 -> "أزور رفض الطلب (تحقّق من اسم الصوت)"
            else -> "خطأ من خدمة الصوت (HTTP $code)"
        }

        val ARABIC_VOICES = listOf(
            VoiceOption("ar-DZ-IsmaelNeural", "إسماعيل", "ar-DZ", "الجزائر"),
            VoiceOption("ar-DZ-AminaNeural", "أمينة", "ar-DZ", "الجزائر"),
            VoiceOption("ar-SA-HamedNeural", "حامد", "ar-SA", "السعودية"),
            VoiceOption("ar-AE-HamdanNeural", "حمدان", "ar-AE", "الإمارات"),
            VoiceOption("ar-EG-ShakirNeural", "شاكر", "ar-EG", "مصر"),
            VoiceOption("ar-MA-JamalNeural", "جمال", "ar-MA", "المغرب"),
            VoiceOption("ar-TN-HediNeural", "هادي", "ar-TN", "تونس"),
        )

        /** XML-escape, or a quote in the text breaks the document. */
        fun escapeXml(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        fun buildSsml(text: String, voice: String, pitch: String, rate: String): String {
            val locale = voice.split("-").take(2).joinToString("-")
            return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"$locale\">" +
                "<voice name=\"$voice\">" +
                "<prosody pitch=\"$pitch\" rate=\"$rate\">${escapeXml(text)}</prosody>" +
                "</voice></speak>"
        }
    }
}
