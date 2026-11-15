package com.jarvis.ai.core.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A message in an OpenRouter conversation. */
sealed interface ChatMessage {
    fun toJson(): JsonObject

    data class System(val content: String) : ChatMessage {
        override fun toJson() = buildJsonObject {
            put("role", "system"); put("content", content)
        }
    }

    data class User(val content: String) : ChatMessage {
        override fun toJson() = buildJsonObject {
            put("role", "user"); put("content", content)
        }
    }

    /** An assistant turn, which may carry tool calls instead of text. */
    data class Assistant(val content: String?, val toolCalls: List<ToolCall> = emptyList()) : ChatMessage {
        override fun toJson() = buildJsonObject {
            put("role", "assistant")
            put("content", content ?: "")
            if (toolCalls.isNotEmpty()) {
                put(
                    "tool_calls",
                    buildJsonArray {
                        for (call in toolCalls) {
                            add(
                                buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", call.name)
                                            put("arguments", call.rawArguments)
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    /** The result of running one tool, answering a specific call id. */
    data class ToolReply(val callId: String, val name: String, val content: String) : ChatMessage {
        override fun toJson() = buildJsonObject {
            put("role", "tool")
            put("tool_call_id", callId)
            put("name", name)
            put("content", content)
        }
    }
}

data class ToolCall(
    val id: String,
    val name: String,
    /** Raw JSON text, exactly as the model produced it. */
    val rawArguments: String,
)

/** What one model turn produced. */
data class ModelTurn(
    val text: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
) {
    val wantsTools: Boolean get() = toolCalls.isNotEmpty()
}

/**
 * Parsing of OpenRouter's non-streaming chat completion response.
 *
 * Kept separate from the HTTP client so it can be tested on the JVM with no
 * network and no Android framework.
 */
object OpenRouterParser {

    fun buildRequest(
        model: String,
        messages: List<ChatMessage>,
        tools: List<JsonObject>,
        maxTokens: Int,
        stream: Boolean,
        toolChoice: String = "auto",
        /** Provider-specific top-level fields, e.g. Groq's `reasoning_effort`. */
        extra: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray { messages.forEach { add(it.toJson()) } })
        put("max_tokens", maxTokens)
        put("stream", stream)
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray { tools.forEach { add(it) } })
            put("tool_choice", toolChoice)
        }
        extra?.forEach { (key, value) -> put(key, value) }
    }

    /** Parse a completion body. Throws [OpenRouterException] on an error body. */
    fun parseTurn(body: JsonObject): ModelTurn {
        body["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.content ?: "unknown provider error"
            val code = error.jsonObject["code"]?.jsonPrimitive?.content
            throw OpenRouterException(message, code)
        }

        val choice = body["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw OpenRouterException("response contained no choices", null)
        val message = choice["message"]?.jsonObject

        val text = message?.get("content")?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }
        val calls = message?.get("tool_calls")?.jsonArray.orEmpty().mapNotNull { element ->
            val call = element.jsonObject
            val fn = call["function"]?.jsonObject ?: return@mapNotNull null
            val name = fn["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            ToolCall(
                id = call["id"]?.jsonPrimitive?.content ?: name,
                name = name,
                rawArguments = fn["arguments"]?.jsonPrimitive?.content ?: "{}",
            )
        }

        val usage = body["usage"]?.jsonObject
        return ModelTurn(
            text = text,
            toolCalls = calls,
            finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNullSafe(),
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        )
    }

    /**
     * Pull the text delta out of one streaming SSE `data:` payload.
     * Returns null for keep-alives, role-only openers and tool-call deltas.
     */
    fun parseStreamDelta(payload: JsonObject): String? =
        payload["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("delta")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNullSafe()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Accumulate streamed tool-call fragments. OpenRouter sends a tool call
     * across several deltas: the name arrives once, the arguments in pieces,
     * each tagged with an index.
     */
    fun mergeToolCallDeltas(payload: JsonObject, into: MutableMap<Int, MutableToolCall>) {
        val deltas = payload["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("delta")?.jsonObject
            ?.get("tool_calls")?.jsonArray ?: return

        for (element in deltas) {
            val delta = element.jsonObject
            val index = delta["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val slot = into.getOrPut(index) { MutableToolCall() }
            delta["id"]?.jsonPrimitive?.contentOrNullSafe()?.let { slot.id = it }
            delta["function"]?.jsonObject?.let { fn ->
                fn["name"]?.jsonPrimitive?.contentOrNullSafe()?.let { slot.name = it }
                fn["arguments"]?.jsonPrimitive?.contentOrNullSafe()?.let { slot.arguments.append(it) }
            }
        }
    }

    /** JSON null arrives as a primitive whose content is the string "null". */
    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (!isString && content == "null") null else content
}

class MutableToolCall(
    var id: String? = null,
    var name: String? = null,
    val arguments: StringBuilder = StringBuilder(),
) {
    fun toToolCall(): ToolCall? {
        val resolvedName = name ?: return null
        return ToolCall(
            id = id ?: resolvedName,
            name = resolvedName,
            rawArguments = arguments.toString().ifBlank { "{}" },
        )
    }
}

class OpenRouterException(message: String, val code: String?) : RuntimeException(message)

/**
 * Model capability hints.
 *
 * OpenRouter exposes `supported_parameters` per model. Tools are only
 * offered to a model that advertises them, because sending a tools array to
 * a model without support produces either an error or silent ignoring —
 * both worse than not trying (spec section 9).
 */
object ModelCapabilities {

    data class Caps(val id: String, val tools: Boolean, val vision: Boolean, val contextLength: Int)

    fun parseModels(body: JsonObject, provider: ModelProvider = ModelProvider.OPENROUTER): List<Caps> =
        if (provider == ModelProvider.GROQ) parseGroqModels(body) else parseOpenRouterModels(body)

    /**
     * Groq lists speech, guard and audio models next to the chat ones and has
     * no per-model capability field. Its documentation says every chat model
     * it hosts supports tool use, so the non-chat ones are dropped by name.
     */
    private fun parseGroqModels(body: JsonObject): List<Caps> =
        body["data"]?.jsonArray.orEmpty().mapNotNull { element ->
            val model = element.jsonObject
            val id = model["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (model["active"]?.jsonPrimitive?.content == "false") return@mapNotNull null
            if (GROQ_NOT_CHAT.any { it in id.lowercase() }) return@mapNotNull null
            Caps(
                id = id,
                tools = true,
                vision = false,
                contextLength = model["context_window"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }

    private val GROQ_NOT_CHAT = listOf("whisper", "orpheus", "guard", "tts", "playai")

    private fun parseOpenRouterModels(body: JsonObject): List<Caps> =
        body["data"]?.jsonArray.orEmpty().mapNotNull { element ->
            val model = element.jsonObject
            val id = model["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val params = model["supported_parameters"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.content }
            val modalities = model["architecture"]?.jsonObject
                ?.get("input_modalities")?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.content }
            Caps(
                id = id,
                tools = "tools" in params || "tool_choice" in params,
                vision = "image" in modalities,
                contextLength = model["context_length"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
