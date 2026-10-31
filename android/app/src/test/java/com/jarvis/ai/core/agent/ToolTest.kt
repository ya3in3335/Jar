package com.jarvis.ai.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val json = Json { isLenient = true }
private fun args(text: String) = json.parseToJsonElement(text) as JsonObject

private val volumeTool = Tool(
    name = "control_volume",
    description = "يرفع أو يخفض الصوت",
    parameters = listOf(
        Tool.Param("direction", "string", "الاتجاه", enum = listOf("up", "down", "mute")),
        Tool.Param("step", "number", "عدد الدرجات", required = false),
    ),
)

class ToolSchemaTest {

    @Test
    fun `schema is a function tool with the right name`() {
        val schema = volumeTool.toJson()
        assertEquals("function", schema["type"]?.jsonPrimitive?.content)
        val fn = schema["function"]!!.jsonObject
        assertEquals("control_volume", fn["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `only required params are listed as required`() {
        val required = volumeTool.toJson()["function"]!!.jsonObject["parameters"]!!
            .jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("direction"), required)
    }

    @Test
    fun `enum values reach the schema`() {
        val direction = volumeTool.toJson()["function"]!!.jsonObject["parameters"]!!
            .jsonObject["properties"]!!.jsonObject["direction"]!!.jsonObject
        assertEquals(
            listOf("up", "down", "mute"),
            direction["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `schema forbids extra properties`() {
        val params = volumeTool.toJson()["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals(false, params["additionalProperties"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `a tool with no parameters still produces a valid object schema`() {
        val tool = Tool("pause_media", "يوقف الوسائط", emptyList())
        val params = tool.toJson()["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals("object", params["type"]?.jsonPrimitive?.content)
        assertTrue(params["required"]!!.jsonArray.isEmpty())
    }
}

class ToolValidationTest {

    @Test
    fun `valid arguments pass`() {
        assertNull(volumeTool.validate(args("""{"direction":"up","step":3}""")))
    }

    @Test
    fun `optional argument may be omitted`() {
        assertNull(volumeTool.validate(args("""{"direction":"mute"}""")))
    }

    @Test
    fun `missing required argument is rejected`() {
        val problem = volumeTool.validate(args("""{"step":2}"""))
        assertNotNull(problem)
        assertTrue(problem!!.contains("direction"))
    }

    @Test
    fun `value outside the enum is rejected`() {
        val problem = volumeTool.validate(args("""{"direction":"sideways"}"""))
        assertNotNull(problem)
        assertTrue(problem!!.contains("up"))
    }

    @Test
    fun `non-numeric value for a number is rejected`() {
        val problem = volumeTool.validate(args("""{"direction":"up","step":"كثير"}"""))
        assertNotNull(problem)
        assertTrue(problem!!.contains("step"))
    }

    @Test
    fun `unknown argument is rejected rather than ignored`() {
        // A model inventing a parameter is a bug to surface, not to swallow.
        val problem = volumeTool.validate(args("""{"direction":"up","speed":9}"""))
        assertNotNull(problem)
        assertTrue(problem!!.contains("speed"))
    }

    @Test
    fun `problem messages are in Arabic`() {
        val problem = volumeTool.validate(args("{}"))!!
        assertTrue(problem.any { it in '؀'..'ۿ' })
    }
}

class ToolRegistryTest {

    private class FakeExecutor(override val tool: Tool) : ToolExecutor {
        override suspend fun execute(args: JsonObject) = ToolResult.success("ok")
    }

    @Test
    fun `lookup is by tool name`() {
        val registry = ToolRegistry(listOf(FakeExecutor(volumeTool)))
        assertNotNull(registry.find("control_volume"))
        assertNull(registry.find("open_portal"))
    }

    @Test
    fun `unavailable tools are not offered to the model`() {
        val registry = ToolRegistry(
            listOf(
                FakeExecutor(volumeTool),
                FakeExecutor(Tool("analyze_screen", "يحلّل الشاشة", emptyList(), available = false)),
            ),
        )
        assertEquals(2, registry.names.size)
        assertEquals(1, registry.schemas().size)
    }

    @Test
    fun `sensitive tools are flagged`() {
        val tool = Tool("send_message", "يرسل رسالة", emptyList(), sensitive = true)
        assertTrue(tool.sensitive)
    }
}

class IntentArgsTest {

    @Test
    fun `routed args become json of the right types`() {
        val routed = IntentRouter.route("ارفع الصوت")!!
        val encoded = IntentRouter.argsToJson(routed.args)
        assertEquals("up", encoded["direction"]?.jsonPrimitive?.content)
        assertEquals(2, encoded["step"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `routed args satisfy the matching tool schema`() {
        // The local router and the tool schemas must agree, or a locally
        // routed command would fail validation the model never saw.
        val routed = IntentRouter.route("ارفع الصوت")!!
        assertNull(volumeTool.validate(IntentRouter.argsToJson(routed.args)))
    }

    @Test
    fun `nulls are omitted rather than sent`() {
        val encoded = IntentRouter.argsToJson(mapOf("app" to "youtube", "search" to null))
        assertTrue(encoded.containsKey("app"))
        assertTrue(!encoded.containsKey("search"))
    }

    @Test
    fun `compound command carries its search term`() {
        val routed = IntentRouter.route("افتح يوتيوب وابحث عن بايثون")!!
        val encoded = IntentRouter.argsToJson(routed.args)
        assertEquals("youtube", encoded["app"]?.jsonPrimitive?.content)
        assertTrue(encoded["search"]?.jsonPrimitive?.content!!.contains("بايثون"))
    }

    @Test
    fun `the search term reaches the tool in normalised form`() {
        // Routing folds Arabic orthography, so "قرآن" is carried as "قران".
        // Callers that compare against the user's raw spelling will miss.
        val routed = IntentRouter.route("افتح يوتيوب وابحث عن قرآن")!!
        val search = IntentRouter.argsToJson(routed.args)["search"]?.jsonPrimitive?.content
        assertEquals("قران", search)
    }
}
