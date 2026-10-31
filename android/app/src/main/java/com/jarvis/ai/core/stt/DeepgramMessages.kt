package com.jarvis.ai.core.stt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsing of Deepgram's streaming messages.
 *
 * Kept apart from the socket so the turn-taking rules can be tested on the
 * JVM with no network: which message means "the user started", which means
 * "this is the sentence", and which is an interim guess to show and then
 * throw away.
 */
object DeepgramMessages {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Turn one socket message into an event, or null when it carries
     * nothing worth acting on (an empty interim, a metadata frame).
     */
    fun parse(raw: String): SttEvent? {
        val message = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return null

        return when (message["type"]?.jsonPrimitive?.content) {
            "SpeechStarted" -> SttEvent.SpeechStarted
            "UtteranceEnd" -> SttEvent.UtteranceEnd
            "Results" -> parseResults(message)
            "Error" -> SttEvent.Failed(
                message["description"]?.jsonPrimitive?.content ?: "خطأ من خدمة التعرّف",
                recoverable = false,
            )
            // Metadata, warnings and keep-alives carry no turn information.
            else -> null
        }
    }

    private fun parseResults(message: JsonObject): SttEvent? {
        val alternative = message["channel"]?.jsonObject
            ?.get("alternatives")?.jsonArray?.firstOrNull()?.jsonObject
            ?: return null

        val text = alternative["transcript"]?.jsonPrimitive?.content.orEmpty().trim()
        val isFinal = message["is_final"]?.jsonPrimitive?.booleanOrNull ?: false
        val speechFinal = message["speech_final"]?.jsonPrimitive?.booleanOrNull ?: false

        // An empty transcript is normal: Deepgram sends finals for silence.
        // Emitting those as a turn would run the agent on nothing.
        if (text.isEmpty()) return null

        // speech_final means the endpointer decided the sentence closed.
        // is_final alone only means this segment will not be revised, which
        // is not the same as the speaker having finished.
        return if (speechFinal) {
            SttEvent.Final(text, alternative["confidence"]?.jsonPrimitive?.floatOrNull)
        } else if (isFinal) {
            SttEvent.Partial(text)
        } else {
            SttEvent.Partial(text)
        }
    }

    /**
     * Accumulates a turn across messages.
     *
     * Deepgram can close a sentence in pieces: several segments marked
     * final, then an UtteranceEnd. Joining them is what turns "افتح"
     * and "يوتيوب" into one usable instruction.
     */
    class TurnAssembler {
        private val committed = StringBuilder()
        private var pending: String = ""

        /** Everything known about the current turn. */
        val text: String
            get() = listOf(committed.toString().trim(), pending.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" ")

        val isEmpty: Boolean get() = text.isBlank()

        /** An interim guess: shown, then replaced. */
        fun partial(value: String) {
            pending = value
        }

        /** A segment the recogniser will not revise. */
        fun commit(value: String) {
            if (value.isBlank()) return
            if (committed.isNotEmpty()) committed.append(' ')
            committed.append(value.trim())
            pending = ""
        }

        fun reset() {
            committed.setLength(0)
            pending = ""
        }

        /** Take the turn and clear it, so the next sentence starts clean. */
        fun take(): String {
            val value = text
            reset()
            return value
        }
    }

    /** Sent to close the stream politely so the last words are flushed. */
    const val CLOSE_MESSAGE = """{"type":"CloseStream"}"""

    /** Sent periodically so an idle socket is not dropped. */
    const val KEEPALIVE_MESSAGE = """{"type":"KeepAlive"}"""
}
