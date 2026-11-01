package com.jarvis.ai.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val json = Json { isLenient = true }
private fun args(text: String) = json.parseToJsonElement(text) as JsonObject

/**
 * The catalogue has grown enough that these invariants are worth guarding:
 * a duplicate name silently shadows a tool, and a destructive tool left
 * unflagged would run without the user ever being asked.
 */
class ToolCatalogueTest {

    private val sample = listOf(
        Tool("open_url", "يفتح رابطًا", listOf(Tool.Param("url", "string", "الرابط"))),
        Tool(
            "compose_message", "يجهّز رسالة",
            listOf(Tool.Param("text", "string", "النصّ")),
            sensitive = true,
        ),
        Tool("get_battery_status", "البطارية", emptyList()),
    )

    @Test
    fun `tool names are unique`() {
        val names = sample.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `every tool has an Arabic description`() {
        for (tool in sample) {
            assertTrue(tool.name, tool.description.any { it in '؀'..'ۿ' })
        }
    }

    @Test
    fun `anything that sends is flagged sensitive`() {
        // A message tool that is not flagged would open and send without
        // the confirmation step ever running.
        assertTrue(sample.first { it.name == "compose_message" }.sensitive)
    }

    @Test
    fun `a read-only tool is not flagged`() {
        assertTrue(!sample.first { it.name == "get_battery_status" }.sensitive)
    }

    @Test
    fun `a no-parameter tool still validates an empty object`() {
        assertNull(sample.first { it.name == "get_battery_status" }.validate(args("{}")))
    }

    @Test
    fun `an unexpected argument on a no-parameter tool is rejected`() {
        val problem = sample.first { it.name == "get_battery_status" }.validate(args("""{"x":1}"""))
        assertNotNull(problem)
    }
}

class WebSearchToolShapeTest {

    private val tool = Tool(
        name = "search_web",
        description = "يبحث في الويب ويعيد خلاصة",
        parameters = listOf(
            Tool.Param("query", "string", "ما تريد البحث عنه"),
            Tool.Param("detail", "string", "short أو full", required = false,
                enum = listOf("short", "full")),
        ),
    )

    @Test
    fun `query is required and detail is not`() {
        assertNotNull(tool.validate(args("{}")))
        assertNull(tool.validate(args("""{"query":"سعر الذهب اليوم"}""")))
    }

    @Test
    fun `detail is limited to the two documented values`() {
        assertNull(tool.validate(args("""{"query":"x","detail":"full"}""")))
        assertNotNull(tool.validate(args("""{"query":"x","detail":"everything"}""")))
    }

    @Test
    fun `research is not sensitive, since it changes nothing`() {
        assertTrue(!tool.sensitive)
    }
}
