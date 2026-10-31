package com.jarvis.ai.core.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.jarvis.ai.core.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.coroutines.resume

/**
 * Speech through the JARVIS backend, which holds the Azure Speech
 * credentials and synthesises ar-DZ-IsmaelNeural.
 *
 * MP3 is written to a cache file and played from there rather than streamed,
 * because MediaPlayer cannot seek a chunked HTTP body reliably and a failed
 * seek mid-sentence is worse than a short wait.
 */
class RelayTtsProvider(
    private val http: OkHttpClient,
    private val secureStore: SecureStore,
    private val cacheDir: File,
) : TtsProvider {

    override val id = "relay"
    override val label = "صوت إسماعيل الجزائري (يحتاج إنترنت)"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    @Volatile private var player: MediaPlayer? = null

    override suspend fun isAvailable(): Boolean =
        !secureStore.backendUrl.isNullOrBlank() && !secureStore.deviceToken.isNullOrBlank()

    override suspend fun speak(
        text: String,
        voice: String,
        pitch: String,
        rate: String,
        onAmplitude: ((Float) -> Unit)?,
    ) {
        val base = secureStore.backendUrl?.trimEnd('/')
            ?: throw TtsException("لم يُضبَط عنوان الخادم")
        val token = secureStore.deviceToken ?: throw TtsException("الجهاز غير مقترن بالخادم")

        val audio = withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("text", text)
                put("voice", voice)
                put("pitch", pitch)
                put("rate", rate)
            }
            val request = Request.Builder()
                .url("$base/v1/voice/tts")
                .header("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody(jsonType))
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw TtsException(describe(response.code, response.body?.string()))
                }
                val bytes = response.body?.bytes() ?: throw TtsException("لم يُرجع الخادم صوتًا")
                if (bytes.isEmpty()) throw TtsException("لم يُرجع الخادم صوتًا")

                File(cacheDir, "jarvis-speech.mp3").apply { writeBytes(bytes) }
            }
        }

        playFile(audio)
    }

    private suspend fun playFile(file: File) = suspendCancellableCoroutine { continuation ->
        stop() // never let two utterances overlap

        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                if (continuation.isActive) continuation.resume(Unit)
            }
            setOnErrorListener { _, what, extra ->
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(TtsException("تعذّر تشغيل الصوت ($what/$extra)")))
                }
                true
            }
        }
        player = mp

        continuation.invokeOnCancellation { stop() }

        runCatching {
            mp.prepare()
            mp.start()
        }.onFailure { e ->
            if (continuation.isActive) {
                continuation.resumeWith(Result.failure(TtsException("تعذّر تهيئة الصوت", e)))
            }
        }
    }

    override fun stop() {
        val current = player ?: return
        player = null
        runCatching {
            if (current.isPlaying) current.stop()
        }
        runCatching { current.release() }
    }

    override fun release() = stop()

    override suspend fun voices(): List<VoiceOption> {
        val base = secureStore.backendUrl?.trimEnd('/') ?: return emptyList()
        val token = secureStore.deviceToken ?: return emptyList()

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$base/v1/voice/voices")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val body = json.parseToJsonElement(response.body?.string().orEmpty()) as JsonObject
                    body["voices"]?.jsonArray.orEmpty().mapNotNull { element ->
                        val v = element.jsonObject
                        VoiceOption(
                            id = v["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            name = v["name"]?.jsonPrimitive?.content ?: "",
                            locale = v["locale"]?.jsonPrimitive?.content ?: "",
                            localeName = v["localeName"]?.jsonPrimitive?.content ?: "",
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    /** Turn a backend error code into something actionable in Arabic. */
    private fun describe(code: Int, body: String?): String {
        val backendCode = runCatching {
            (json.parseToJsonElement(body.orEmpty()) as JsonObject)["error"]
                ?.jsonObject?.get("code")?.jsonPrimitive?.content
        }.getOrNull()

        return when (backendCode ?: "") {
            "voice_not_configured" -> "محرّك الصوت غير مضبوط على الخادم"
            "voice_auth_failed" -> "الخادم رفض مفتاح خدمة الصوت"
            "voice_rate_limited" -> "تجاوزت حصّة الصوت، حاول بعد قليل"
            "voice_timeout" -> "خدمة الصوت لم تستجب"
            "token_expired" -> "انتهت صلاحية رمز الجهاز، أعد الإقران"
            "unauthorized" -> "الجهاز غير مُصرَّح له"
            else -> "تعذّر توليد الصوت (HTTP $code)"
        }
    }
}

private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
    this ?: emptyList()
