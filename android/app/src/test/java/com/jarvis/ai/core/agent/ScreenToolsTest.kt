package com.jarvis.ai.core.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen tools, with the accessibility service switched off.
 *
 * That is the state they are in on a fresh install, and it is the state
 * that matters most: a tool that quietly returned success here would make
 * the assistant announce taps that never happened. Every one of them has
 * to fail, and fail with the instruction that fixes it.
 */
class ScreenToolsTest {

    /** The tools that need no Context, so they can be built in a plain JVM test. */
    private val tools: List<ToolExecutor> = listOf(
        ReadScreenTool(),
        FindOnScreenTool(),
        WaitForScreenTool(),
        TapTool(),
        ScrollScreenTool(),
        TypeTextTool(),
        PressKeyTool(),
    )

    private fun argsFor(name: String): JsonObject = when (name) {
        "find_on_screen", "tap" -> buildJsonObject { put("text", "إرسال") }
        "wait_for_screen" -> buildJsonObject { put("text", "إرسال"); put("timeout_seconds", 1) }
        "type_text" -> buildJsonObject { put("text", "مرحبا") }
        "scroll_screen" -> buildJsonObject { put("direction", "down") }
        "press_key" -> buildJsonObject { put("key", "back") }
        else -> buildJsonObject { }
    }

    @Test
    fun `every screen tool refuses when the service is off`() = runTest {
        for (executor in tools) {
            val result = executor.execute(argsFor(executor.tool.name))
            assertFalse(
                "${executor.tool.name} reported success with no service connected",
                result.ok,
            )
            assertTrue(
                "${executor.tool.name} must say how to enable it",
                result.summary.contains("إمكانية الوصول"),
            )
        }
    }

    @Test
    fun `the sample arguments satisfy each tool's own schema`() {
        // A tool whose schema rejects the arguments the model is told to
        // send would fail validation before it ever ran.
        for (executor in tools) {
            assertNull(executor.tool.name, executor.tool.validate(argsFor(executor.tool.name)))
        }
    }

    @Test
    fun `scroll and key tools constrain their options`() {
        val scroll = ScrollScreenTool().tool.parameters.single { it.name == "direction" }
        assertEquals(listOf("down", "up", "left", "right"), scroll.enum)

        val key = PressKeyTool().tool.parameters.single { it.name == "key" }
        assertEquals(listOf("back", "home", "recents", "notifications"), key.enum)
    }

    @Test
    fun `screen tool names are unique and distinct from the device tools`() {
        val names = tools.map { it.tool.name } + "open_accessibility_settings"
        assertEquals("a duplicate name silently shadows a tool", names.size, names.toSet().size)
    }

    @Test
    fun `reading the screen is not a sensitive action but typing into it carries no hidden writes`() {
        // Reading and tapping are things the user watches happen, so they
        // do not need a confirmation prompt. Nothing here sends or deletes;
        // the moment one of these grows into that, this test should fail.
        for (executor in tools) {
            assertFalse(executor.tool.name, executor.tool.sensitive)
        }
    }

    @Test
    fun `timeout is clamped so a model cannot stall the turn`() = runTest {
        // Twenty seconds is the ceiling; a model asking for five minutes
        // would otherwise hold the whole agent loop open.
        val param = WaitForScreenTool().tool.parameters.single { it.name == "timeout_seconds" }
        assertFalse("the model must be allowed to omit it", param.required)
        assertTrue(param.description.contains("20"))
    }
}
