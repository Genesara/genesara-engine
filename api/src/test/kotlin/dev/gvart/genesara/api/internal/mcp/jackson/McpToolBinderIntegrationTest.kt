package dev.gvart.genesara.api.internal.mcp.jackson

import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.method.MethodToolCallback
import org.springframework.ai.util.json.JsonParser
import org.springframework.ai.util.json.schema.JsonSchemaGenerator
import java.lang.reflect.Method
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpToolBinderIntegrationTest {

    enum class TestBuildingType { CAMPFIRE, WORKBENCH, STORAGE_CHEST }
    enum class TestAttribute { STRENGTH, DEXTERITY, INTELLIGENCE }

    @Suppress("unused")
    internal class TestTool {
        fun build(type: TestBuildingType, toolContext: ToolContext): String = "built ${type.name}"
        fun allocate(deltas: Map<TestAttribute, Int>, toolContext: ToolContext): String = "allocated ${deltas.size}"
        fun inspect(targetType: TestBuildingType, targetId: String, toolContext: ToolContext): String =
            "inspected ${targetType.name} $targetId"
        fun harvest(itemId: String, toolContext: ToolContext): String = "harvested $itemId"
    }

    private val ctx = ToolContext(mapOf("any" to "value"))

    private fun wrap(methodName: String): EnumCaseInsensitiveToolCallback {
        val method: Method = TestTool::class.java.declaredMethods.first { it.name == methodName }
        val schema = JsonSchemaGenerator.generateForMethodInput(method)
        val def = ToolDefinition.builder().name(methodName).description("test").inputSchema(schema).build()
        val raw = MethodToolCallback(def, null, method, TestTool(), null)
        return EnumCaseInsensitiveToolCallback(McpToolInputValidationCallback(raw))
    }

    @Test
    fun `build with unknown BuildingType returns structured rejection without FQN`() {
        val out = wrap("build").call("""{"type":"CASTLE"}""", ctx)
        val tree = JsonParser.getJsonMapper().readTree(out)

        assertEquals("rejected", tree.get("kind").asString())
        assertEquals("unknown_enum_value", tree.get("reason").asString())
        assertEquals("type", tree.get("parameter").asString())
        assertEquals("CASTLE", tree.get("received").asString())
        val valid = mutableListOf<String>()
        tree.get("validValues").forEach { valid.add(it.asString()) }
        assertEquals(listOf("CAMPFIRE", "WORKBENCH", "STORAGE_CHEST"), valid)
        assertTrue(!out.contains("dev.gvart"), "must not leak FQN: $out")
        assertTrue(!out.contains("TestBuildingType."), "no FQN-ish dotted enum names: $out")
    }

    @Test
    fun `allocate with unknown Map-key enum returns structured rejection without FQN`() {
        val out = wrap("allocate").call("""{"deltas":{"FOOBAR":1}}""", ctx)
        val tree = JsonParser.getJsonMapper().readTree(out)

        assertEquals("rejected", tree.get("kind").asString())
        assertEquals("unknown_enum_value", tree.get("reason").asString())
        assertEquals("FOOBAR", tree.get("received").asString())
        val valid = mutableListOf<String>()
        tree.get("validValues").forEach { valid.add(it.asString()) }
        assertEquals(setOf("STRENGTH", "DEXTERITY", "INTELLIGENCE"), valid.toSet())
        assertTrue(!out.contains("dev.gvart"), "must not leak FQN: $out")
        assertTrue(!out.contains("McpToolBinderIntegrationTest"), "must not leak nested class FQN: $out")
    }

    @Test
    fun `inspect with lowercase targetType is canonicalised and succeeds`() {
        val out = wrap("inspect").call("""{"targetType":"campfire","targetId":"x"}""", ctx)
        assertTrue(out.contains("CAMPFIRE"), "lowercase enum should be normalised: $out")
    }

    @Test
    fun `inspect with missing required parameter returns structured rejection`() {
        val out = wrap("inspect").call("""{}""", ctx)
        val tree = JsonParser.getJsonMapper().readTree(out)

        assertEquals("rejected", tree.get("kind").asString())
        assertEquals("missing_required", tree.get("reason").asString())
        assertEquals("targetType", tree.get("parameter").asString())
    }

    @Test
    fun `harvest with missing required string parameter returns structured rejection`() {
        val out = wrap("harvest").call("""{}""", ctx)
        val tree = JsonParser.getJsonMapper().readTree(out)

        assertEquals("rejected", tree.get("kind").asString())
        assertEquals("missing_required", tree.get("reason").asString())
        assertEquals("itemId", tree.get("parameter").asString())
    }
}
