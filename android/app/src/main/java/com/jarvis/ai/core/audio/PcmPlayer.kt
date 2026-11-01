package com.jarvis.ai.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min

/**
 * Raw 16-bit mono PCM straight to the speaker, written as it arrives.
 *
 * Shared by every streaming voice, because the hard parts are the same
 * whatever produced the bytes: start playing before the audio is complete,
 * let the write block so the network is paced by the speaker rather than
 * by a buffer, and wait for the tail to actually come out before releasing
 * the track.
 *
 * No decoder is involved. That is the point: PCM in, sound out, nothing in
 * between to buffer a whole file first.
 */
class PcmPlayer(private val sampleRate: Int) {

    @Volatile private var track: AudioTrack? = null
    @Volatile private var written = 0L

    val bytesWritten: Long get() = written

    /**
     * Open the track and begin playing silence, ready for the first bytes.
     *
     * Pitch and rate are applied here because the cloud voices expose no
     * prosody controls of their own — this changes what is actually heard
     * instead of sending a parameter that is ignored.
     */
    fun start(pitch: String, rate: String) {
        stop()
        written = 0L

        val minimum = AudioTrack.getMinBufferSize(sampleRate, CHANNEL, ENCODING)
        // A quarter second of headroom: enough to ride out a slow chunk,
        // short enough that stopping is still immediate.
        val buffer = maxOf(minimum, sampleRate / 4 * 2)

        val built = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(sampleRate)
                    .setChannelMask(CHANNEL)
                    .build(),
            )
            .setBufferSizeInBytes(buffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        runCatching {
            built.playbackParams = built.playbackParams
                .setSpeed(DeepgramTtsProvider.speedFor(rate))
                .setPitch(DeepgramTtsProvider.pitchFor(pitch))
        }.onFailure { Log.w(TAG, "playback params unsupported: ${it.javaClass.simpleName}") }

        built.play()
        track = built
    }

    /**
     * Write [length] bytes and return their level, 0..1, for the visualiser.
     *
     * Blocking on purpose: back-pressure here paces the producer to real
     * playback speed, which is what keeps memory flat on a long reply.
     */
    fun write(bytes: ByteArray, length: Int = bytes.size): Float {
        if (length <= 0) return 0f
        val player = track ?: return 0f
        val count = player.write(bytes, 0, length, AudioTrack.WRITE_BLOCKING)
        if (count > 0) written += count
        return levelOf(bytes, length)
    }

    /**
     * Wait for what was written to reach the speaker.
     *
     * AudioTrack reports frames consumed, so this waits on the real
     * remainder rather than guessing a sleep — without it the last word of
     * every sentence is cut off when the track is released.
     */
    suspend fun drain() {
        val player = track ?: return
        val totalFrames = written / BYTES_PER_FRAME
        val deadline = System.currentTimeMillis() + DRAIN_CAP_MS
        while (System.currentTimeMillis() < deadline) {
            val played = runCatching { player.playbackHeadPosition.toLong() }
                .getOrDefault(totalFrames)
            if (played >= totalFrames) return
            val remainingMs = (totalFrames - played) * 1000 / sampleRate
            delay(min(remainingMs + POLL_MS, DRAIN_STEP_MS))
        }
    }

    fun stop() {
        val current = track ?: return
        track = null
        runCatching { current.pause() }
        runCatching { current.flush() }
        runCatching { current.release() }
    }

    companion object {
        private const val TAG = "PcmPlayer"
        private const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_FRAME = 2
        private const val POLL_MS = 20L
        private const val DRAIN_STEP_MS = 120L
        private const val DRAIN_CAP_MS = 30_000L

        /** Every 8th sample is plenty for a visualiser and far cheaper. */
        private const val STRIDE = 8
        private const val GAIN = 3.5f

        /**
         * A 0..1 level from real samples.
         *
         * Measured, not invented: this is the mean amplitude of the audio
         * about to be heard, which is what the orb should follow.
         */
        fun levelOf(pcm: ByteArray, length: Int = pcm.size): Float {
            val end = min(length, pcm.size)
            if (end < 2) return 0f
            var sum = 0L
            var count = 0
            var i = 0
            while (i + 1 < end) {
                val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                sum += abs(sample.toShort().toInt())
                count++
                i += 2 * STRIDE
            }
            if (count == 0) return 0f
            return ((sum.toFloat() / count) / Short.MAX_VALUE * GAIN).coerceIn(0f, 1f)
        }
    }
}
