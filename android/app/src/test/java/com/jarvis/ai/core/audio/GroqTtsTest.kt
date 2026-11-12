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
        assertEquals(200.0, mono.getShort().toDouble(), 1.0)
        assertEquals(0.0, mono.getShort().toDouble(), 1.0)
    }

    @Test
    fun `anything that is not a wav is refused`() {
        assertNull(GroqTtsProvider.parseWav("not audio at all".toByteArray()))
        assertNull(GroqTtsProvider.parseWav(ByteArray(4)))
    }

    private fun header(format: Int, channels: Int, rate: Int, bits: Int, dataSize: Int, declared: Int = dataSize): ByteBuffer {
        val out = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(-1).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(format.toShort()).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        out.put("data".toByteArray()).putInt(declared)
        return out
    }

    private fun samples(wav: GroqTtsProvider.Wav): List<Int> {
        val mono = ByteBuffer.wrap(wav.pcm).order(ByteOrder.LITTLE_ENDIAN)
        return List(wav.pcm.size / 2) { mono.getShort().toInt() }
    }

    @Test
    fun `32-bit float samples are converted to 16-bit`() {
        val out = header(format = 3, channels = 1, rate = 24_000, bits = 32, dataSize = 12)
        out.putFloat(0.5f).putFloat(-1f).putFloat(2f) // the last one is out of range
        val parsed = GroqTtsProvider.parseWav(out.array())!!
        assertEquals(24_000, parsed.sampleRate)
        assertEquals(listOf(16384, -32768, 32767), samples(parsed))
    }

    @Test
    fun `24-bit samples are converted to 16-bit`() {
        val out = header(format = 1, channels = 1, rate = 48_000, bits = 24, dataSize = 6)
        out.put(byteArrayOf(0, 0, 0x40)).put(byteArrayOf(0, 0, 0xC0.toByte())) // +0.5 and -0.5
        assertEquals(listOf(16384, -16384), samples(GroqTtsProvider.parseWav(out.array())!!))
    }

    @Test
    fun `a streamed wav with unknown lengths is read to the end`() {
        val out = header(format = 1, channels = 1, rate = 24_000, bits = 16, dataSize = 4, declared = -1)
        out.putShort(11).putShort(-12)
        assertEquals(listOf(11, -12), samples(GroqTtsProvider.parseWav(out.array())!!))
    }

    @Test
    fun `an unknown encoding is refused rather than played as noise`() {
        val out = header(format = 7, channels = 1, rate = 8_000, bits = 8, dataSize = 4) // mu-law
        assertNull(GroqTtsProvider.parseWav(out.array()))
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

class GroqErrorTest {

    private val model = GroqTtsProvider.MODEL_ARABIC

    @Test
    fun `unaccepted terms name the page where they are accepted`() {
        val body = """{"error":{"message":"The model requires terms acceptance.","type":"invalid_request_error","code":"model_terms_required"}}"""
        val text = GroqTtsProvider.describe(400, body, model)
        assertTrue(text.contains("console.groq.com/playground?model=$model"))
    }

    @Test
    fun `a bad key is reported as a bad key`() {
        assertTrue(GroqTtsProvider.describe(401, """{"error":{"message":"Invalid API Key"}}""", model).contains("مفتاح"))
    }

    @Test
    fun `any other refusal carries groq's own message`() {
        val text = GroqTtsProvider.describe(400, """{"error":{"message":"voice must be one of [fahad]"}}""", model)
        assertTrue(text.contains("voice must be one of"))
    }

    @Test
    fun `a body that is not json still yields a message`() {
        assertTrue(GroqTtsProvider.describe(418, "<html>teapot</html>", model).contains("418"))
    }
}
