package com.jarvis.ai.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GroqVoicesTest {

    @Test
    fun `six arabic and six english voices are offered, arabic first`() {
        val voices = GroqTtsProvider.VOICES
        assertEquals(6, voices.count { it.locale.startsWith("ar") })
        assertEquals(6, voices.count { it.locale.startsWith("en") })
        assertTrue(voices.first().locale.startsWith("ar"))
        assertEquals(voices.size, voices.map { it.id }.toSet().size)
    }

    @Test
    fun `the voice id picks the model and the bare voice name`() {
        assertEquals(
            GroqTtsProvider.VoiceSpec(GroqTtsProvider.MODEL_ARABIC, "fahad"),
            GroqTtsProvider.specFor("groq-ar-fahad", "مرحبا"),
        )
        assertEquals(
            GroqTtsProvider.VoiceSpec(GroqTtsProvider.MODEL_ENGLISH, "troy"),
            GroqTtsProvider.specFor("groq-en-troy", "hello"),
        )
    }

    @Test
    fun `another provider's voice falls back by the script of the text`() {
        assertEquals(GroqTtsProvider.MODEL_ARABIC, GroqTtsProvider.specFor("ar-DZ-IsmaelNeural", "مرحبا بك").model)
        assertEquals(GroqTtsProvider.MODEL_ENGLISH, GroqTtsProvider.specFor("ar-DZ-IsmaelNeural", "hello there").model)
    }

    @Test
    fun `only english voices make the reply english`() {
        assertTrue(GroqTtsProvider.isEnglishVoice("groq-en-hannah"))
        assertFalse(GroqTtsProvider.isEnglishVoice("groq-ar-noura"))
        assertFalse(GroqTtsProvider.isEnglishVoice("ar-DZ-IsmaelNeural"))
    }

    @Test
    fun `the request asks for wav with the model and voice`() {
        val body = GroqTtsProvider.buildBody(GroqTtsProvider.VoiceSpec("m", "v"), "قل \"مرحبا\"")
        assertTrue(body.contains("\"model\":\"m\""))
        assertTrue(body.contains("\"voice\":\"v\""))
        assertTrue(body.contains("\"response_format\":\"wav\""))
        // Quotes in the text must be escaped, not break the JSON.
        assertTrue(body.contains("\\\"مرحبا\\\""))
    }
}

class GroqSplitTest {

    @Test
    fun `short text is one piece`() {
        assertEquals(listOf("مرحبا بك."), GroqTtsProvider.splitForSpeech("  مرحبا   بك.  "))
    }

    @Test
    fun `blank text yields nothing`() {
        assertTrue(GroqTtsProvider.splitForSpeech("   ").isEmpty())
    }

    @Test
    fun `no piece exceeds the limit and nothing is lost`() {
        val text = (1..60).joinToString(" ") { "كلمة$it" } + ". " + (1..60).joinToString(" ") { "word$it" } + "?"
        val pieces = GroqTtsProvider.splitForSpeech(text)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= GroqTtsProvider.MAX_CHARS })
        assertEquals(text.replace(" ", ""), pieces.joinToString("").replace(" ", ""))
    }

    @Test
    fun `a sentence end is preferred over a mid-sentence cut`() {
        val first = "هذه جملة أولى طويلة نسبيًا تنتهي هنا."
        val text = first + " " + "ثم ".repeat(40)
        assertEquals(first, GroqTtsProvider.splitForSpeech(text, limit = 60).first())
    }

    @Test
    fun `a single over-long word is cut hard rather than dropped`() {
        val pieces = GroqTtsProvider.splitForSpeech("a".repeat(450), limit = 200)
        assertEquals(listOf(200, 200, 50), pieces.map { it.length })
    }
}

class GroqWavTest {

    private fun wav(rate: Int, channels: Int, bits: Int, samples: ShortArray, extraChunk: Boolean = false): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val extra = if (extraChunk) 8 + 6 else 0
        val out = ByteBuffer.allocate(44 + extra + data.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + extra + data.capacity()).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        if (extraChunk) out.put("LIST".toByteArray()).putInt(6).put(ByteArray(6))
        out.put("data".toByteArray()).putInt(data.capacity()).put(data.array())
        return out.array()
    }

    @Test
    fun `sample rate and samples are read from the header`() {
        val parsed = GroqTtsProvider.parseWav(wav(24_000, 1, 16, shortArrayOf(1, -2, 3)))
        assertNotNull(parsed)
        assertEquals(24_000, parsed!!.sampleRate)
        assertEquals(6, parsed.pcm.size)
    }

    @Test
    fun `a chunk before the data is skipped`() {
        val parsed = GroqTtsProvider.parseWav(wav(48_000, 1, 16, shortArrayOf(7, 8), extraChunk = true))
        assertEquals(48_000, parsed!!.sampleRate)
        assertEquals(4, parsed.pcm.size)
    }

    @Test
    fun `stereo is mixed down to mono`() {
        val parsed = GroqTtsProvider.parseWav(wav(24_000, 2, 16, shortArrayOf(100, 300, -50, 50)))!!
        val mono = ByteBuffer.wrap(parsed.pcm).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(200, mono.getShort().toInt())
        assertEquals(0, mono.getShort().toInt())
    }

    @Test
    fun `anything that is not 16-bit pcm wav is refused`() {
        assertNull(GroqTtsProvider.parseWav("not audio at all".toByteArray()))
        assertNull(GroqTtsProvider.parseWav(wav(24_000, 1, 8, shortArrayOf(1, 2))))
        assertNull(GroqTtsProvider.parseWav(ByteArray(4)))
    }
}

class PlaybackProsodyTest {

    @Test
    fun `rate and pitch map onto playback multipliers`() {
        assertEquals(0.92f, PcmPlayer.speedFor("-8%"), 0.001f)
        assertEquals(1.25f, PcmPlayer.speedFor("+25%"), 0.001f)
        assertEquals(0.85f, PcmPlayer.pitchFor("-15Hz"), 0.001f)
    }

    @Test
    fun `extreme and unreadable values stay in range`() {
        assertEquals(0.5f, PcmPlayer.speedFor("-900%"), 0.001f)
        assertEquals(2.0f, PcmPlayer.pitchFor("+500Hz"), 0.001f)
        assertEquals(1f, PcmPlayer.speedFor("fast"), 0.001f)
        assertEquals(1f, PcmPlayer.pitchFor(""), 0.001f)
    }
}
