package dev.gvart.genesara.api.internal.mcp.jackson

import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnumCaseInsensitiveToolCallbackTest {

    private val inspectSchema = """
        {
          "type": "object",
          "properties": {
            "targetType": {"type": "string", "enum": ["NODE", "AGENT", "ITEM", "BUILDING"]},
            "targetId":   {"type": "string"}
          }
        }
    """.trimIndent()

    @Test
    fun `lowercase enum value is rewritten to its canonical case`() {
        val recorder = RecordingToolCallback(inspectSchema)
        val wrapped = EnumCaseInsensitiveToolCallback(recorder)

        wrapped.call("""{"targetType": "node", "targetId": "525"}""", ToolContext(emptyMap()))

        val seen = recorder.lastInput!!
        assertTrue(seen.contains("\"targetType\":\"NODE\""), "expected NODE in delegated input, got: $seen")
        assertTrue(seen.contains("\"targetId\":\"525\""))
    }

    @Test
    fun `mixed-case enum value is rewritten to its canonical case`() {
        val recorder = RecordingToolCallback(inspectSchema)
        val wrapped = EnumCaseInsensitiveToolCallback(recorder)

        wrapped.call("""{"targetType": "Agent", "targetId": "x"}""", ToolContext(emptyMap()))

        assertTrue(recorder.lastInput!!.contains("\"targetType\":\"AGENT\""))
    }

    @Test
    fun `exact-case enum value passes through without rewriting`() {
        val recorder = RecordingToolCallback(inspectSchema)
        val wrapped = EnumCaseInsensitiveToolCallback(recorder)

        wrapped.call("""{"targetType":"NODE","targetId":"525"}""", ToolContext(emptyMap()))

        assertEquals("""{"targetType":"NODE","targetId":"525"}""", recorder.lastInput)
    }

    @Test
    fun `unknown enum value passes through untouched and lets the delegate raise`() {
        val recorder = RecordingToolCallback(inspectSchema)
        val wrapped = EnumCaseInsensitiveToolCallback(recorder)

        wrapped.call("""{"targetType":"CASTLE","targetId":"x"}""", ToolContext(emptyMap()))

        assertTrue(recorder.lastInput!!.contains("\"targetType\":\"CASTLE\""))
    }

    @Test
    fun `tool with no enum fields skips rewrite entirely`() {
        val plainSchema = """{"type":"object","properties":{"nodeId":{"type":"integer"}}}"""
        val recorder = RecordingToolCallback(plainSchema)
        val wrapped = EnumCaseInsensitiveToolCallback(recorder)
        val input = """{"nodeId":525}"""

        wrapped.call(input, ToolContext(emptyMap()))

        assertEquals(input, recorder.lastInput)
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
