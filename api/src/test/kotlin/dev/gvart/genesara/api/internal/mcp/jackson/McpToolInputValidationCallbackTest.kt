package dev.gvart.genesara.api.internal.mcp.jackson

import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata
import org.springframework.ai.util.json.JsonParser
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpToolInputValidationCallbackTest {

    private val harvestSchema = """
        {
          "type": "object",
          "properties": {
            "itemId": {"type": "string"}
          },
          "required": ["itemId"]
        }
    """.trimIndent()

    private val buildSchema = """
        {
          "type": "object",
          "properties": {
            "type": {"type": "string", "enum": ["CAMPFIRE", "WORKBENCH", "STORAGE_CHEST"]}
          },
          "required": ["type"]
        }
    """.trimIndent()

    @Test
    fun `missing required param returns structured error and does not call delegate`() {
        val recorder = RecordingToolCallback(harvestSchema)
        val wrapped = McpToolInputValidationCallback(recorder)

        val result = wrapped.call("""{}""", ToolContext(emptyMap()))
        val tree = JsonParser.getJsonMapper().readTree(result)

        assertNull(recorder.lastInput, "delegate must not be invoked for invalid input")
        assertEquals("error", tree.get("kind").asString())
        assertEquals("MISSING_REQUIRED_PARAMETER", tree.path("error").path("code").asString())
        assertEquals("itemId", tree.path("error").path("parameter").asString())
    }

    @Test
    fun `explicit null for required param returns structured error`() {
        val recorder = RecordingToolCallback(harvestSchema)
        val wrapped = McpToolInputValidationCallback(recorder)

        val result = wrapped.call("""{"itemId": null}""", ToolContext(emptyMap()))
        val tree = JsonParser.getJsonMapper().readTree(result)

        assertNull(recorder.lastInput)
        assertEquals("MISSING_REQUIRED_PARAMETER", tree.path("error").path("code").asString())
    }

    @Test
    fun `blank required string returns structured error`() {
        val recorder = RecordingToolCallback(harvestSchema)
        val wrapped = McpToolInputValidationCallback(recorder)

        val result = wrapped.call("""{"itemId":"   "}""", ToolContext(emptyMap()))
        val tree = JsonParser.getJsonMapper().readTree(result)

        assertNull(recorder.lastInput)
        assertEquals("MISSING_REQUIRED_PARAMETER", tree.path("error").path("code").asString())
        assertTrue(tree.path("error").path("message").asString().contains("must not be blank"))
    }

    @Test
    fun `unknown enum value returns structured error with validValues list`() {
        val recorder = RecordingToolCallback(buildSchema)
        val wrapped = McpToolInputValidationCallback(recorder)

        val result = wrapped.call("""{"type":"CASTLE"}""", ToolContext(emptyMap()))
        val tree = JsonParser.getJsonMapper().readTree(result)

        assertNull(recorder.lastInput)
        assertEquals("INVALID_ENUM_VALUE", tree.path("error").path("code").asString())
        assertEquals("type", tree.path("error").path("parameter").asString())
        assertEquals("CASTLE", tree.path("error").path("received").asString())
        val valid = mutableListOf<String>()
        tree.path("error").path("validValues").forEach { valid.add(it.asString()) }
        assertEquals(listOf("CAMPFIRE", "WORKBENCH", "STORAGE_CHEST"), valid)
    }

    @Test
    fun `valid enum value passes through and delegate is invoked`() {
        val recorder = RecordingToolCallback(buildSchema)
        val wrapped = McpToolInputValidationCallback(recorder)

        wrapped.call("""{"type":"CAMPFIRE"}""", ToolContext(emptyMap()))

        assertTrue(recorder.lastInput!!.contains("\"type\":\"CAMPFIRE\""))
    }

    @Test
    fun `present required param passes through and delegate is invoked`() {
        val recorder = RecordingToolCallback(harvestSchema)
        val wrapped = McpToolInputValidationCallback(recorder)

        wrapped.call("""{"itemId":"BERRY"}""", ToolContext(emptyMap()))

        assertEquals("""{"itemId":"BERRY"}""", recorder.lastInput)
    }

    @Test
    fun `schema with no required and no enum fields skips validation entirely`() {
        val plainSchema = """{"type":"object","properties":{"nodeId":{"type":"integer"}}}"""
        val recorder = RecordingToolCallback(plainSchema)
        val wrapped = McpToolInputValidationCallback(recorder)
        val input = """{}"""

        wrapped.call(input, ToolContext(emptyMap()))

        assertEquals(input, recorder.lastInput)
    }

    @Test
    fun `composition with EnumCaseInsensitiveToolCallback canonicalises case before validation`() {
        val recorder = RecordingToolCallback(buildSchema)
        val wrapped = EnumCaseInsensitiveToolCallback(McpToolInputValidationCallback(recorder))

        wrapped.call("""{"type":"campfire"}""", ToolContext(emptyMap()))

        assertTrue(recorder.lastInput!!.contains("\"type\":\"CAMPFIRE\""))
    }

    @Test
    fun `composition rejects unknown enum even with mixed casing`() {
        val recorder = RecordingToolCallback(buildSchema)
        val wrapped = EnumCaseInsensitiveToolCallback(McpToolInputValidationCallback(recorder))

        val result = wrapped.call("""{"type":"CasTLE"}""", ToolContext(emptyMap()))
        val tree = JsonParser.getJsonMapper().readTree(result)

        assertNull(recorder.lastInput)
        assertEquals("INVALID_ENUM_VALUE", tree.path("error").path("code").asString())
    }

    private class RecordingToolCallback(private val schema: String) : ToolCallback {
        var lastInput: String? = null
        override fun getToolDefinition(): ToolDefinition =
            ToolDefinition.builder().name("test").description("test").inputSchema(schema).build()
        override fun getToolMetadata(): ToolMetadata = ToolMetadata.builder().build()
        override fun call(toolInput: String): String = call(toolInput, null)
        override fun call(toolInput: String, toolContext: ToolContext?): String {
            lastInput = toolInput
            return "{}"
        }
    }
}
