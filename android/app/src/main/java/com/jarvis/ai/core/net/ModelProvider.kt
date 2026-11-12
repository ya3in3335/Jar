package com.jarvis.ai.core.net

/**
 * Where model requests go.
 *
 * Both providers speak the OpenAI chat-completions dialect, so one client
 * serves them; what differs is the base URL, the key and which models are
 * allowed. OpenRouter is restricted to its free models on purpose: a paid
 * model id is refused before any request is made, so this app cannot spend
 * credits there.
 */
enum class ModelProvider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val keyPrefix: String,
    val defaultModel: String,
    /** Shown before the live list has been loaded on this device. */
    val suggestedModels: List<String>,
) {
    GROQ(
        id = "groq",
        label = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        keyPrefix = "gsk_",
        defaultModel = "llama-3.3-70b-versatile",
        suggestedModels = listOf(
            "llama-3.3-70b-versatile",
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "llama-3.1-8b-instant",
        ),
    ),
    OPENROUTER(
        id = "openrouter",
        label = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        keyPrefix = "sk-or-",
        // OpenRouter's own router over whatever is free today; individual
        // free models come and go with little notice.
        defaultModel = "openrouter/free",
        suggestedModels = listOf("openrouter/free"),
    ),
    ;

    /** Whether this provider may be asked for [model] at all. */
    fun allows(model: String): Boolean = this != OPENROUTER || isFreeOpenRouterModel(model)

    companion object {
        fun fromId(id: String?): ModelProvider = entries.firstOrNull { it.id == id } ?: GROQ

        /** Free on OpenRouter means the `:free` variant or the free router. */
        fun isFreeOpenRouterModel(model: String): Boolean =
            model == "openrouter/free" || model.endsWith(":free")
    }
}
