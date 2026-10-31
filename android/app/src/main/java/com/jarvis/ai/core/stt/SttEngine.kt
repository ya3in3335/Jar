package com.jarvis.ai.core.stt

import kotlinx.coroutines.flow.Flow

/**
 * Live speech recognition, behind an interface.
 *
 * Deepgram streaming is the first implementation. The Voice Agent API —
 * which bundles recognition, model and speech into one socket — can be a
 * second one without anything above this changing.
 */
interface SttEngine {

    val id: String
    val label: String

    /**
     * Start listening. The flow completes when [stop] is called and emits
     * [SttEvent.Failed] rather than throwing, so a dropped connection is
     * something the UI shows instead of a crash.
     */
    fun listen(language: String): Flow<SttEvent>

    fun stop()
}

sealed interface SttEvent {
    /** The socket is open and audio is being sent. */
    data object Ready : SttEvent

    /** The speaker started. Used to interrupt playback (barge-in). */
    data object SpeechStarted : SttEvent

    /** Words so far. Replaced by later interim results. */
    data class Partial(val text: String) : SttEvent

    /**
     * A segment the recogniser will not revise, in an utterance that is
     * still going. It must be kept: treating it as a partial loses it when
     * the next segment arrives, which eats the first half of any sentence
     * Deepgram closes in pieces.
     */
    data class Segment(val text: String) : SttEvent

    /** A completed utterance. This is what gets acted on. */
    data class Final(val text: String, val confidence: Float?) : SttEvent

    /** The speaker stopped and nothing more is coming for this turn. */
    data object UtteranceEnd : SttEvent

    /** Recognition stopped for a reason worth showing, in Arabic. */
    data class Failed(val message: String, val recoverable: Boolean) : SttEvent
}

/** Credentials and settings the backend hands out for one session. */
data class SttSession(
    val accessToken: String,
    val expiresInSeconds: Int,
    val url: String,
) {
    val isUsable: Boolean get() = accessToken.isNotBlank() && url.startsWith("wss://")
}
