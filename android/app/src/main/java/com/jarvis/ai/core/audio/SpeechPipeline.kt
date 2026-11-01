package com.jarvis.ai.core.audio

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Speaks a reply while it is still being written.
 *
 * Waiting for the whole answer, then the whole audio, then playing it, is
 * three waits in a row — and the user hears nothing through all of them.
 * This cuts the text into sentences as it streams, starts speaking the
 * first one immediately, and synthesises the rest behind it.
 *
 * The first chunk is deliberately allowed to be short: time to first sound
 * is what the delay feels like, and a three-word opener bought early is
 * worth more than a perfectly balanced sentence bought late.
 */
class SpeechPipeline(
    private val scope: CoroutineScope,
    private val speak: suspend (String) -> Unit,
    private val onError: (String) -> Unit = {},
) {

    private val chunks = Channel<String>(Channel.UNLIMITED)
    private val buffer = StringBuilder()
    private var started = false
    private var consumer: Job? = null

    /** Feed streamed text. Complete sentences are queued as they appear. */
    fun push(delta: String) {
        if (delta.isEmpty()) return
        ensureConsumer()
        buffer.append(delta)

        while (true) {
            val cut = nextCut(buffer.toString(), isFirst = !started) ?: break
            val chunk = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (chunk.isEmpty()) continue
            started = true
            chunks.trySend(chunk)
        }
    }

    /** No more text is coming: flush whatever is left and finish. */
    fun finish() {
        val tail = buffer.toString().trim()
        buffer.setLength(0)
        if (tail.isNotEmpty()) {
            ensureConsumer()
            chunks.trySend(tail)
        }
        chunks.close()
    }

    /** Abandon anything not yet spoken. */
    fun cancel() {
        buffer.setLength(0)
        consumer?.cancel()
        consumer = null
        // Drain so a later run does not inherit this one's sentences.
        while (chunks.tryReceive().isSuccess) Unit
    }

    /** Suspends until everything queued has been spoken. */
    suspend fun join() {
        consumer?.join()
    }

    private fun ensureConsumer() {
        if (consumer?.isActive == true) return
        consumer = scope.launch {
            for (chunk in chunks) {
                try {
                    speak(chunk)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One failed sentence must not silence the rest.
                    Log.w(TAG, "chunk failed: ${e.javaClass.simpleName}")
                    onError(e.message ?: "تعذّر نطق جزء من الردّ")
                }
            }
        }
    }

    companion object {
        private const val TAG = "SpeechPipeline"

        /**
         * Minimum characters before a terminator may end a chunk.
         *
         * Small on purpose: "تم." is a complete and useful first reply,
         * and a high floor would delay exactly the answers that should be
         * fastest. It is only here to stop an abbreviation like "د." from
         * becoming an utterance of its own.
         */
        private const val FIRST_MIN = 3

        /** Slightly higher later, to avoid a stream of tiny fragments. */
        private const val LATER_MIN = 12

        /** Cut here even mid-sentence, or a rambling reply never starts. */
        private const val HARD_MAX = 220

        /** Arabic and Latin sentence ends, plus the Arabic comma. */
        private val TERMINATORS = charArrayOf('.', '!', '?', '۔', '؟', '!', '\n', '،', ';', '؛', ':')

        /**
         * Where to cut [text], or null when it should keep buffering.
         *
         * Returns an index into [text] — the character *after* the chunk.
         */
        fun nextCut(text: String, isFirst: Boolean): Int? {
            val minimum = if (isFirst) FIRST_MIN else LATER_MIN

            for (i in text.indices) {
                if (text[i] !in TERMINATORS) continue
                // A terminator only ends a sentence once there is enough
                // before it; "د." in an abbreviation must not trigger.
                if (i + 1 < minimum) continue
                return i + 1
            }

            if (text.length >= HARD_MAX) {
                // Fall back to the last space so a word is not split.
                val space = text.lastIndexOf(' ', HARD_MAX)
                return if (space > minimum) space + 1 else HARD_MAX
            }
            return null
        }

        /** Split a finished text the same way, for the non-streaming path. */
        fun split(text: String): List<String> {
            val parts = mutableListOf<String>()
            var rest = text
            var first = true
            while (true) {
                val cut = nextCut(rest, first) ?: break
                val chunk = rest.substring(0, cut).trim()
                if (chunk.isNotEmpty()) {
                    parts += chunk
                    first = false
                }
                rest = rest.substring(cut)
            }
            rest.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
            return parts
        }
    }
}
