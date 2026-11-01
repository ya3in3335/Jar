package com.jarvis.ai.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in two rules that would be easy to break by accident later.
 */
class VoiceProviderPolicyTest {

    @Test
    fun `every offered Azure voice is Arabic`() {
        for (voice in AzureTtsProvider.ARABIC_VOICES) {
            assertTrue("not Arabic: ${voice.id}", voice.locale.startsWith("ar"))
            assertTrue("unnamed: ${voice.id}", voice.name.isNotBlank())
        }
    }

    @Test
    fun `the Algerian male voice is offered first`() {
        assertEquals("ar-DZ-IsmaelNeural", AzureTtsProvider.ARABIC_VOICES.first().id)
    }

    @Test
    fun `no voice belongs to a Deepgram model family`() {
        // Deepgram has no Arabic voice at all, so one appearing here would
        // make JARVIS answer in English.
        for (voice in AzureTtsProvider.ARABIC_VOICES) {
            assertFalse(voice.id.startsWith("aura"))
            assertFalse(voice.id.startsWith("flux-"))
            assertFalse(voice.id.contains("deepgram", ignoreCase = true))
        }
    }

    @Test
    fun `injected markup cannot add a second voice`() {
        val ssml = AzureTtsProvider.buildSsml(
            """</prosody></voice><voice name="aura-asteria-en">hacked""",
            "ar-DZ-IsmaelNeural",
            "-15Hz",
            "-8%",
        )
        assertEquals(1, ssml.split("<voice name=").size - 1)
        assertTrue(ssml.contains("&lt;/prosody&gt;"))
    }

    @Test
    fun `ssml locale follows the chosen voice`() {
        assertTrue(
            AzureTtsProvider.buildSsml("مرحبا", "ar-SA-HamedNeural", "0Hz", "0%")
                .contains("""xml:lang="ar-SA""""),
        )
    }

    @Test
    fun `the streaming provider asks for the format its player expects`() {
        // raw PCM is what makes it streamable: an MP3 body would need a
        // decoder, and that puts the whole-sentence wait straight back.
        assertEquals("raw-24khz-16bit-mono-pcm", AzureStreamingTts.OUTPUT_FORMAT)
    }

    @Test
    fun `both Azure providers build the same SSML`() {
        // The streaming path must not quietly lose the prosody or the
        // escaping that the file-based one got right.
        val args = arrayOf("مرحبا", "ar-DZ-IsmaelNeural", "-15Hz", "-8%")
        assertEquals(
            AzureTtsProvider.buildSsml(args[0], args[1], args[2], args[3]),
            AzureStreamingTts.buildSsml(args[0], args[1], args[2], args[3]),
        )
    }

    @Test
    fun `arabic text passes through unescaped`() {
        assertEquals("مرحبا بك", AzureTtsProvider.escapeXml("مرحبا بك"))
    }
}
