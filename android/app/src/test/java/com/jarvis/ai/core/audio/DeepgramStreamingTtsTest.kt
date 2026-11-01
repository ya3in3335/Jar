package com.jarvis.ai.core.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streaming socket's URL contract.
 *
 * A wrong sample_rate does not fail the request — it plays the voice at
 * the wrong speed, which is harder to diagnose than an outright error.
 */
class DeepgramStreamingTtsTest {

    @Test
    fun `aura voices stream from v1`() {
        val url = DeepgramStreamingTts.streamUrl("aura-2-zeus-en", expressivity = 0)
        assertTrue(url.startsWith("wss://api.deepgram.com/v1/speak?"))
        assertTrue(url.contains("model=aura-2-zeus-en"))
    }

    @Test
    fun `flux voices stream from v2`() {
        val url = DeepgramStreamingTts.streamUrl("flux-1-test", expressivity = 1)
        assertTrue(url.startsWith("wss://api.deepgram.com/v2/speak?"))
    }

    @Test
    fun `the player's format is the format requested`() {
        // The AudioTrack is built for 24 kHz 16-bit mono. If the URL ever
        // asks for something else the audio plays at the wrong pitch and
        // speed, with no error anywhere to explain why.
        val url = DeepgramStreamingTts.streamUrl("aura-2-zeus-en", expressivity = 0)
        assertTrue("streaming needs raw PCM", url.contains("encoding=linear16"))
        assertTrue("must match the AudioTrack", url.contains("sample_rate=24000"))
    }

    @Test
    fun `expressivity is sent only for flux`() {
        assertTrue(DeepgramStreamingTts.streamUrl("flux-1-test", 2).contains("expressivity=2"))
        assertTrue(
            "Aura-2 has no expressivity, so sending it would be a lie",
            !DeepgramStreamingTts.streamUrl("aura-2-zeus-en", 2).contains("expressivity"),
        )
    }

    @Test
    fun `expressivity is clamped to the documented range`() {
        assertTrue(DeepgramStreamingTts.streamUrl("flux-1-test", 9).contains("expressivity=2"))
        assertTrue(DeepgramStreamingTts.streamUrl("flux-1-test", -9).contains("expressivity=-2"))
    }

}
