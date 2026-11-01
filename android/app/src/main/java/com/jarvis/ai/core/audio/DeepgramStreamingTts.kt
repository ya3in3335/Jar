package com.jarvis.ai.core.audio

import android.util.Log
import com.jarvis.ai.core.data.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speech that starts before it has finished being made.
 *
 * The REST provider is three waits in a row: synthesise the whole
 * utterance, download the whole file, decode it, then play. Deepgram also
 * serves synthesis over a WebSocket that emits raw PCM as it is generated,
 * so audio can reach the speaker while the rest of the sentence is still
 * being synthesised. That turns the wait from "length of the sentence" into
 * "time to the first chunk".
 *
 * The socket is kept open between utterances. Reconnecting per sentence
 * would put the TLS handshake back in front of every chunk and undo most
 * of what this class is for.
 *
 * linear16 is the only sensible encoding here: the streaming endpoint emits
 * raw audio with no container, and AudioTrack takes PCM directly, so there
 * is no decoder in the path at all. Pitch and rate are applied with
 * AudioTrack's own playback params, the same honest substitute the REST
 * provider uses — Aura exposes no prosody controls.
 */
class DeepgramStreamingTts(
    private val http: OkHttpClient,
    private val secureStore: SecureStore,
) : TtsProvider {

    override val id = "deepgram-stream"
    override val label = "أصوات ديبڨرام (بثّ فوري)"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The live socket, keyed by the settings it was opened with. */
    @Volatile private var socket: WebSocket? = null
    @Volatile private var socketKey: String? = null
    @Volatile private var session: Session? = null

    @Volatile private var player: PcmPlayer? = null
    @Volatile var expressivity: Int = 0

    override suspend fun isAvailable(): Boolean = !secureStore.deepgramKey.isNullOrBlank()

    override suspend fun voices(): List<VoiceOption> = DeepgramTtsProvider.BUILT_IN_VOICES

    override suspend fun speak(
        text: String,
        voice: String,
        pitch: String,
        rate: String,
        onAmplitude: ((Float) -> Unit)?,
    ) {
        val key = secureStore.deepgramKey ?: throw TtsException("أضف مفتاح ديبڨرام من الإعدادات")
        val model = if (voice.startsWith("aura") || voice.startsWith("flux-")) {
            voice
        } else {
            DeepgramTtsProvider.DEFAULT_VOICE
        }

        val url = streamUrl(model, expressivity)
        val live = withContext(Dispatchers.IO) { connect(url, key) }

        val player = PcmPlayer(SAMPLE_RATE).apply { start(pitch, rate) }
        this.player = player
        val done = Channel<Result<Unit>>(Channel.CONFLATED)
        // A fresh sink per utterance: the socket outlives the sentence, but
        // a channel cannot be reopened once closed.
        val frames = live.openSink()

        val pump = scope.launch {
            try {
                for (chunk in frames) {
                    val bytes = chunk.toByteArray()
                    if (bytes.isEmpty()) continue
                    onAmplitude?.invoke(player.write(bytes))
                }
                done.trySend(Result.success(Unit))
            } catch (e: Throwable) {
                done.trySend(Result.failure(e))
            }
        }

        try {
            live.send(buildJsonObject { put("type", "Speak"); put("text", text) }.toString())
            live.send(FLUSH)

            // Flushed means synthesis is complete, not that playback is.
            live.awaitFlush()
            frames.close()
            done.receive().getOrThrow()

            if (player.bytesWritten == 0L) throw TtsException("لم تُرجع الخدمة صوتًا")
            player.drain()
        } finally {
            pump.cancel()
            onAmplitude?.invoke(0f)
            player.stop()
            if (this.player === player) this.player = null
            live.reset()
        }
    }

    // ------------------------------------------------------------- transport

    private fun connect(url: String, key: String): Session {
        val existing = session
        if (existing != null && socketKey == url && existing.isUsable) return existing

        closeSocket()

        val request = Request.Builder()
            .url(url)
            // A raw account key uses the Token scheme, not Bearer.
            .header("Authorization", "Token $key")
            .build()

        val fresh = Session()
        val ws = http.newWebSocket(request, fresh)
        fresh.attach(ws)
        socket = ws
        socketKey = url
        session = fresh
        return fresh
    }

    private fun closeSocket() {
        runCatching { socket?.close(1000, null) }
        socket = null
        socketKey = null
        session?.fail(TtsException("أُغلقت جلسة الصوت"))
        session = null
    }

    /**
     * One synthesis socket.
     *
     * Audio frames land on a channel so the consumer can write them to the
     * track at playback speed rather than buffering the whole utterance,
     * which is the entire point of streaming.
     */
    private inner class Session : WebSocketListener() {

        @Volatile private var sink: Channel<ByteString>? = null
        @Volatile private var ws: WebSocket? = null
        private val failed = AtomicBoolean(false)

        /**
         * Flush acknowledgements, as a channel rather than a continuation.
         *
         * The acknowledgement arrives on OkHttp's reader thread while the
         * speaking coroutine waits for it. Handing that thread a raw
         * continuation means resuming a coroutine from inside whatever lock
         * guards it, and the resumed code goes straight on to block on the
         * speaker. A conflated channel does the same handshake with none of
         * that: thread-safe by construction, and the sender never waits.
         */
        private val flushes = Channel<Result<Unit>>(Channel.CONFLATED)

        @Volatile private var error: Throwable? = null

        val isUsable: Boolean get() = !failed.get() && ws != null

        fun attach(socket: WebSocket) {
            ws = socket
        }

        /**
         * Where this utterance's frames go.
         *
         * Unbounded, because audio order is not negotiable: a bounded
         * channel would force either blocking the socket thread, which
         * stalls the whole connection, or an off-thread retry, which can
         * reorder frames and produce audible corruption. Pacing happens
         * where it belongs — at the blocking write into AudioTrack. The
         * backlog is bounded in practice by the chunk size the speech
         * pipeline cuts at, a few hundred kilobytes at worst.
         */
        fun openSink(): Channel<ByteString> {
            error?.let { throw it }
            val channel = Channel<ByteString>(Channel.UNLIMITED)
            sink = channel
            return channel
        }

        fun send(message: String) {
            error?.let { throw it }
            val target = ws ?: throw TtsException("لا توجد جلسة صوت")
            if (!target.send(message)) throw TtsException("تعذّر إرسال النصّ للنطق")
        }

        /** Wait for Deepgram to report the buffer flushed. */
        suspend fun awaitFlush() {
            error?.let { throw it }
            flushes.receive().getOrThrow()
        }

        /**
         * Ready the session for the next utterance on the same socket.
         *
         * Draining matters: a stale acknowledgement left in the channel
         * would satisfy the next sentence's wait immediately and cut it off
         * before a single byte had been synthesised.
         */
        fun reset() {
            sink = null
            while (flushes.tryReceive().isSuccess) Unit
        }

        fun fail(cause: Throwable) {
            if (!failed.compareAndSet(false, true)) return
            error = cause
            sink?.close(cause)
            sink = null
            flushes.trySend(Result.failure(cause))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            sink?.trySend(bytes)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val type = runCatching {
                (json.parseToJsonElement(text) as JsonObject)["type"]?.jsonPrimitive?.content
            }.getOrNull()

            when (type) {
                "Flushed" -> flushes.trySend(Result.success(Unit))
                "Warning" -> Log.w(TAG, "deepgram warning")
                "Error", "Fatal" -> fail(TtsException("خطأ من خدمة الصوت"))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val message = when (response?.code) {
                401, 403 -> "ديبڨرام رفض المفتاح"
                429 -> "تجاوزت حصّة ديبڨرام، حاول بعد قليل"
                400 -> "ديبڨرام رفض الطلب (تحقّق من اسم الصوت)"
                null -> "تعذّر الاتصال بخدمة الصوت"
                else -> "خطأ من خدمة الصوت (HTTP ${response.code})"
            }
            fail(TtsException(message, t))
            if (session === this) {
                socket = null
                socketKey = null
                session = null
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(TtsException("أُغلقت جلسة الصوت"))
            if (session === this) {
                socket = null
                socketKey = null
                session = null
            }
        }
    }

    // -------------------------------------------------------------- playback

    override fun stop() {
        player?.stop()
        player = null
    }

    override fun release() {
        stop()
        closeSocket()
        scope.cancel()
    }

    companion object {
        private const val TAG = "DeepgramStreamTts"
        private const val SAMPLE_RATE = 24_000

        const val STREAM_BASE = "wss://api.deepgram.com/v1/speak"
        const val STREAM_BASE_V2 = "wss://api.deepgram.com/v2/speak"

        private const val FLUSH = """{"type":"Flush"}"""

        /**
         * The streaming socket URL.
         *
         * encoding and sample_rate are explicit because the player is built
         * around them: a mismatch would play at the wrong speed rather than
         * failing loudly.
         */
        fun streamUrl(voice: String, expressivity: Int): String {
            val base = if (DeepgramTtsProvider.isFluxVoice(voice)) STREAM_BASE_V2 else STREAM_BASE
            val params = StringBuilder(
                "?model=$voice&encoding=linear16&sample_rate=$SAMPLE_RATE",
            )
            if (DeepgramTtsProvider.isFluxVoice(voice)) {
                params.append("&expressivity=").append(expressivity.coerceIn(-2, 2))
            }
            return base + params
        }

    }
}
