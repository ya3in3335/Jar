package com.jarvis.ai.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A tool the model may call.
 *
 * Every tool declares a strict JSON schema, so a malformed call is rejected
 * before anything runs rather than being coerced into a guess.
 */
data class Tool(
    val name: String,
    /** Arabic, because the model is prompted and answers in Arabic. */
    val description: String,
    val parameters: List<Param>,
    /**
     * True when running this changes something the user would not want
     * undone silently (spec section 11): sending, posting, buying,
     * deleting, or changing a security setting. These require explicit
     * confirmation before execution, never after.
     */
    val sensitive: Boolean = false,
    /** False when the device cannot currently run it (permission, missing app). */
    val available: Boolean = true,
) {
    data class Param(
        val name: String,
        val type: String, // string | number | boolean
        val description: String,
        val required: Boolean = true,
        val enum: List<String>? = null,
    )

    /** Render as an OpenAI/OpenRouter-style function tool definition. */
    fun toJson(): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    for (p in parameters) {
                        putJsonObject(p.name) {
                            put("type", p.type)
                            put("description", p.description)
                            p.enum?.let { values ->
                                putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
                            }
                        }
                    }
                }
                putJsonArray("required") {
                    parameters.filter { it.required }.forEach { add(JsonPrimitive(it.name)) }
                }
                // Reject anything the schema does not describe.
                put("additionalProperties", false)
            }
        }
    }

    /**
     * Check a model-produced argument object against this tool's schema.
     * Returns null when valid, or an Arabic message naming the problem.
     */
    fun validate(args: JsonObject): String? {
        for (p in parameters) {
            val value = args[p.name]
            if (value == null) {
                if (p.required) return "الوسيط '${p.name}' مطلوب للأداة '$name'"
                continue
            }
            val primitive = value.jsonPrimitive
            val typeOk = when (p.type) {
                "string" -> primitive.isString || primitive.content.isNotEmpty()
                "number" -> primitive.content.toDoubleOrNull() != null
                "boolean" -> primitive.content.toBooleanStrictOrNull() != null
                else -> true
            }
            if (!typeOk) return "الوسيط '${p.name}' يجب أن يكون من النوع ${p.type}"
            if (p.enum != null && primitive.content !in p.enum) {
                return "الوسيط '${p.name}' يجب أن يكون أحد: ${p.enum.joinToString(", ")}"
            }
        }
        val unknown = args.keys - parameters.map { it.name }.toSet()
        if (unknown.isNotEmpty()) return "وسائط غير معروفة: ${unknown.joinToString(", ")}"
        return null
    }
}

/** What a tool returns to the agent loop, and through it to the model. */
data class ToolResult(
    val ok: Boolean,
    /** Short Arabic summary shown to the user. */
    val summary: String,
    /** Optional detail fed back to the model as the tool message. */
    val detail: String? = null,
) {
    companion object {
        fun success(summary: String, detail: String? = null) = ToolResult(true, summary, detail)
        fun failure(summary: String, detail: String? = null) = ToolResult(false, summary, detail)
    }
}

/** Anything that can run a tool. */
interface ToolExecutor {
    val tool: Tool
    suspend fun execute(args: JsonObject): ToolResult
}
