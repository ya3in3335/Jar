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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Microphone loudness, for the orb.
 *
 * Reads raw PCM and reports a 0..1 level roughly 60 times a second. It only
 * measures loudness — nothing is recorded, buffered or sent anywhere, and
 * the stream stops the moment the flow is cancelled.
 */
@Singleton
class AmplitudeMeter @Inject constructor(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Emit the microphone level until cancelled.
     *
     * Emits nothing at all when the permission is missing, rather than
     * emitting zeros that would look like a working but silent microphone.
     */
    @SuppressLint("MissingPermission") // checked above; flow closes when absent
    fun levels(): Flow<Float> = callbackFlow {
        if (!hasPermission()) {
            close(SecurityException("RECORD_AUDIO not granted"))
            return@callbackFlow
        }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            close(IllegalStateException("no usable microphone configuration"))
            return@callbackFlow
        }
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 10 * 2)

        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            close(IllegalStateException("microphone busy"))
            return@callbackFlow
        }

        val samples = ShortArray(CHUNK)
        var smoothed = 0f

        val job = launch(Dispatchers.IO) {
            runCatching { recorder.startRecording() }.onFailure {
                close(it)
                return@launch
            }
            while (isActive) {
                val read = recorder.read(samples, 0, samples.size)
                if (read <= 0) continue

                var sumSquares = 0.0
                for (i in 0 until read) {
                    val v = samples[i].toDouble()
                    sumSquares += v * v
                }
                val rms = sqrt(sumSquares / read)

                // Loudness is logarithmic; a linear RMS barely moves for
                // speech and then saturates. Map dBFS onto a usable range.
                val level = if (rms < 1.0) {
                    0f
                } else {
                    val db = 20.0 * log10(rms / Short.MAX_VALUE.toDouble())
                    ((db - FLOOR_DB) / (0.0 - FLOOR_DB)).coerceIn(0.0, 1.0).toFloat()
                }

                // Fast attack, slow release, so the orb snaps to speech and
                // settles gently rather than flickering.
                val k = if (level > smoothed) 0.5f else 0.12f
                smoothed += (level - smoothed) * k

                trySend(smoothed)
            }
        }

        awaitClose {
            job.cancel()
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }.onFailure { Log.w(TAG, "stop failed: ${it.javaClass.simpleName}") }
            runCatching { recorder.release() }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val TAG = "AmplitudeMeter"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** ~16 ms of audio: fast enough to track syllables. */
        const val CHUNK = 256

        /** Quietest level treated as silence. */
        const val FLOOR_DB = -45.0
    }
}
