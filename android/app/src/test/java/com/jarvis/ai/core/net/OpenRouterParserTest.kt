package com.jarvis.ai.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

class OpenRouterRequestTest {

    @Test
    fun `request carries model messages and token cap`() {
        val body = OpenRouterParser.buildRequest(
            model = "x/y",
            messages = listOf(ChatMessage.System("نظام"), ChatMessage.User("افتح يوتيوب")),
            tools = emptyList(),
            maxTokens = 512,
            stream = false,
        )
        assertEquals("x/y", body["model"]?.jsonPrimitive?.content)
        assertEquals(512, body["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals(2, body["messages"]?.jsonArray?.size)
        assertEquals("نظام", body["messages"]!!.jsonArray[0].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tools are omitted entirely when there are none`() {
        val body = OpenRouterParser.buildRequest("m", listOf(ChatMessage.User("x")), emptyList(), 100, false)
        assertNull(body["tools"])
        assertNull(body["tool_choice"])
    }

    @Test
    fun `tool choice is auto when tools are offered`() {
        val tool = obj("""{"type":"function","function":{"name":"open_url"}}""")
        val body = OpenRouterParser.buildRequest("m", listOf(ChatMessage.User("x")), listOf(tool), 100, false)
        assertEquals(1, body["tools"]?.jsonArray?.size)
        assertEquals("auto", body["tool_choice"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assistant turn with tool calls round-trips into the request`() {
        val assistant = ChatMessage.Assistant(
            content = null,
            toolCalls = listOf(ToolCall("call_1", "open_url", """{"url":"https://x.dz"}""")),
        )
        val encoded = assistant.toJson()
        val calls = encoded["tool_calls"]!!.jsonArray
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("open_url", calls[0].jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `tool reply is bound to its call id`() {
        val reply = ChatMessage.ToolReply("call_1", "open_url", "نجح").toJson()
        assertEquals("tool", reply["role"]?.jsonPrimitive?.content)
        assertEquals("call_1", reply["tool_call_id"]?.jsonPrimitive?.content)
    }
}

class OpenRouterTurnTest {

    @Test
    fun `plain text reply`() {
        val turn = OpenRouterParser.parseTurn(
            obj(
                """{"choices":[{"message":{"role":"assistant","content":"السماء زرقاء"},
                   "finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""",
            ),
        )
        assertEquals("السماء زرقاء", turn.text)
        assertFalse(turn.wantsTools)
        assertEquals(10, turn.promptTokens)
        assertEquals(5, turn.completionTokens)
    }

    @Test
    fun `tool call reply`() {
        val turn = OpenRouterParser.parseTurn(
            obj(
                """{"choices":[{"message":{"role":"assistant","content":null,
                   "tool_calls":[{"id":"c1","type":"function",
                   "function":{"name":"open_application","arguments":"{\"app\":\"youtube\"}"}}]},
                   "finish_reason":"tool_calls"}]}""",
            ),
        )
        assertTrue(turn.wantsTools)
        assertNull(turn.text)
        assertEquals("open_application", turn.toolCalls[0].name)
        assertEquals("""{"app":"youtube"}""", turn.toolCalls[0].rawArguments)
    }

    @Test
    fun `null content does not become the string null`() {
        val turn = OpenRouterParser.parseTurn(
            obj("""{"choices":[{"message":{"role":"assistant","content":null}}]}"""),
        )
        assertNull(turn.text)
    }

    @Test
    fun `missing arguments default to an empty object`() {
        val turn = OpenRouterParser.parseTurn(
            obj(
                """{"choices":[{"message":{"tool_calls":[{"id":"c1",
                   "function":{"name":"launch_camera"}}]}}]}""",
            ),
        )
        assertEquals("{}", turn.toolCalls[0].rawArguments)
    }

    @Test
    fun `call id falls back to the tool name`() {
        val turn = OpenRouterParser.parseTurn(
            obj("""{"choices":[{"message":{"tool_calls":[{"function":{"name":"pause_media"}}]}}]}"""),
        )
        assertEquals("pause_media", turn.toolCalls[0].id)
    }

    @Test(expected = OpenRouterException::class)
    fun `error body throws`() {
        OpenRouterParser.parseTurn(obj("""{"error":{"message":"no credit","code":402}}"""))
    }

    @Test(expected = OpenRouterException::class)
    fun `body with no choices throws`() {
        OpenRouterParser.parseTurn(obj("""{"id":"x"}"""))
    }
}

class OpenRouterStreamTest {

    @Test
    fun `text delta is extracted`() {
        val delta = OpenRouterParser.parseStreamDelta(
            obj("""{"choices":[{"delta":{"content":"مرحبا"}}]}"""),
        )
        assertEquals("مرحبا", delta)
    }

    @Test
    fun `role-only opener yields nothing`() {
        assertNull(OpenRouterParser.parseStreamDelta(obj("""{"choices":[{"delta":{"role":"assistant"}}]}""")))
    }

    @Test
    fun `keep-alive yields nothing`() {
        assertNull(OpenRouterParser.parseStreamDelta(obj("""{"choices":[]}""")))
    }

    @Test
    fun `tool call is reassembled from fragments`() {
        val pending = mutableMapOf<Int, MutableToolCall>()
        OpenRouterParser.mergeToolCallDeltas(
            obj(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1",
                   "function":{"name":"open_application","arguments":"{\"app\":"}}]}}]}""",
            ),
            pending,
        )
        OpenRouterParser.mergeToolCallDeltas(
            obj("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"youtube\"}"}}]}}]}"""),
            pending,
        )

        val call = pending[0]?.toToolCall()
        assertEquals("c1", call?.id)
        assertEquals("open_application", call?.name)
        assertEquals("""{"app":"youtube"}""", call?.rawArguments)
    }

    @Test
    fun `two parallel tool calls stay separate`() {
        val pending = mutableMapOf<Int, MutableToolCall>()
        OpenRouterParser.mergeToolCallDeltas(
            obj(
                """{"choices":[{"delta":{"tool_calls":[
                   {"index":0,"id":"a","function":{"name":"pause_media","arguments":"{}"}},
                   {"index":1,"id":"b","function":{"name":"launch_camera","arguments":"{}"}}]}}]}""",
            ),
            pending,
        )
        assertEquals(2, pending.size)
        assertEquals("pause_media", pending[0]?.toToolCall()?.name)
        assertEquals("launch_camera", pending[1]?.toToolCall()?.name)
    }

    @Test
    fun `a fragment with no name is not a usable call`() {
        val pending = mutableMapOf<Int, MutableToolCall>()
        OpenRouterParser.mergeToolCallDeltas(
            obj("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}}]}"""),
            pending,
        )
        assertNull(pending[0]?.toToolCall())
    }
}

class ModelCapabilitiesTest {

    @Test
    fun `tool support is read from supported_parameters`() {
        val caps = ModelCapabilities.parseModels(
            obj(
                """{"data":[
                   {"id":"a/withtools","supported_parameters":["tools","temperature"],"context_length":128000},
                   {"id":"b/notools","supported_parameters":["temperature"],"context_length":8192}]}""",
            ),
        )
        assertEquals(2, caps.size)
        assertTrue(caps.first { it.id == "a/withtools" }.tools)
        assertFalse(caps.first { it.id == "b/notools" }.tools)
        assertEquals(128000, caps.first { it.id == "a/withtools" }.contextLength)
    }

    @Test
    fun `tool_choice alone also counts as support`() {
        val caps = ModelCapabilities.parseModels(
            obj("""{"data":[{"id":"m","supported_parameters":["tool_choice"]}]}"""),
        )
        assertTrue(caps[0].tools)
    }

    @Test
    fun `vision is read from input modalities`() {
        val caps = ModelCapabilities.parseModels(
            obj("""{"data":[{"id":"m","architecture":{"input_modalities":["text","image"]}}]}"""),
        )
        assertTrue(caps[0].vision)
    }

    @Test
    fun `an entry with no id is skipped rather than crashing`() {
        val caps = ModelCapabilities.parseModels(obj("""{"data":[{"name":"anonymous"},{"id":"ok"}]}"""))
        assertEquals(listOf("ok"), caps.map { it.id })
    }

    @Test
    fun `empty payload yields no models`() {
        assertTrue(ModelCapabilities.parseModels(obj("{}")).isEmpty())
    }
}
