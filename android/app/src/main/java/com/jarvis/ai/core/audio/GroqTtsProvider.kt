package com.jarvis.ai.core.audio

import com.jarvis.ai.core.data.SecureStore
import com.jarvis.ai.core.data.redact
import com.jarvis.ai.core.net.ModelProvider
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Groq's Orpheus voices, Arabic and English, on the Groq key the model
 * already uses — so speech needs no second account.
 *
 * Two things about the service shape this class:
 *
 * - One request takes at most 200 characters, so a reply is cut at sentence
 *   and word boundaries and spoken piece by piece. The next piece is
 *   fetched while the current one plays, which hides most of the gap.
 * - The only format is WAV. Its header says what the samples are, so it is
 *   read rather than assumed, and the samples go to the shared [PcmPlayer].
 *
 * The service has no pitch or rate parameter; the user's values are applied
 * at playback, which changes what is actually heard.
 */
class GroqTtsProvider(
    private val http: OkHttpClient,
    private val secureStore: SecureStore,
) : TtsProvider {

    override val id = ID
    override val label = "أصوات Groq (عربية وإنجليزية)"

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @Volatile private var player: PcmPlayer? = null

    /** What the last successful call played, for the settings preview. */
    @Volatile var lastDetail: String? = null
        private set

    override suspend fun isAvailable(): Boolean = !secureStore.groqKey.isNullOrBlank()

    override suspend fun voices(): List<VoiceOption> = VOICES

    override suspend fun speak(
        text: String,
        voice: String,
        pitch: String,
        rate: String,
        onAmplitude: ((Float) -> Unit)?,
    ) {
        val key = secureStore.groqKey ?: throw TtsException("أضف مفتاح Groq من الإعدادات")
        val spec = specFor(voice, text)
        val pieces = splitForSpeech(text)
        if (pieces.isEmpty()) return

        lastDetail = null
        try {
            coroutineScope {
                var next: Deferred<Wav> = async(Dispatchers.IO) { fetch(key, spec, pieces[0]) }
                var seconds = 0f
                var peak = 0f
                var playedMs = 0L
                for (index in pieces.indices) {
                    val wav = next.await()
                    seconds += wav.pcm.size / 2f / wav.sampleRate
                    peak = maxOf(peak, peakOf(wav.pcm))
                    // Peak 0% means the service sent silence; anything else was real sound.
                    val describe = { ms: Long ->
                        "Groq · ${spec.voice} · ${wav.sampleRate}Hz · " +
                            "${"%.1f".format(java.util.Locale.US, seconds)} ث · أعلى مستوى ${(peak * 100).toInt()}% · " +
                            "استغرق التشغيل ${"%.1f".format(java.util.Locale.US, ms / 1000f)} ث"
                    }
                    lastDetail = describe(playedMs)
                    if (index + 1 < pieces.size) {
                        next = async(Dispatchers.IO) { fetch(key, spec, pieces[index + 1]) }
                    }
                    // How long the speaker really took. Far less than the
                    // audio's length means the samples were never played out.
                    val began = System.nanoTime()
                    play(wav, pitch, rate, onAmplitude)
                    playedMs += (System.nanoTime() - began) / 1_000_000
                    lastDetail = describe(playedMs)
                }
            }
        } finally {
            onAmplitude?.invoke(0f)
        }
    }

    private fun fetch(key: String, spec: VoiceSpec, piece: String): Wav {
        val request = Request.Builder()
            .url("${ModelProvider.GROQ.baseUrl}/audio/speech")
            .header("Authorization", "Bearer $key")
            .post(buildBody(spec, piece).toRequestBody(jsonType))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw TtsException(describe(response.code, response.body?.string().orEmpty(), spec.model))
            }
            val bytes = response.body?.bytes() ?: throw TtsException("لم تُرجع الخدمة صوتًا")
            return parseWav(bytes) ?: throw TtsException("صيغة الصوت المُرجَعة غير مفهومة")
        }
    }

    private suspend fun play(wav: Wav, pitch: String, rate: String, onAmplitude: ((Float) -> Unit)?) {
        val player = PcmPlayer(wav.sampleRate).apply { start(pitch, rate) }
        this.player = player
        try {
            // The write blocks on the speaker, so it stays off the caller's dispatcher.
            withContext(Dispatchers.IO) {
                val buffer = ByteArray(CHUNK)
                var offset = 0
                while (offset < wav.pcm.size) {
                    val length = minOf(CHUNK, wav.pcm.size - offset)
                    System.arraycopy(wav.pcm, offset, buffer, 0, length)
                    onAmplitude?.invoke(player.write(buffer, length))
                    offset += length
                }
            }
            player.drain()
        } finally {
            player.stop()
            if (this.player === player) this.player = null
        }
    }

    override fun stop() {
        player?.stop()
        player = null
    }

    override fun release() = stop()

    /** Which model and voice a request uses. */
    data class VoiceSpec(val model: String, val voice: String)

    /** Mono 16-bit samples and the rate to play them at. */
    class Wav(val sampleRate: Int, val pcm: ByteArray)

    companion object {
        const val ID = "groq"
        const val MODEL_ARABIC = "canopylabs/orpheus-arabic-saudi"
        const val MODEL_ENGLISH = "canopylabs/orpheus-v1-english"
        const val DEFAULT_ARABIC = "groq-ar-fahad"
        const val DEFAULT_ENGLISH = "groq-en-troy"

        /** Groq's documented limit for one request. */
        const val MAX_CHARS = 200

        private const val CHUNK = 8 * 1024
        private const val AR = "groq-ar-"
        private const val EN = "groq-en-"

        /**
         * Ids carry the language so one string is enough to pick the model.
         * The Arabic voices are Saudi-dialect; Groq has no Algerian one.
         */
        val VOICES = listOf(
            VoiceOption("${AR}fahad", "فهد", "ar-SA", "عربي — رجل"),
            VoiceOption("${AR}abdullah", "عبدالله", "ar-SA", "عربي — رجل"),
            VoiceOption("${AR}sultan", "سلطان", "ar-SA", "عربي — رجل"),
            VoiceOption("${AR}noura", "نورة", "ar-SA", "عربي — امرأة"),
            VoiceOption("${AR}lulwa", "لولوة", "ar-SA", "عربي — امرأة"),
            VoiceOption("${AR}aisha", "عائشة", "ar-SA", "عربي — امرأة"),
            VoiceOption("${EN}troy", "Troy", "en-US", "English — male"),
            VoiceOption("${EN}austin", "Austin", "en-US", "English — male"),
            VoiceOption("${EN}daniel", "Daniel", "en-US", "English — male"),
            VoiceOption("${EN}hannah", "Hannah", "en-US", "English — female"),
            VoiceOption("${EN}diana", "Diana", "en-US", "English — female"),
            VoiceOption("${EN}autumn", "Autumn", "en-US", "English — female"),
        )

        /** True when a voice speaks English, so replies must be English too. */
        fun isEnglishVoice(voice: String): Boolean = voice.startsWith(EN) || voice.endsWith("-en")

        /**
         * The model and voice for [voice]. An id from another provider (this
         * one is also a fallback for them) gets the default for the script
         * the text is written in, so Arabic is never read by an English voice.
         */
        fun specFor(voice: String, text: String): VoiceSpec {
            val known = VOICES.firstOrNull { it.id == voice }?.id
                ?: if (text.any { it in '؀'..'ۿ' }) DEFAULT_ARABIC else DEFAULT_ENGLISH
            return if (known.startsWith(EN)) {
                VoiceSpec(MODEL_ENGLISH, known.removePrefix(EN))
            } else {
                VoiceSpec(MODEL_ARABIC, known.removePrefix(AR))
            }
        }

        /** Loudest sample as 0..1; 0 means the audio is pure silence. */
        fun peakOf(pcm: ByteArray): Float {
            var peak = 0
            var index = 0
            while (index + 1 < pcm.size) {
                val sample = (pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)
                peak = maxOf(peak, kotlin.math.abs(sample))
                index += 2
            }
            return (peak / 32768f).coerceIn(0f, 1f)
        }

        fun buildBody(spec: VoiceSpec, input: String): String = JsonObject(
            mapOf(
                "model" to JsonPrimitive(spec.model),
                "voice" to JsonPrimitive(spec.voice),
                "input" to JsonPrimitive(input),
                "response_format" to JsonPrimitive("wav"),
            ),
        ).toString()

        /**
         * Cut [text] into pieces of at most [limit] characters, preferring
         * the end of a sentence, then a comma, then a space. A single word
         * longer than the limit is cut hard — it cannot be spoken otherwise.
         */
        fun splitForSpeech(text: String, limit: Int = MAX_CHARS): List<String> {
            val pieces = mutableListOf<String>()
            var rest = text.trim().replace(Regex("\\s+"), " ")
            while (rest.length > limit) {
                val window = rest.substring(0, limit)
                val cut = window.lastIndexOfAny(charArrayOf('.', '!', '?', '؟', '…')).takeIf { it >= limit / 3 }
                    ?: window.lastIndexOfAny(charArrayOf(',', '،', ';', '؛', ':')).takeIf { it >= limit / 3 }
                    ?: window.lastIndexOf(' ').takeIf { it > 0 }?.minus(1)
                    ?: (limit - 1)
                pieces += rest.substring(0, cut + 1).trim()
                rest = rest.substring(cut + 1).trim()
            }
            if (rest.isNotEmpty()) pieces += rest
            return pieces.filter { it.isNotEmpty() }
        }

        /**
         * Read a RIFF/WAVE body into mono 16-bit PCM, or null when it is not
         * audio this player can use.
         *
         * Chunks are walked rather than assuming a 44-byte header, since
         * encoders put extra chunks before the data. Integer PCM of any
         * width and floating point are all accepted and converted: which of
         * them the service sends is not documented, and a speech model's
         * natural output is float.
         */
        fun parseWav(bytes: ByteArray): Wav? {
            if (bytes.size < 12) return null
            if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            var format = 0
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val tag = String(bytes, offset, 4, Charsets.US_ASCII)
                val declared = buffer.getInt(offset + 4)
                val body = offset + 8
                // A streamed WAV declares 0 or -1 for a length it did not know yet.
                val size = if (declared <= 0 && tag == "data" || declared < 0 || body + declared > bytes.size) {
                    bytes.size - body
                } else {
                    declared
                }

                if (tag == "fmt " && size >= 16) {
                    format = buffer.getShort(body).toInt() and 0xFFFF
                    channels = buffer.getShort(body + 2).toInt()
                    sampleRate = buffer.getInt(body + 4)
                    bits = buffer.getShort(body + 14).toInt()
                    // WAVE_FORMAT_EXTENSIBLE keeps the real format in a sub-format field.
                    if (format == 0xFFFE && size >= 26) format = buffer.getShort(body + 24).toInt() and 0xFFFF
                } else if (tag == "data") {
                    val frameBytes = bits / 8 * channels
                    if (sampleRate <= 0 || channels !in 1..2 || frameBytes <= 0) return null
                    val pcm = toMono16(buffer, body, size / frameBytes, channels, bits, format) ?: return null
                    return if (pcm.isEmpty()) null else Wav(sampleRate, pcm)
                }
                offset = body + size + (size and 1) // chunks are word-aligned
            }
            return null
        }

        private const val FORMAT_PCM = 1
        private const val FORMAT_FLOAT = 3

        /** Convert [frames] frames starting at [start] to 16-bit mono, or null for an unknown encoding. */
        private fun toMono16(
            buffer: ByteBuffer,
            start: Int,
            frames: Int,
            channels: Int,
            bits: Int,
            format: Int,
        ): ByteArray? {
            val read: (Int) -> Float = when {
                format == FORMAT_PCM && bits == 16 -> { at -> buffer.getShort(at) / 32768f }
                format == FORMAT_PCM && bits == 8 -> { at -> ((buffer.get(at).toInt() and 0xFF) - 128) / 128f }
                format == FORMAT_PCM && bits == 24 -> { at ->
                    ((buffer.get(at + 2).toInt() shl 16) or
                        ((buffer.get(at + 1).toInt() and 0xFF) shl 8) or
                        (buffer.get(at).toInt() and 0xFF)) / 8388608f
                }
                format == FORMAT_PCM && bits == 32 -> { at -> buffer.getInt(at) / 2147483648f }
                format == FORMAT_FLOAT && bits == 32 -> { at -> buffer.getFloat(at) }
                format == FORMAT_FLOAT && bits == 64 -> { at -> buffer.getDouble(at).toFloat() }
                else -> return null
            }
            val step = bits / 8
            val out = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channels) sum += read(start + (frame * channels + channel) * step)
                out.putShort((sum / channels * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            return out.array()
        }

        /**
         * Say why a request failed, using Groq's own words where they help.
         *
         * The Orpheus models refuse every request until their terms have
         * been accepted once in the Groq console, which looks like a dead
         * voice from inside the app — so that case names the fix.
         */
        fun describe(code: Int, body: String, model: String): String {
            val error = runCatching {
                (Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject
            }.getOrNull()
            val errorCode = (error?.get("code") as? JsonPrimitive)?.content.orEmpty()
            val message = (error?.get("message") as? JsonPrimitive)?.content.orEmpty()

            return when {
                errorCode == "model_terms_required" || message.contains("terms", ignoreCase = true) ->
                    "صوت Groq يحتاج قبول شروط النموذج مرّة واحدة: افتح " +
                        "console.groq.com/playground?model=$model بحسابك واقبل الشروط، ثم أعد المحاولة"
                code == 401 || code == 403 -> "مفتاح Groq غير صحيح أو منتهي"
                code == 429 -> "تجاوزت حدّ النطق في Groq، حاول بعد قليل"
                code in 500..599 -> "خدمة النطق في Groq غير متاحة حاليًا"
                message.isNotBlank() -> "رفضت Groq النطق: ${redact(message).take(160)}"
                else -> "فشل النطق عبر Groq (HTTP $code)"
            }
        }
    }
}
