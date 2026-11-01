package com.jarvis.ai.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The level the streaming voices hand the orb.
 *
 * It has to come from the samples. An invented number would make the orb
 * move convincingly while bearing no relation to what is being heard,
 * which is exactly the kind of fake feedback this project refuses.
 */
class PcmPlayerTest {

    @Test
    fun `silence reads as zero`() {
        assertEquals(0f, PcmPlayer.levelOf(ByteArray(2048)), 0.001f)
    }

    @Test
    fun `a short or empty buffer is not a crash`() {
        assertEquals(0f, PcmPlayer.levelOf(ByteArray(0)), 0.001f)
        assertEquals(0f, PcmPlayer.levelOf(byteArrayOf(5)), 0.001f)
    }

    @Test
    fun `loud audio reads higher than quiet audio`() {
        assertTrue(
            "level must follow the samples",
            PcmPlayer.levelOf(pcm(20_000)) > PcmPlayer.levelOf(pcm(1_000)),
        )
    }

    @Test
    fun `the level stays inside the visualiser's range`() {
        // Full-scale audio with gain applied must not hand the orb a value
        // above 1, which would read as a permanently clipped ring.
        val level = PcmPlayer.levelOf(pcm(Short.MAX_VALUE.toInt()))
        assertTrue("got $level", level in 0f..1f)
    }

    @Test
    fun `only the bytes actually read are measured`() {
        // The streaming loop reuses one buffer, so the tail of it holds the
        // previous chunk. Measuring the whole array would mix stale audio
        // into the level on every short read.
        val buffer = pcm(Short.MAX_VALUE.toInt(), samples = 512)
        val quietPrefix = pcm(0, samples = 64)
        quietPrefix.copyInto(buffer)

        assertEquals(
            "a 64-sample read must ignore the stale remainder",
            0f,
            PcmPlayer.levelOf(buffer, length = 128),
            0.001f,
        )
    }

    @Test
    fun `little-endian sign is respected`() {
        // 0xFF 0xFF is -1, a near-silent sample. Reading it unsigned would
        // make it 65535 and peg the level at full scale.
        val negativeOne = ByteArray(256) { 0xFF.toByte() }
        assertTrue(
            "samples must be read as signed 16-bit",
            PcmPlayer.levelOf(negativeOne) < 0.01f,
        )
    }

    /** Little-endian 16-bit mono at a constant amplitude. */
    private fun pcm(amplitude: Int, samples: Int = 512): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val value = if (i % 2 == 0) amplitude else -amplitude
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = (value shr 8).toByte()
        }
        return out
    }
}
