package com.jarvis.ai.core.net

import com.jarvis.ai.core.data.redact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenRouter over HTTPS.
 *
 * The key is held by the caller and sent per request. It is never written to
 * a log: failures are reported through [redact].
 */
@Singleton
class OpenRouterClient @Inject constructor(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /** One non-streaming turn. Used by the agent loop, which needs tool calls whole. */
    suspend fun complete(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        tools: List<JsonObject> = emptyList(),
        maxTokens: Int = 1024,
    ): ModelTurn = withContext(Dispatchers.IO) {
        val payload = OpenRouterParser.buildRequest(model, messages, tools, maxTokens, stream = false)
        val request = Request.Builder()
            .url("$BASE/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "JARVIS")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw mapHttpError(response.code, body)
            OpenRouterParser.parseTurn(json.parseToJsonElement(body) as JsonObject)
        }
    }

    /**
     * Stream a reply token by token, for a turn with no tool calls expected.
     * Tool-call fragments are accumulated and emitted once complete.
     */
    fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        tools: List<JsonObject> = emptyList(),
        maxTokens: Int = 1024,
    ): Flow<StreamEvent> = flow {
        val payload = OpenRouterParser.buildRequest(model, messages, tools, maxTokens, stream = true)
        val request = Request.Builder()
            .url("$BASE/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "JARVIS")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw mapHttpError(response.code, response.body?.string().orEmpty())
            }
            val source = response.body?.source() ?: throw IOException("empty response body")
            val pending = mutableMapOf<Int, MutableToolCall>()

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break

                val element = runCatching { json.parseToJsonElement(data) as JsonObject }.getOrNull() ?: continue
                OpenRouterParser.parseStreamDelta(element)?.let { emit(StreamEvent.Text(it)) }
                OpenRouterParser.mergeToolCallDeltas(element, pending)
            }

            val calls = pending.toSortedMap().values.mapNotNull { it.toToolCall() }
            if (calls.isNotEmpty()) emit(StreamEvent.Tools(calls))
            emit(StreamEvent.Done)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Verify a key and report what the chosen model can do.
     * Returns an Arabic description on success.
     */
    suspend fun testConnection(apiKey: String, model: String): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$BASE/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw mapHttpError(response.code, body)

                val all = ModelCapabilities.parseModels(json.parseToJsonElement(body) as JsonObject)
                val chosen = all.firstOrNull { it.id == model }
                when {
                    all.isEmpty() -> "المفتاح يعمل، لكن لم تُرجَع أي نماذج"
                    chosen == null -> "المفتاح يعمل. لكن النموذج '$model' غير موجود في القائمة (${all.size} نموذجًا متاحًا)"
                    !chosen.tools -> "المفتاح يعمل. لكن هذا النموذج لا يدعم استدعاء الأدوات، فستعمل المحادثة دون تنفيذ أوامر"
                    else -> "المفتاح يعمل، والنموذج يدعم استدعاء الأدوات (سياق ${chosen.contextLength} رمزًا)"
                }
            }
        }
    }

    /** Models that advertise tool support, for the settings picker. */
    suspend fun toolCapableModels(apiKey: String): List<ModelCapabilities.Caps> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw mapHttpError(response.code, body)
            ModelCapabilities.parseModels(json.parseToJsonElement(body) as JsonObject).filter { it.tools }
        }
    }

    /** Turn an HTTP failure into something the UI can show in Arabic. */
    private fun mapHttpError(code: Int, body: String): Exception {
        val detail = runCatching {
            OpenRouterParser.parseTurn(json.parseToJsonElement(body) as JsonObject)
        }.exceptionOrNull()?.message

        return when (code) {
            401, 403 -> OpenRouterException("مفتاح OpenRouter غير صحيح أو منتهي", "invalid_key")
            402 -> OpenRouterException("رصيد الحساب غير كافٍ لهذا النموذج", "insufficient_credits")
            404 -> OpenRouterException("النموذج غير موجود", "model_not_found")
            408, 504 -> OpenRouterException("انتهت المدّة المحدّدة", "timeout")
            429 -> OpenRouterException("تجاوزت حدّ الطلبات، حاول بعد قليل", "rate_limited")
            in 500..599 -> OpenRouterException("خدمة النموذج غير متاحة حاليًا", "provider_down")
            else -> OpenRouterException(
                detail?.let { redact(it) } ?: "فشل الطلب (HTTP $code)",
                "http_$code",
            )
        }
    }

    private companion object {
        const val BASE = "https://openrouter.ai/api/v1"
    }
}

sealed interface StreamEvent {
    data class Text(val delta: String) : StreamEvent
    data class Tools(val calls: List<ToolCall>) : StreamEvent
    data object Done : StreamEvent
}
