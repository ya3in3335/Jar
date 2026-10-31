package com.jarvis.ai.core.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun results(
    transcript: String,
    isFinal: Boolean = false,
    speechFinal: Boolean = false,
    confidence: Double? = null,
): String {
    val conf = confidence?.let { ""","confidence":$it""" } ?: ""
    return """
        {"type":"Results","is_final":$isFinal,"speech_final":$speechFinal,
         "channel":{"alternatives":[{"transcript":"$transcript"$conf}]}}
    """.trimIndent()
}

class DeepgramParsingTest {

    @Test
    fun `speech started drives barge-in`() {
        assertEquals(SttEvent.SpeechStarted, DeepgramMessages.parse("""{"type":"SpeechStarted"}"""))
    }

    @Test
    fun `utterance end closes the turn`() {
        assertEquals(SttEvent.UtteranceEnd, DeepgramMessages.parse("""{"type":"UtteranceEnd"}"""))
    }

    @Test
    fun `interim transcript is a partial`() {
        val event = DeepgramMessages.parse(results("افتح", isFinal = false))
        assertTrue(event is SttEvent.Partial)
        assertEquals("افتح", (event as SttEvent.Partial).text)
    }

    @Test
    fun `is_final without speech_final is still only a partial`() {
        // is_final means this segment will not be revised. It does NOT mean
        // the speaker stopped, so acting on it would cut people off.
        val event = DeepgramMessages.parse(results("افتح يوتيوب", isFinal = true, speechFinal = false))
        assertTrue(event is SttEvent.Partial)
    }

    @Test
    fun `speech_final is the end of the sentence`() {
        val event = DeepgramMessages.parse(results("افتح يوتيوب", isFinal = true, speechFinal = true))
        assertTrue(event is SttEvent.Final)
        assertEquals("افتح يوتيوب", (event as SttEvent.Final).text)
    }

    @Test
    fun `confidence is carried when present`() {
        val event = DeepgramMessages.parse(
            results("مرحبا", isFinal = true, speechFinal = true, confidence = 0.97),
        )
        assertEquals(0.97f, (event as SttEvent.Final).confidence!!, 0.001f)
    }

    @Test
    fun `confidence is absent rather than invented`() {
        val event = DeepgramMessages.parse(results("مرحبا", isFinal = true, speechFinal = true))
        assertNull((event as SttEvent.Final).confidence)
    }

    @Test
    fun `empty transcript yields nothing`() {
        // Deepgram sends finals for silence; running the agent on those
        // would fire a turn on no words at all.
        assertNull(DeepgramMessages.parse(results("", isFinal = true, speechFinal = true)))
    }

    @Test
    fun `whitespace-only transcript yields nothing`() {
        assertNull(DeepgramMessages.parse(results("   ", isFinal = true, speechFinal = true)))
    }

    @Test
    fun `error message is surfaced`() {
        val event = DeepgramMessages.parse("""{"type":"Error","description":"bad audio"}""")
        assertTrue(event is SttEvent.Failed)
        assertEquals("bad audio", (event as SttEvent.Failed).message)
    }

    @Test
    fun `metadata is ignored`() {
        assertNull(DeepgramMessages.parse("""{"type":"Metadata","request_id":"abc"}"""))
    }

    @Test
    fun `malformed json does not crash the socket`() {
        assertNull(DeepgramMessages.parse("not json at all"))
        assertNull(DeepgramMessages.parse(""))
    }

    @Test
    fun `results with no alternatives is ignored`() {
        assertNull(DeepgramMessages.parse("""{"type":"Results","channel":{"alternatives":[]}}"""))
    }
}

class TurnAssemblerTest {

    @Test
    fun `starts empty`() {
        assertTrue(DeepgramMessages.TurnAssembler().isEmpty)
    }

    @Test
    fun `a partial is visible but replaceable`() {
        val turn = DeepgramMessages.TurnAssembler()
        turn.partial("افتح")
        assertEquals("افتح", turn.text)
        turn.partial("افتح يوتيوب")
        assertEquals("افتح يوتيوب", turn.text)
    }

    @Test
    fun `committed segments are joined into one instruction`() {
        // This is the case the whole class exists for: Deepgram closing a
        // sentence in pieces must not produce two separate commands.
        val turn = DeepgramMessages.TurnAssembler()
        turn.commit("افتح")
        turn.commit("يوتيوب")
        assertEquals("افتح يوتيوب", turn.text)
    }

    @Test
    fun `a partial after a commit extends rather than replaces`() {
        val turn = DeepgramMessages.TurnAssembler()
        turn.commit("افتح يوتيوب")
        turn.partial("وابحث عن")
        assertEquals("افتح يوتيوب وابحث عن", turn.text)
    }

    @Test
    fun `committing clears the pending guess`() {
        val turn = DeepgramMessages.TurnAssembler()
        turn.partial("افتح يوت")
        turn.commit("افتح يوتيوب")
        assertEquals("افتح يوتيوب", turn.text)
    }

    @Test
    fun `taking the turn empties it for the next sentence`() {
        val turn = DeepgramMessages.TurnAssembler()
        turn.commit("افتح يوتيوب")
        assertEquals("افتح يوتيوب", turn.take())
        assertTrue(turn.isEmpty)
    }

    @Test
    fun `blank commits are ignored`() {
        val turn = DeepgramMessages.TurnAssembler()
        turn.commit("   ")
        assertTrue(turn.isEmpty)
    }

    @Test
    fun `no stray spaces when only a partial exists`() {
        val turn = DeepgramMessages.TurnAssembler()
        turn.partial("مرحبا")
        assertEquals("مرحبا", turn.text)
    }

    @Test
    fun `reset discards everything`() {
        val turn = DeepgramMessages.TurnAssembler()
        turn.commit("افتح")
        turn.partial("يوتيوب")
        turn.reset()
        assertTrue(turn.isEmpty)
    }

    @Test
    fun `a full dictation sequence produces one instruction`() {
        val turn = DeepgramMessages.TurnAssembler()
        // What the socket actually delivers for one spoken sentence.
        for (message in listOf(
            results("افتح"),
            results("افتح يوتيوب"),
            results("افتح يوتيوب", isFinal = true),
            results("وابحث عن بايثون", isFinal = true, speechFinal = true),
        )) {
            when (val event = DeepgramMessages.parse(message)) {
                is SttEvent.Partial -> turn.partial(event.text)
                is SttEvent.Final -> turn.commit(event.text)
                else -> Unit
            }
        }
        assertEquals("وابحث عن بايثون", turn.text.takeLast(17))
        assertTrue(turn.text.contains("يوتيوب"))
    }
}

class SttSessionTest {

    @Test
    fun `a session with a token and a wss url is usable`() {
        assertTrue(SttSession("jwt", 600, "wss://api.deepgram.com/v1/listen?model=nova-3").isUsable)
    }

    @Test
    fun `a blank token is not usable`() {
        assertTrue(!SttSession("", 600, "wss://x").isUsable)
    }

    @Test
    fun `a plaintext url is not usable`() {
        // Audio must never leave the device unencrypted.
        assertTrue(!SttSession("jwt", 600, "ws://api.deepgram.com/v1/listen").isUsable)
    }
}
