package com.jarvis.ai.core.agent

import com.jarvis.ai.core.net.ChatMessage
import com.jarvis.ai.core.net.ModelTurn
import com.jarvis.ai.core.net.OpenRouterClient
import com.jarvis.ai.core.net.OpenRouterException
import com.jarvis.ai.core.net.ToolCall
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent loop: interpret, plan, pick a tool, validate, run, observe,
 * continue, report.
 *
 * Every bound here exists because an unbounded agent loop is the default
 * failure mode: a model that keeps calling the same tool will otherwise
 * spend the user's credit until the key is empty.
 */
@Singleton
class AgentRuntime @Inject constructor(private val client: OpenRouterClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Limits(
        val maxToolCalls: Int = 8,
        val maxDurationMs: Long = 90_000,
        val maxRetriesPerTool: Int = 1,
        val maxOutputTokens: Int = 1024,
        /** Stop once the conversation has cost this many tokens. 0 disables. */
        val tokenBudget: Int = 0,
    )

    /** Progress the UI can show while the loop runs. */
    sealed interface Event {
        data class Thinking(val detail: String? = null) : Event
        data class ToolStarted(val tool: String, val summary: String) : Event
        data class ToolFinished(val tool: String, val result: ToolResult) : Event
        /** The loop is paused: a sensitive tool needs explicit approval. */
        data class NeedsConfirmation(val call: ToolCall, val tool: Tool) : Event
        data class Spoke(val text: String) : Event
        data class Failed(val message: String) : Event
        data class Finished(val text: String?, val toolsRun: Int, val tokensUsed: Int) : Event
    }

    /** Outcome of a run, so the caller knows whether to speak or stop. */
    data class Outcome(
        val text: String?,
        val toolsRun: Int,
        val tokensUsed: Int,
        val stoppedBecause: Stop,
    )

    enum class Stop { COMPLETED, TOOL_LIMIT, TIME_LIMIT, TOKEN_BUDGET, NEEDS_CONFIRMATION, ERROR, CANCELLED }

    /**
     * Run one user instruction to completion.
     *
     * [confirm] is consulted before any sensitive tool runs; returning false
     * aborts that call rather than running it anyway.
     */
    suspend fun run(
        apiKey: String,
        model: String,
        instruction: String,
        registry: ToolRegistry,
        history: List<ChatMessage> = emptyList(),
        limits: Limits = Limits(),
        confirm: suspend (Tool, JsonObject) -> Boolean = { _, _ -> true },
        onEvent: (Event) -> Unit = {},
    ): Outcome {
        val messages = buildList {
            add(ChatMessage.System(systemPrompt(registry)))
            addAll(history)
            add(ChatMessage.User(instruction))
        }.toMutableList()

        val toolSchemas = registry.schemas()
        var toolsRun = 0
        var tokensUsed = 0
        val retries = mutableMapOf<String, Int>()

        return try {
            withTimeout(limits.maxDurationMs) {
                while (true) {
                    onEvent(Event.Thinking())

                    val turn: ModelTurn = client.complete(
                        apiKey = apiKey,
                        model = model,
                        messages = messages,
                        tools = toolSchemas,
                        maxTokens = limits.maxOutputTokens,
                    )
                    tokensUsed += turn.promptTokens + turn.completionTokens

                    if (limits.tokenBudget > 0 && tokensUsed >= limits.tokenBudget) {
                        onEvent(Event.Failed("توقّفت: بلغت حدّ الرموز المسموح"))
                        return@withTimeout Outcome(turn.text, toolsRun, tokensUsed, Stop.TOKEN_BUDGET)
                    }

                    if (!turn.wantsTools) {
                        turn.text?.let { onEvent(Event.Spoke(it)) }
                        onEvent(Event.Finished(turn.text, toolsRun, tokensUsed))
                        return@withTimeout Outcome(turn.text, toolsRun, tokensUsed, Stop.COMPLETED)
                    }

                    // The assistant turn must be recorded before its tool
                    // replies, or the provider rejects the next request.
                    messages.add(ChatMessage.Assistant(turn.text, turn.toolCalls))

                    for (call in turn.toolCalls) {
                        if (toolsRun >= limits.maxToolCalls) {
                            onEvent(Event.Failed("توقّفت: بلغت حدّ ${limits.maxToolCalls} استدعاءات للأدوات"))
                            return@withTimeout Outcome(turn.text, toolsRun, tokensUsed, Stop.TOOL_LIMIT)
                        }

                        val result = runTool(call, registry, retries, limits, confirm, onEvent)
                            ?: return@withTimeout Outcome(null, toolsRun, tokensUsed, Stop.NEEDS_CONFIRMATION)

                        toolsRun++
                        messages.add(
                            ChatMessage.ToolReply(
                                callId = call.id,
                                name = call.name,
                                content = buildString {
                                    append(if (result.ok) "نجح: " else "فشل: ")
                                    append(result.summary)
                                    result.detail?.let { append(" | ").append(it) }
                                },
                            ),
                        )
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                Outcome(null, toolsRun, tokensUsed, Stop.COMPLETED)
            }
        } catch (e: TimeoutCancellationException) {
            onEvent(Event.Failed("توقّفت: انتهت المدّة المحدّدة"))
            Outcome(null, toolsRun, tokensUsed, Stop.TIME_LIMIT)
        } catch (e: OpenRouterException) {
            onEvent(Event.Failed(e.message ?: "فشل الاتصال بالنموذج"))
            Outcome(null, toolsRun, tokensUsed, Stop.ERROR)
        }
    }

    /**
     * Validate and run a single call.
     * Returns null when a sensitive call is still awaiting confirmation.
     */
    private suspend fun runTool(
        call: ToolCall,
        registry: ToolRegistry,
        retries: MutableMap<String, Int>,
        limits: Limits,
        confirm: suspend (Tool, JsonObject) -> Boolean,
        onEvent: (Event) -> Unit,
    ): ToolResult? {
        val executor = registry.find(call.name)
            ?: return ToolResult.failure("لا توجد أداة بالاسم '${call.name}'")

        val args = runCatching { json.parseToJsonElement(call.rawArguments) as JsonObject }.getOrNull()
            ?: return ToolResult.failure("وسائط الأداة '${call.name}' ليست JSON صحيحًا")

        executor.tool.validate(args)?.let { problem ->
            val used = retries.getOrDefault(call.name, 0)
            if (used >= limits.maxRetriesPerTool) {
                return ToolResult.failure("وسائط غير صحيحة بعد إعادة المحاولة: $problem")
            }
            retries[call.name] = used + 1
            // Hand the problem back so the model can correct itself once.
            return ToolResult.failure(problem)
        }

        if (executor.tool.sensitive && !confirm(executor.tool, args)) {
            return ToolResult.failure("أُلغي الإجراء بناءً على اختيارك")
        }

        onEvent(Event.ToolStarted(call.name, executor.tool.description))
        val result = runCatching { executor.execute(args) }.getOrElse { e ->
            ToolResult.failure("تعذّر تنفيذ '${call.name}': ${e.javaClass.simpleName}")
        }
        onEvent(Event.ToolFinished(call.name, result))
        return result
    }

    /**
     * The system prompt.
     *
     * States the three things the model gets wrong by default: inventing
     * tools, claiming an action succeeded without running it, and answering
     * in a language other than the user's.
     */
    private fun systemPrompt(registry: ToolRegistry): String = buildString {
        appendLine("أنت جارفيس، مساعد شخصي يعمل على هاتف أندرويد يملكه المستخدم.")
        appendLine()
        appendLine("القواعد:")
        appendLine("- تحدّث بالعربية دائمًا، بإيجاز وبلا حشو.")
        appendLine("- لتنفيذ أي إجراء على الهاتف استخدم الأدوات المتاحة فقط. لا تخترع أداة.")
        appendLine("- لا تقل إنك نفّذت شيئًا قبل أن تُرجع الأداة نجاحًا فعليًا.")
        appendLine("- إن فشلت أداة، اذكر السبب كما ورد ولا تتظاهر بالنجاح.")
        appendLine("- إن كان السؤال معرفيًا ولا يحتاج إجراءً، أجب مباشرة بلا أدوات.")
        appendLine("- لا تستدعِ أكثر من أداة واحدة إن كفت واحدة.")
        appendLine()
        appendLine("الأدوات المتاحة على هذا الجهاز:")
        for (executor in registry.executors) {
            append("- ").append(executor.tool.name).append(": ").append(executor.tool.description)
            if (executor.tool.sensitive) append(" (يتطلّب تأكيد المستخدم)")
            appendLine()
        }
    }
}

/** The set of tools available on this device. */
class ToolRegistry(val executors: List<ToolExecutor>) {

    private val byName = executors.associateBy { it.tool.name }

    fun find(name: String): ToolExecutor? = byName[name]

    /** Schemas for the tools currently usable, in OpenRouter's format. */
    fun schemas(): List<JsonObject> =
        executors.filter { it.tool.available }.map { it.tool.toJson() }

    val names: List<String> get() = executors.map { it.tool.name }
}
