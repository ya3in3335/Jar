package com.jarvis.ai.core.stt

import android.util.Log
import com.jarvis.ai.core.audio.AudioCapture
import com.jarvis.ai.core.net.JarvisBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deepgram streaming speech recognition.
 *
 * The account key is never here: the backend mints a short-lived token per
 * session and the device opens its own socket with that. Audio goes up as
 * raw PCM frames from the shared [AudioCapture]; turn boundaries come back
 * from Deepgram's own endpointer, which is faster and more accurate than
 * deciding locally.
 *
 * Arabic runs on Nova-3. Deepgram has no Arabic voice, so speech output
 * stays on our own engines — see DECISIONS.md D8.
 */
@Singleton
class DeepgramStt @Inject constructor(
    private val http: OkHttpClient,
    private val backend: JarvisBackend,
    private val capture: AudioCapture,
) : SttEngine {

    override val id = "deepgram"
    override val label = "ديبڨرام (تعرّف مباشر)"

    @Volatile private var socket: WebSocket? = null

    override fun listen(language: String): Flow<SttEvent> = callbackFlow {
        val session = try {
            backend.sttSession(language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trySend(SttEvent.Failed(e.message ?: "تعذّر الحصول على رمز التعرّف", recoverable = true))
            close()
            return@callbackFlow
        }

        if (!session.isUsable) {
            trySend(SttEvent.Failed("رمز التعرّف غير صالح", recoverable = true))
            close()
            return@callbackFlow
        }

        if (!capture.start()) {
            trySend(SttEvent.Failed(capture.lastError ?: "تعذّر فتح الميكروفون", recoverable = false))
            close()
            return@callbackFlow
        }

        val assembler = DeepgramMessages.TurnAssembler()

        val request = Request.Builder()
            .url(session.url)
            // A temporary token, not the account key.
            .header("Authorization", "Bearer ${session.accessToken}")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(SttEvent.Ready)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val event = DeepgramMessages.parse(text)) {
                    null -> Unit

                    is SttEvent.Partial -> {
                        assembler.partial(event.text)
                        trySend(SttEvent.Partial(assembler.text))
                    }

                    is SttEvent.Segment -> {
                        assembler.commit(event.text)
                        // Show the sentence growing; the turn is not over.
                        trySend(SttEvent.Partial(assembler.text))
                    }

                    is SttEvent.Final -> {
                        assembler.commit(event.text)
                        // Deepgram closed the sentence; hand it over whole.
                        trySend(SttEvent.Final(assembler.take(), event.confidence))
                    }

                    is SttEvent.UtteranceEnd -> {
                        // Anything still pending becomes the turn: this is
                        // the case where speech_final never arrived.
                        if (!assembler.isEmpty) {
                            trySend(SttEvent.Final(assembler.take(), null))
                        }
                        trySend(SttEvent.UtteranceEnd)
                    }

                    else -> trySend(event)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // The listen endpoint never sends binary; ignore rather than fail.
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = when (response?.code) {
                    401, 403 -> "انتهت صلاحية رمز التعرّف"
                    429 -> "تجاوزت حصّة خدمة التعرّف"
                    else -> "انقطع الاتصال بخدمة التعرّف"
                }
                Log.w(TAG, "socket failure: ${t.javaClass.simpleName}")
                trySend(SttEvent.Failed(message, recoverable = true))
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }

        val ws = http.newWebSocket(request, listener)
        socket = ws

        // Pump microphone frames up the socket.
        val pump = launch {
            capture.frames.collect { frame ->
                if (!isActive) return@collect
                ws.send(frame.pcm.toByteString())
            }
        }

        // Deepgram drops a socket that goes quiet; a keep-alive costs
        // nothing and avoids a reconnect mid-thought.
        val keepAlive = launch {
            while (isActive) {
                delay(KEEPALIVE_MS)
                ws.send(DeepgramMessages.KEEPALIVE_MESSAGE)
            }
        }

        awaitClose {
            pump.cancel()
            keepAlive.cancel()
            runCatching {
                // Ask for a flush so the last words are not lost.
                ws.send(DeepgramMessages.CLOSE_MESSAGE)
                ws.close(1000, "done")
            }
            socket = null
        }
    }

    override fun stop() {
        runCatching {
            socket?.send(DeepgramMessages.CLOSE_MESSAGE)
            socket?.close(1000, "stopped")
        }
        socket = null
    }

    private companion object {
        const val TAG = "DeepgramStt"
        const val KEEPALIVE_MS = 8_000L
    }
}
