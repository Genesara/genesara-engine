package dev.gvart.genesara.api.internal.mcp.jackson

import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.execution.ToolExecutionException
import org.springframework.ai.tool.metadata.ToolMetadata
import org.springframework.ai.util.json.JsonParser
import tools.jackson.databind.exc.InvalidFormatException
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

    private val allocateSchema = """
        {
          "type": "object",
          "properties": {
            "deltas": {"type": "object"}
          },
          "required": ["deltas"]
        }
    """.trimIndent()

    @Test
    fun `missing required param returns structured rejection and does not call delegate`() {
        val recorder = RecordingToolCallback(harvestSchema)
        val wrapped = McpToolInputValidationCallback(recorder)

        val result = wrapped.call("""{}""", ToolContext(emptyMap()))
        val tree = JsonParser.getJsonMapper().readTree(result)

        assertNull(recorder.lastInput, "delegate must not be invoked for invalid input")
        assertEquals("rejected", tree.get("kind").asString())
        assertEquals("missing_required", tree.get("reason").asString())
        assertEquals("itemId", tree.get("parameter").asString())
        assertTrue(tree.get("detail").asString().contains("'itemId'"))
    }

    @Test
    fun `explicit null for required param returns structured rejection`() {
        val recorder = RecordingToolCallback(harvestSchema)
        val wrapped = McpToolInputValidationCallback(recorder)

        val result = wrapped.call("""{"itemId": null}""", ToolContext(emptyMap()))
        val tree = JsonParser.getJsonMapper().readTree(result)

        assertNull(recorder.lastInput)
        assertEquals("missing_required", tree.get("reason").asString())
    }

    @Test
    fun `blank required string returns structured rejection`() {
        val recorder = RecordingToolCallback(harvestSchema)
        val wrapped = McpToolInputValidationCallback(recorder)

        val result = wrapped.call("""{"itemId":"   "}""", ToolContext(emptyMap()))
        val tree = JsonParser.getJsonMapper().readTree(result)

        assertNull(recorder.lastInput)
        assertEquals("missing_required", tree.get("reason").asString())
        assertTrue(tree.get("detail").asString().contains("must not be blank"))
    }

    @Test
    fun `unknown enum value at top level returns structured rejection with validValues`() {
        val recorder = RecordingToolCallback(buildSchema)
        val wrapped = McpToolInputValidationCallback(recorder)

        val result = wrapped.call("""{"type":"CASTLE"}""", ToolContext(emptyMap()))
        val tree = JsonParser.getJsonMapper().readTree(result)

        assertNull(recorder.lastInput)
        assertEquals("rejected", tree.get("kind").asString())
        assertEquals("unknown_enum_value", tree.get("reason").asString())
        assertEquals("type", tree.get("parameter").asString())
        assertEquals("CASTLE", tree.get("received").asString())
        val valid = mutableListOf<String>()
        tree.get("validValues").forEach { valid.add(it.asString()) }
        assertEquals(listOf("CAMPFIRE", "WORKBENCH", "STORAGE_CHEST"), valid)
        assertTrue(tree.get("detail").asString().contains("'CASTLE' is not one of"))
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
    fun `schema with no required and no enum fields skips pre-validation entirely`() {
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
        assertEquals("unknown_enum_value", tree.get("reason").asString())
    }

    @Test
    fun `kotlin non-null IllegalArgumentException from binder translates to missing_required`() {
        val def = ToolDefinition.builder().name("test").description("t").inputSchema(harvestSchema).build()
        val cause = IllegalArgumentException("Parameter specified as non-null is null: method foo.Bar.invoke, parameter itemId")
        val throwing = ThrowingToolCallback(def, ToolExecutionException(def, cause))
        val wrapped = McpToolInputValidationCallback(throwing)

        val result = wrapped.call("""{"itemId":"WOOD"}""", ToolContext(emptyMap()))
        val tree = JsonParser.getJsonMapper().readTree(result)

        assertEquals("rejected", tree.get("kind").asString())
        assertEquals("missing_required", tree.get("reason").asString())
        assertEquals("itemId", tree.get("parameter").asString())
        assertTrue(tree.get("detail").asString().contains("'itemId'"))
    }

    @Test
    fun `kotlin non-null NullPointerException from binder translates to missing_required`() {
        val def = ToolDefinition.builder().name("test").description("t").inputSchema(harvestSchema).build()
        val cause = NullPointerException("Parameter specified as non-null is null: method foo.Bar.invoke, parameter itemId")
        val throwing = ThrowingToolCallback(def, ToolExecutionException(def, cause))
        val wrapped = McpToolInputValidationCallback(throwing)

        val result = wrapped.call("""{"itemId":"WOOD"}""", ToolContext(emptyMap()))
        val tree = JsonParser.getJsonMapper().readTree(result)

        assertEquals("missing_required", tree.get("reason").asString())
        assertEquals("itemId", tree.get("parameter").asString())
    }

    @Test
    fun `IllegalArgumentException with No enum constant translates to unknown_enum_value`() {
        val def = ToolDefinition.builder().name("test").description("t").inputSchema(allocateSchema).build()
        val cause = IllegalArgumentException("No enum constant ${ProbeEnum::class.java.name}.FOOBAR")
        val throwing = ThrowingToolCallback(def, ToolExecutionException(def, cause))
        val wrapped = McpToolInputValidationCallback(throwing)

        val result = wrapped.call("""{"deltas":{}}""", ToolContext(emptyMap()))
        val tree = JsonParser.getJsonMapper().readTree(result)

        assertEquals("rejected", tree.get("kind").asString())
        assertEquals("unknown_enum_value", tree.get("reason").asString())
        assertEquals("ProbeEnum", tree.get("parameter").asString())
        assertEquals("FOOBAR", tree.get("received").asString())
        val valid = mutableListOf<String>()
        tree.get("validValues").forEach { valid.add(it.asString()) }
        assertEquals(listOf("ALPHA", "BETA"), valid)
        assertTrue(!tree.get("detail").asString().contains("dev.gvart"))
    }

    @Test
    fun `Jackson Map key InvalidFormatException for enum translates to unknown_enum_value with simple name`() {
        val def = ToolDefinition.builder().name("test").description("t").inputSchema(allocateSchema).build()
        val cause = InvalidFormatException.from(
            null,
            "Cannot deserialize Map key of type `${ProbeEnum::class.java.name}` from String \"FOOBAR\"",
            "FOOBAR",
            ProbeEnum::class.java,
        )
        val throwing = ThrowingToolCallback(def, ToolExecutionException(def, cause))
        val wrapped = McpToolInputValidationCallback(throwing)

        val result = wrapped.call("""{"deltas":{"FOOBAR":1}}""", ToolContext(emptyMap()))
        val tree = JsonParser.getJsonMapper().readTree(result)

        assertEquals("rejected", tree.get("kind").asString())
        assertEquals("unknown_enum_value", tree.get("reason").asString())
        assertEquals("FOOBAR", tree.get("received").asString())
        val valid = mutableListOf<String>()
        tree.get("validValues").forEach { valid.add(it.asString()) }
        assertEquals(listOf("ALPHA", "BETA"), valid)
        assertTrue(!tree.get("detail").asString().contains("dev.gvart"), "detail must not leak FQN: ${tree.get("detail").asString()}")
        assertTrue(!tree.get("detail").asString().contains("."), "detail uses simple name only: ${tree.get("detail").asString()}")
    }

    @Test
    fun `unrecognized ToolExecutionException is rethrown so callers still observe the failure`() {
        val def = ToolDefinition.builder().name("test").description("t").inputSchema(harvestSchema).build()
        val cause = RuntimeException("something else")
        val throwing = ThrowingToolCallback(def, ToolExecutionException(def, cause))
        val wrapped = McpToolInputValidationCallback(throwing)

        try {
            wrapped.call("""{"itemId":"WOOD"}""", ToolContext(emptyMap()))
            error("expected ToolExecutionException to bubble up")
        } catch (e: ToolExecutionException) {
            assertEquals(cause, e.cause)
        }
    }

    enum class ProbeEnum { ALPHA, BETA }

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

    private class ThrowingToolCallback(
        private val def: ToolDefinition,
        private val toThrow: RuntimeException,
    ) : ToolCallback {
        override fun getToolDefinition(): ToolDefinition = def
        override fun getToolMetadata(): ToolMetadata = ToolMetadata.builder().build()
        override fun call(toolInput: String): String = throw toThrow
        override fun call(toolInput: String, toolContext: ToolContext?): String = throw toThrow
    }
}
