package com.jarvis.ai.core.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import com.jarvis.ai.core.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.coroutines.resume

/**
 * Deepgram Aura speech, using the same key the recogniser already holds.
 *
 * These voices are English. Deepgram has none in Arabic, so selecting one
 * also means the assistant should answer in English — an English voice
 * reading Arabic script produces noise, not speech. The reply language
 * follows the chosen voice; see AgentRuntime.
 *
 * Aura's REST endpoint exposes no prosody parameters at all: its query
 * string takes model, encoding, container, sample_rate and bit_rate, and
 * nothing for pitch or rate. So the user's pitch and rate are applied to
 * playback instead, which actually changes what is heard rather than being
 * quietly dropped.
 */
class DeepgramTtsProvider(
    private val http: OkHttpClient,
    private val secureStore: SecureStore,
    private val cacheDir: File,
) : TtsProvider {

    override val id = "deepgram"
    override val label = "أصوات ديبڨرام (إنجليزية)"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var fetched: List<VoiceOption>? = null

    override suspend fun isAvailable(): Boolean = !secureStore.deepgramKey.isNullOrBlank()

    /** Delivery register for Flux voices; ignored by Aura, which has none. */
    @Volatile var expressivity: Int = 0

    override suspend fun speak(
        text: String,
        voice: String,
        pitch: String,
        rate: String,
        onAmplitude: ((Float) -> Unit)?,
    ) {
        val key = secureStore.deepgramKey ?: throw TtsException("أضف مفتاح ديبڨرام من الإعدادات")
        val model = if (voice.startsWith("aura") || voice.startsWith("flux-")) voice else DEFAULT_VOICE

        val audio = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(speakUrlFor(model, expressivity))
                // A raw account key uses the Token scheme, not Bearer.
                .header("Authorization", "Token $key")
                .post(buildBody(text).toRequestBody(jsonType))
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw TtsException(describe(response.code))
                val bytes = response.body?.bytes() ?: throw TtsException("لم تُرجع الخدمة صوتًا")
                if (bytes.isEmpty()) throw TtsException("لم تُرجع الخدمة صوتًا")
                File(cacheDir, "jarvis-deepgram.mp3").apply { writeBytes(bytes) }
            }
        }

        play(audio, pitch, rate)
    }

    private suspend fun play(file: File, pitch: String, rate: String) =
        suspendCancellableCoroutine { continuation ->
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
                // Aura has no prosody controls, so the settings are applied
                // here. Failure is not fatal: speaking at the default rate
                // beats not speaking.
                runCatching {
                    mp.playbackParams = PlaybackParams()
                        .setSpeed(speedFor(rate))
                        .setPitch(pitchFor(pitch))
                }.onFailure { Log.w(TAG, "playback params unsupported: ${it.javaClass.simpleName}") }
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

    /**
     * The catalogue, asked of Deepgram when possible.
     *
     * The built-in list is deliberately short — only voices documented by
     * name — because a guessed model id fails the request at the moment
     * the user expects speech.
     */
    override suspend fun voices(): List<VoiceOption> {
        fetched?.let { return it }
        val key = secureStore.deepgramKey ?: return BUILT_IN_VOICES

        val live = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(MODELS_URL)
                    .header("Authorization", "Token $key")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    parseVoices(json.parseToJsonElement(response.body?.string().orEmpty()) as JsonObject)
                }
            }.getOrDefault(emptyList())
        }

        val result = live.ifEmpty { BUILT_IN_VOICES }
        fetched = result
        return result
    }

    private fun describe(code: Int): String = when (code) {
        401, 403 -> "ديبڨرام رفض المفتاح"
        429 -> "تجاوزت حصّة ديبڨرام، حاول بعد قليل"
        400 -> "ديبڨرام رفض الطلب (تحقّق من اسم الصوت)"
        else -> "خطأ من خدمة الصوت (HTTP $code)"
    }

    companion object {
        private const val TAG = "DeepgramTts"
        const val SPEAK_URL = "https://api.deepgram.com/v1/speak"
        const val SPEAK_URL_V2 = "https://api.deepgram.com/v2/speak"
        const val MODELS_URL = "https://api.deepgram.com/v1/models"

        /**
         * A deep, smooth male voice, from Deepgram's own voice table.
         * Calm and strong is exactly what Zeus is documented as.
         */
        const val DEFAULT_VOICE = "aura-2-zeus-en"

        /**
         * Male Aura-2 voices, with the characteristics Deepgram documents
         * for each. Only names from their own table are shipped: a guessed
         * model id fails at the moment the user expects speech.
         */
        val BUILT_IN_VOICES = listOf(
            VoiceOption("aura-2-zeus-en", "Zeus", "en-US", "عميق، واثق، سلس"),
            VoiceOption("aura-2-saturn-en", "Saturn", "en-US", "باريتون، واثق، متمكّن"),
            VoiceOption("aura-2-orpheus-en", "Orpheus", "en-US", "مهنيّ، واضح، جدير بالثقة"),
            VoiceOption("aura-2-apollo-en", "Apollo", "en-US", "واثق، مرتاح، عفويّ"),
            VoiceOption("aura-2-atlas-en", "Atlas", "en-US", "ناضج، متحمّس، ودود"),
        )

        /**
         * Delivery register, from calm to animated.
         *
         * Real and documented, but Flux-only: Deepgram states plainly that
         * expressivity is not available on Aura-2. So it is sent only for
         * a flux-* voice, and the slider is hidden otherwise rather than
         * pretending to do something.
         *
         * It is NOT laughter or sighs. Deepgram has no non-speech
         * vocalisation at all, and a tag like [laughing] is either stripped
         * or read aloud as the word.
         */
        fun supportsExpressivity(voice: String): Boolean = voice.startsWith("flux-")

        fun isFluxVoice(voice: String): Boolean = voice.startsWith("flux-")

        /** Flux is served from /v2/speak; Aura from /v1/speak. */
        fun speakUrlFor(voice: String, expressivity: Int): String {
            val base = if (isFluxVoice(voice)) SPEAK_URL_V2 else SPEAK_URL
            val params = StringBuilder("?model=$voice&encoding=mp3")
            if (isFluxVoice(voice)) {
                params.append("&expressivity=").append(expressivity.coerceIn(-2, 2))
            }
            return base + params
        }

        /** JSON body for one utterance. */
        fun buildBody(text: String): String =
            buildJsonObject { put("text", text) }.toString()

        /**
         * Pull TTS voices out of the models listing, keeping only Aura
         * models — the listing also contains recognition models.
         */
        fun parseVoices(body: JsonObject): List<VoiceOption> {
            val entries = body["tts"]?.jsonArray ?: return emptyList()
            return entries.mapNotNull { element ->
                val model = element.jsonObject
                val name = model["canonical_name"]?.jsonPrimitive?.content
                    ?: model["name"]?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                if (!name.startsWith("aura") && !name.startsWith("flux-")) return@mapNotNull null

                val language = model["languages"]?.jsonArray?.firstOrNull()
                    ?.jsonPrimitive?.content ?: "en"
                VoiceOption(
                    id = name,
                    name = model["name"]?.jsonPrimitive?.content ?: name,
                    locale = language,
                    localeName = model["metadata"]?.jsonObject
                        ?.get("accent")?.jsonPrimitive?.content ?: "",
                )
            }
        }

        /** "-8%" onto the 0.5..2 playback multiplier MediaPlayer accepts. */
        fun speedFor(rate: String): Float {
            val percent = rate.removeSuffix("%").toFloatOrNull() ?: return 1f
            return (1f + percent / 100f).coerceIn(0.5f, 2.0f)
        }

        /** "-15Hz" onto a playback pitch multiplier. */
        fun pitchFor(pitch: String): Float {
            val hz = pitch.removeSuffix("Hz").toFloatOrNull() ?: return 1f
            return (1f + hz / 100f).coerceIn(0.5f, 2.0f)
        }

        /** True when a voice speaks English, so replies must be English too. */
        fun isEnglishVoice(voice: String): Boolean =
            voice.startsWith("aura") || voice.startsWith("flux-") || voice.endsWith("-en")
    }
}
