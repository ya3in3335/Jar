package com.jarvis.ai.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cutting rules decide how fast the first sound arrives, which is what
 * the delay actually feels like.
 */
class SpeechCuttingTest {

    @Test
    fun `nothing is cut from a fragment`() {
        assertNull(SpeechPipeline.nextCut("افتح", isFirst = true))
    }

    @Test
    fun `the opener is cut at the first sentence end`() {
        val text = "تم فتح يوتيوب. والآن أبحث عن الفيديو"
        val cut = SpeechPipeline.nextCut(text, isFirst = true)!!
        assertEquals("تم فتح يوتيوب.", text.substring(0, cut))
    }

    @Test
    fun `an Arabic question mark ends a sentence`() {
        val text = "هل تريد أن أفتح يوتيوب؟ أخبرني"
        val cut = SpeechPipeline.nextCut(text, isFirst = true)!!
        assertTrue(text.substring(0, cut).endsWith("؟"))
    }

    @Test
    fun `an Arabic comma can end the opener`() {
        // Cutting on a comma gets sound out sooner, which matters most for
        // the very first chunk.
        val text = "حسنًا يا صديقي، سأفتح التطبيق الآن"
        val cut = SpeechPipeline.nextCut(text, isFirst = true)!!
        assertTrue(text.substring(0, cut).endsWith("،"))
    }

    @Test
    fun `a terminator too early does not cut`() {
        // "د." and similar must not trigger a one-word utterance.
        assertNull(SpeechPipeline.nextCut("د. ", isFirst = true))
    }

    @Test
    fun `later chunks need more before they cut`() {
        val text = "نعم. هذا"
        assertNull(SpeechPipeline.nextCut(text, isFirst = false))
        assertEquals(4, SpeechPipeline.nextCut(text, isFirst = true))
    }

    @Test
    fun `a rambling reply with no punctuation is still cut`() {
        val text = "كلمة ".repeat(80)
        val cut = SpeechPipeline.nextCut(text, isFirst = false)
        assertTrue("never cut", cut != null)
        assertTrue("cut too late", cut!! <= 220)
    }

    @Test
    fun `the hard cut falls on a space, not mid-word`() {
        val text = "كلمة ".repeat(80)
        val cut = SpeechPipeline.nextCut(text, isFirst = false)!!
        assertTrue(text[cut - 1] == ' ' || cut == 220)
    }

    @Test
    fun `a newline ends a chunk`() {
        val text = "تم التنفيذ بنجاح\nهل أتابع"
        val cut = SpeechPipeline.nextCut(text, isFirst = true)!!
        assertEquals("تم التنفيذ بنجاح", text.substring(0, cut).trim())
    }
}

class SpeechSplitTest {

    @Test
    fun `a reply becomes ordered chunks`() {
        val parts = SpeechPipeline.split("فتحت يوتيوب. وبحثت عن بايثون. هل أشغّل أوّل فيديو؟")
        assertEquals(3, parts.size)
        assertTrue(parts[0].startsWith("فتحت"))
        assertTrue(parts.last().endsWith("؟"))
    }

    @Test
    fun `nothing is lost when there is no final terminator`() {
        val parts = SpeechPipeline.split("تم. والباقي بلا نقطة")
        assertEquals("تم. والباقي بلا نقطة", parts.joinToString(" "))
    }

    @Test
    fun `a short reply stays whole`() {
        assertEquals(listOf("تم"), SpeechPipeline.split("تم"))
    }

    @Test
    fun `empty text yields nothing`() {
        assertTrue(SpeechPipeline.split("").isEmpty())
        assertTrue(SpeechPipeline.split("   ").isEmpty())
    }

    @Test
    fun `every character survives the split`() {
        val original = "فتحت يوتيوب. وبحثت عن بايثون، ثم شغّلت الفيديو؟ نعم"
        val rejoined = SpeechPipeline.split(original).joinToString(" ")
        for (word in listOf("فتحت", "يوتيوب", "بايثون", "الفيديو", "نعم")) {
            assertTrue("lost: $word", rejoined.contains(word))
        }
    }

    @Test
    fun `the first chunk is short so sound starts early`() {
        val parts = SpeechPipeline.split("حسنًا، سأفتح لك التطبيق وأبحث عن الفيديو المطلوب الآن.")
        assertTrue("first chunk too long: ${parts[0]}", parts[0].length < 40)
    }
}
