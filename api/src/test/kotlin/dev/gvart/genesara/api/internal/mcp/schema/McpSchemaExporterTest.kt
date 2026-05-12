package dev.gvart.genesara.api.internal.mcp.schema

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the build-time export. A regression here means the docs site would
 * publish a broken catalog or break its parse step, which is worse than not
 * publishing at all.
 */
class McpSchemaExporterTest {

    @Test
    fun `every discovered tool has a usable schema`() {
        val doc = McpSchemaExporter.export()

        assertTrue(doc.tools.isNotEmpty(), "no tools exported — classpath scan misconfigured?")
        for (tool in doc.tools) {
            assertTrue(tool.name.isNotBlank(), "tool has blank name: $tool")
            assertTrue(tool.description.isNotBlank(), "${tool.name} has blank description")
            val type = tool.inputSchema.get("type")
            assertTrue(
                type != null && type.asString() == "object",
                "${tool.name} inputSchema missing top-level type=object: ${tool.inputSchema}",
            )
        }
    }

    @Test
    fun `tools are sorted alphabetically by name`() {
        val names = McpSchemaExporter.export().tools.map { it.name }
        assertEquals(names.sorted(), names, "tool list must be sorted for stable diffs across releases")
    }

    @Test
    fun `well-known tools are present in the catalog`() {
        // Smoke check against tools that have shipped and are unlikely to ever be removed
        // wholesale. Catches the case where the classpath scan misses a registration root.
        val names = McpSchemaExporter.export().tools.map { it.name }.toSet()
        listOf("spawn", "move", "look_around", "say", "trade_offer", "trade_respond").forEach {
            assertTrue(it in names, "expected '$it' in exported catalog, got $names")
        }
    }

    @Test
    fun `document carries version and ISO-8601 generatedAt`() {
        val doc = McpSchemaExporter.export(version = "1.2.3")
        assertEquals("1.2.3", doc.version)
        // Round-trip via Instant.parse to assert the string is well-formed UTC ISO-8601.
        Instant.parse(doc.generatedAt)
    }
}
