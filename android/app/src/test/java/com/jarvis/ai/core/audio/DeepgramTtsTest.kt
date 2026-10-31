package com.jarvis.ai.core.audio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val json = Json { isLenient = true; ignoreUnknownKeys = true }
private fun obj(text: String) = json.parseToJsonElement(text) as JsonObject

class DeepgramTtsTest {

    @Test
    fun `request body carries the text as json`() {
        val body = DeepgramTtsProvider.buildBody("Hello there")
        assertEquals("""{"text":"Hello there"}""", body)
    }

    @Test
    fun `quotes in the text cannot break the body`() {
        val body = DeepgramTtsProvider.buildBody("""say "hi" now""")
        // It must still parse, with the text intact.
        assertEquals("""say "hi" now""", (obj(body)["text"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `arabic text survives the body unescaped into mojibake`() {
        val body = DeepgramTtsProvider.buildBody("مرحبا")
        assertEquals("مرحبا", (obj(body)["text"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `the default voice is one Deepgram documents`() {
        assertEquals("aura-2-thalia-en", DeepgramTtsProvider.DEFAULT_VOICE)
    }

    @Test
    fun `built-in voices are all aura models`() {
        // A guessed model id fails at the moment the user expects speech,
        // so only documented names are shipped.
        for (voice in DeepgramTtsProvider.BUILT_IN_VOICES) {
            assertTrue(voice.id.startsWith("aura"))
        }
    }

    @Test
    fun `model listing keeps only aura voices`() {
        val voices = DeepgramTtsProvider.parseVoices(
            obj(
                """{"tts":[
                   {"name":"Thalia","canonical_name":"aura-2-thalia-en","languages":["en"]},
                   {"name":"Nova","canonical_name":"nova-3","languages":["en"]}]}""",
            ),
        )
        assertEquals(listOf("aura-2-thalia-en"), voices.map { it.id })
    }

    @Test
    fun `an empty listing yields nothing rather than crashing`() {
        assertTrue(DeepgramTtsProvider.parseVoices(obj("{}")).isEmpty())
    }

    @Test
    fun `an entry with no name is skipped`() {
        val voices = DeepgramTtsProvider.parseVoices(obj("""{"tts":[{"languages":["en"]}]}"""))
        assertTrue(voices.isEmpty())
    }
}

class PlaybackProsodyTest {

    @Test
    fun `negative rate slows playback`() {
        assertEquals(0.92f, DeepgramTtsProvider.speedFor("-8%"), 0.001f)
    }

    @Test
    fun `positive rate speeds it up`() {
        assertEquals(1.25f, DeepgramTtsProvider.speedFor("+25%"), 0.001f)
    }

    @Test
    fun `lowered pitch maps below one`() {
        assertEquals(0.85f, DeepgramTtsProvider.pitchFor("-15Hz"), 0.001f)
    }

    @Test
    fun `values are clamped to what MediaPlayer accepts`() {
        assertEquals(0.5f, DeepgramTtsProvider.speedFor("-900%"), 0.001f)
        assertEquals(2.0f, DeepgramTtsProvider.pitchFor("+500Hz"), 0.001f)
    }

    @Test
    fun `unparseable values fall back to neutral`() {
        assertEquals(1f, DeepgramTtsProvider.speedFor("fast"), 0.001f)
        assertEquals(1f, DeepgramTtsProvider.pitchFor(""), 0.001f)
    }
}

class ReplyLanguageTest {

    @Test
    fun `an aura voice is English`() {
        assertTrue(DeepgramTtsProvider.isEnglishVoice("aura-2-thalia-en"))
    }

    @Test
    fun `the Algerian voice is not English`() {
        // If this ever returned true, replies would switch to English for
        // a voice that speaks Arabic perfectly well.
        assertFalse(DeepgramTtsProvider.isEnglishVoice("ar-DZ-IsmaelNeural"))
    }

    @Test
    fun `other Arabic voices are not English`() {
        for (voice in AzureTtsProvider.ARABIC_VOICES) {
            assertFalse(voice.id, DeepgramTtsProvider.isEnglishVoice(voice.id))
        }
    }
}
