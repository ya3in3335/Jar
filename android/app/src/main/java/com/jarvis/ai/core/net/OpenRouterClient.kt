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
 * Chat completions over HTTPS, for every [ModelProvider].
 *
 * The class keeps its first name; it now serves Groq as well, since both
 * speak the same dialect.
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
        provider: ModelProvider,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        tools: List<JsonObject> = emptyList(),
        maxTokens: Int = 1024,
        toolChoice: String = "auto",
        extra: JsonObject? = null,
    ): ModelTurn = withContext(Dispatchers.IO) {
        requireAllowed(provider, model)
        val payload = OpenRouterParser.buildRequest(
            model, messages, tools, maxTokens, stream = false, toolChoice = toolChoice, extra = extra,
        )
        val request = Request.Builder()
            .url("${provider.baseUrl}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "JARVIS")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw mapHttpError(provider, response.code, body)
            OpenRouterParser.parseTurn(json.parseToJsonElement(body) as JsonObject)
        }
    }

    /**
     * Stream a reply token by token, for a turn with no tool calls expected.
     * Tool-call fragments are accumulated and emitted once complete.
     */
    fun stream(
        provider: ModelProvider,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        tools: List<JsonObject> = emptyList(),
        maxTokens: Int = 1024,
    ): Flow<StreamEvent> = flow {
        requireAllowed(provider, model)
        val payload = OpenRouterParser.buildRequest(model, messages, tools, maxTokens, stream = true)
        val request = Request.Builder()
            .url("${provider.baseUrl}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "JARVIS")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw mapHttpError(provider, response.code, response.body?.string().orEmpty())
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
    suspend fun testConnection(provider: ModelProvider, apiKey: String, model: String): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${provider.baseUrl}/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw mapHttpError(provider, response.code, body)

                val all = ModelCapabilities.parseModels(json.parseToJsonElement(body) as JsonObject, provider)
                val chosen = all.firstOrNull { it.id == model }
                when {
                    !provider.allows(model) -> "المفتاح يعمل. لكن '$model' نموذج مدفوع، وهذا التطبيق يستعمل نماذج ${provider.label} المجانية فقط"
                    all.isEmpty() -> "المفتاح يعمل، لكن لم تُرجَع أي نماذج"
                    chosen == null -> "المفتاح يعمل. لكن النموذج '$model' غير موجود في القائمة (${all.size} نموذجًا متاحًا)"
                    !chosen.tools -> "المفتاح يعمل. لكن هذا النموذج لا يدعم استدعاء الأدوات، فستعمل المحادثة دون تنفيذ أوامر"
                    else -> "المفتاح يعمل، والنموذج يدعم استدعاء الأدوات (سياق ${chosen.contextLength} رمزًا)"
                }
            }
        }
    }

    /** Models that advertise tool support, for the settings picker. */
    suspend fun toolCapableModels(
        provider: ModelProvider,
        apiKey: String,
    ): List<ModelCapabilities.Caps> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${provider.baseUrl}/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw mapHttpError(provider, response.code, body)
            ModelCapabilities.parseModels(json.parseToJsonElement(body) as JsonObject, provider)
                .filter { it.tools && provider.allows(it.id) }
        }
    }

    /** Turn an HTTP failure into something the UI can show in Arabic. */
    private fun mapHttpError(provider: ModelProvider, code: Int, body: String): Exception {
        val detail = runCatching {
            OpenRouterParser.parseTurn(json.parseToJsonElement(body) as JsonObject)
        }.exceptionOrNull()?.message

        return when (code) {
            401, 403 -> OpenRouterException("مفتاح ${provider.label} غير صحيح أو منتهي", "invalid_key")
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

    /** Refuse a paid model before anything is sent, so it can never be billed. */
    private fun requireAllowed(provider: ModelProvider, model: String) {
        if (!provider.allows(model)) {
            throw OpenRouterException(
                "النموذج '$model' مدفوع. هذا التطبيق يستعمل نماذج ${provider.label} المجانية فقط — اختر نموذجًا مجانيًا من الإعدادات",
                "paid_model_blocked",
            )
        }
    }
}

sealed interface StreamEvent {
    data class Text(val delta: String) : StreamEvent
    data class Tools(val calls: List<ToolCall>) : StreamEvent
    data object Done : StreamEvent
}
