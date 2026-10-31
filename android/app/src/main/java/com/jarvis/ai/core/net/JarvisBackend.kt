package com.jarvis.ai.core.net

import com.jarvis.ai.core.data.SecureStore
import com.jarvis.ai.core.stt.SttSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls to our own backend: the pieces that need a credential the device
 * must never hold, such as the Deepgram account key.
 */
@Singleton
class JarvisBackend @Inject constructor(
    private val http: OkHttpClient,
    private val secureStore: SecureStore,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val isPaired: Boolean
        get() = !secureStore.backendUrl.isNullOrBlank() && !secureStore.deviceToken.isNullOrBlank()

    /** A short-lived recognition credential plus the URL to use it against. */
    suspend fun sttSession(language: String): SttSession = withContext(Dispatchers.IO) {
        val base = secureStore.backendUrl?.trimEnd('/')
            ?: throw BackendException("لم يُضبَط عنوان الخادم")
        val token = secureStore.deviceToken
            ?: throw BackendException("الجهاز غير مقترن بالخادم")

        val request = Request.Builder()
            .url("$base/v1/stt/token?language=$language")
            .header("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody(null))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw BackendException(describe(response.code, body))

            val parsed = json.parseToJsonElement(body) as JsonObject
            SttSession(
                accessToken = parsed["accessToken"]?.jsonPrimitive?.content.orEmpty(),
                expiresInSeconds = parsed["expiresIn"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                url = parsed["url"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }

    /** Languages the backend will accept, for the settings picker. */
    suspend fun sttLanguages(): List<Language> = withContext(Dispatchers.IO) {
        val base = secureStore.backendUrl?.trimEnd('/') ?: return@withContext emptyList()
        val token = secureStore.deviceToken ?: return@withContext emptyList()

        val request = Request.Builder()
            .url("$base/v1/stt/languages")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val parsed = json.parseToJsonElement(response.body?.string().orEmpty()) as JsonObject
                parsed["languages"]?.let { element ->
                    (element as? kotlinx.serialization.json.JsonArray)?.mapNotNull { item ->
                        val entry = item.jsonObject
                        Language(
                            code = entry["code"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            label = entry["label"]?.jsonPrimitive?.content.orEmpty(),
                            note = entry["note"]?.jsonPrimitive?.content.orEmpty(),
                        )
                    }
                } ?: emptyList()
            }
        }.getOrDefault(emptyList())
    }

    data class Language(val code: String, val label: String, val note: String)

    /** Turn a backend error code into something the user can act on. */
    private fun describe(code: Int, body: String): String {
        val backendCode = runCatching {
            (json.parseToJsonElement(body) as JsonObject)["error"]
                ?.jsonObject?.get("code")?.jsonPrimitive?.content
        }.getOrNull()

        return when (backendCode) {
            "stt_not_configured" -> "خدمة التعرّف غير مضبوطة على الخادم"
            "stt_key_invalid" -> "مفتاح خدمة التعرّف غير صحيح على الخادم"
            "stt_key_forbidden" -> "مفتاح الخادم يحتاج صلاحية أعلى لإصدار الرموز"
            "stt_rate_limited", "rate_limited" -> "تجاوزت حدّ الطلبات، حاول بعد قليل"
            "stt_unreachable" -> "تعذّر وصول الخادم إلى خدمة التعرّف"
            "token_expired" -> "انتهت صلاحية رمز الجهاز، أعد الإقران"
            "unauthorized" -> "الجهاز غير مُصرَّح له"
            else -> "تعذّر بدء التعرّف (HTTP $code)"
        }
    }
}

class BackendException(message: String) : RuntimeException(message)
