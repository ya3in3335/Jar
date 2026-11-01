package com.jarvis.ai.core.audio

import com.jarvis.ai.core.data.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The Algerian neural voice, started before it has finished synthesising.
 *
 * Azure's REST endpoint returns audio chunked as it is generated, so the
 * only reason the other provider waits for the whole file is MediaPlayer:
 * it needs a seekable source, so the body has to land on disk first. Ask
 * for raw PCM instead and that constraint disappears — there is nothing to
 * decode and nothing to seek, so the bytes can go to the speaker as they
 * arrive off the socket.
 *
 * Prosody still goes through SSML here, which is the real thing rather
 * than a playback-rate substitute, so the player is left at its natural
 * speed and pitch.
 */
class AzureStreamingTts(
    private val http: OkHttpClient,
    private val secureStore: SecureStore,
) : TtsProvider {

    override val id = "azure-stream"
    override val label = "صوت إسماعيل الجزائري (بثّ فوري)"

    private val ssmlType = "application/ssml+xml".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var player: PcmPlayer? = null

    override suspend fun isAvailable(): Boolean =
        !secureStore.azureSpeechKey.isNullOrBlank() && !secureStore.azureRegion.isNullOrBlank()

    override suspend fun voices(): List<VoiceOption> = AzureTtsProvider.ARABIC_VOICES

    override suspend fun speak(
        text: String,
        voice: String,
        pitch: String,
        rate: String,
        onAmplitude: ((Float) -> Unit)?,
    ) {
        val key = secureStore.azureSpeechKey ?: throw TtsException("أضف مفتاح أزور من الإعدادات")
        val region = secureStore.azureRegion ?: throw TtsException("أضف منطقة أزور من الإعدادات")

        val request = Request.Builder()
            .url("https://$region.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", key)
            .header("X-Microsoft-OutputFormat", OUTPUT_FORMAT)
            .header("User-Agent", "jarvis")
            .post(buildSsml(text, voice, pitch, rate).toRequestBody(ssmlType))
            .build()

        // SSML already carries the pitch and rate, so the player stays
        // neutral — applying them twice would compound them.
        val player = PcmPlayer(SAMPLE_RATE).apply { start("", "") }
        this.player = player
        val done = Channel<Result<Unit>>(Channel.CONFLATED)

        // The read loop blocks on both the socket and the speaker, so it
        // cannot run on the caller's dispatcher.
        val pump = scope.launch {
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw TtsException(AzureTtsProvider.describe(response.code))
                    val source = response.body?.source() ?: throw TtsException("لم تُرجع الخدمة صوتًا")
                    val buffer = ByteArray(CHUNK)

                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        if (read == 0) continue
                        onAmplitude?.invoke(player.write(buffer, read))
                    }
                }
                done.trySend(Result.success(Unit))
            } catch (e: Throwable) {
                done.trySend(Result.failure(e))
            }
        }

        try {
            done.receive().getOrThrow()
            if (player.bytesWritten == 0L) throw TtsException("لم تُرجع الخدمة صوتًا")
            player.drain()
        } finally {
            pump.cancel()
            onAmplitude?.invoke(0f)
            player.stop()
            if (this.player === player) this.player = null
        }
    }

    override fun stop() {
        player?.stop()
        player = null
    }

    override fun release() {
        stop()
        scope.cancel()
    }

    companion object {
        private const val SAMPLE_RATE = 24_000

        /**
         * Raw PCM, matched to the player.
         *
         * The streaming point of the whole class: an MP3 body would need a
         * decoder, and a decoder needs a complete frame boundary, which
         * puts the wait back.
         */
        const val OUTPUT_FORMAT = "raw-24khz-16bit-mono-pcm"

        /** 8 KiB is about a sixth of a second here — small enough to start fast. */
        private const val CHUNK = 8 * 1024

        /** One SSML document, shared with the file-based provider. */
        fun buildSsml(text: String, voice: String, pitch: String, rate: String): String =
            AzureTtsProvider.buildSsml(text, voice, pitch, rate)
    }
}
