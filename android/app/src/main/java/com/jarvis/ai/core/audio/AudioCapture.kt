package com.jarvis.ai.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The single microphone reader.
 *
 * Android gives one app one capture stream in practice, so the orb's level
 * meter and the speech recogniser cannot each open their own AudioRecord —
 * the second one gets silence or fails outright. This owns the recorder and
 * fans the same frames out to every consumer.
 *
 * Nothing is stored: frames are emitted and dropped.
 */
@Singleton
class AudioCapture @Inject constructor(private val context: Context) {

    /** One 20 ms frame of 16-bit mono PCM at 16 kHz, plus its loudness. */
    data class Frame(val pcm: ByteArray, val level: Float) {
        // ByteArray needs these for data-class equality to mean anything.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Frame && pcm.contentEquals(other.pcm) && level == other.level)

        override fun hashCode(): Int = 31 * pcm.contentHashCode() + level.hashCode()
    }

    private val _frames = MutableSharedFlow<Frame>(
        replay = 0,
        extraBufferCapacity = 32,
        // A slow consumer must never stall the recorder, or the recogniser
        // falls behind the speaker and the whole turn drifts.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<Frame> = _frames.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    var lastError: String? = null
        private set

    val isRunning: Boolean get() = job?.isActive == true

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Begin capturing. Safe to call when already running. */
    @SuppressLint("MissingPermission") // checked here; start fails cleanly otherwise
    fun start(): Boolean {
        if (isRunning) return true
        if (!hasPermission()) {
            lastError = "إذن الميكروفون غير ممنوح"
            return false
        }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            lastError = "لا توجد إعدادات ميكروفون مدعومة"
            return false
        }

        val recorder = try {
            AudioRecord(
                // VOICE_RECOGNITION applies the platform's own noise
                // suppression and skips the echo path meant for calls.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                maxOf(minBuffer, FRAME_BYTES * 8),
            )
        } catch (e: Exception) {
            lastError = "تعذّر فتح الميكروفون: ${e.javaClass.simpleName}"
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            lastError = "الميكروفون مشغول بتطبيق آخر"
            return false
        }

        lastError = null
        job = scope.launch {
            val buffer = ByteArray(FRAME_BYTES)
            try {
                recorder.startRecording()
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    val frame = buffer.copyOf(read) // consumers outlive the buffer
                    _frames.emit(Frame(frame, levelOf(frame, read)))
                }
            } catch (e: Exception) {
                lastError = "انقطع الميكروفون: ${e.javaClass.simpleName}"
                Log.w(TAG, "capture stopped", e)
            } finally {
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                }
                runCatching { recorder.release() }
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 20 ms at 16 kHz, 16-bit mono. */
        const val FRAME_BYTES = 640

        /** Quietest level treated as silence. */
        const val FLOOR_DB = -45.0

        /**
         * Loudness on a 0..1 scale.
         *
         * Mapped through dBFS rather than linear RMS: linear barely moves
         * for speech and then saturates, so the orb would sit nearly still
         * and then slam to full.
         */
        fun levelOf(frame: ByteArray, length: Int): Float {
            var sumSquares = 0.0
            var i = 0
            while (i + 1 < length) {
                // Little-endian 16-bit, sign-extended.
                val sample = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toShort()
                val v = sample.toDouble()
                sumSquares += v * v
                i += 2
            }
            val count = length / 2
            if (count == 0) return 0f
            val rms = sqrt(sumSquares / count)
            if (rms < 1.0) return 0f
            val db = 20.0 * log10(rms / Short.MAX_VALUE.toDouble())
            return ((db - FLOOR_DB) / (0.0 - FLOOR_DB)).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
