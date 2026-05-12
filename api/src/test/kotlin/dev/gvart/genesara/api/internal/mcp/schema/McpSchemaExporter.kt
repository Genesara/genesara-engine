package dev.gvart.genesara.api.internal.mcp.schema

import org.mockito.Mockito.mock
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Build-time emitter for the catalog of `@Tool`-annotated MCP tools exposed by `:api`.
 *
 * Produces the JSON contract consumed by `docs.genesara.com`
 * (`Genesara/genesara-docs:scripts/build-tools-json.ts`). The shape — top-level
 * `version` + `generatedAt` + alphabetical `tools` array, each entry with
 * `name` / `description` / `inputSchema` — is a stable cross-repo contract;
 * extending it requires coordinating with docs first.
 *
 * Tool beans are discovered via Spring's classpath scanner over
 * `dev.gvart.genesara.api.internal.mcp.tools`. Each candidate is instantiated as a
 * Mockito mock so the tool's constructor dependencies (`WorldCommandGateway`, `TickClock`,
 * etc.) need not be wired — Spring AI only reflects on the `@Tool` methods to build the
 * schema; the bean instances themselves are never invoked.
 */
internal object McpSchemaExporter {

    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    data class ToolSchema(
        val name: String,
        val description: String,
        val inputSchema: JsonNode,
    )

    data class SchemaDocument(
        val version: String,
        val generatedAt: String,
        val tools: List<ToolSchema>,
    )

    fun export(version: String = SNAPSHOT_VERSION, generatedAt: Instant = Instant.now()): SchemaDocument {
        val toolClasses = discoverToolClasses()
        check(toolClasses.isNotEmpty()) { "no @Tool beans discovered under mcp.tools — package layout drifted?" }
        val mocks: Array<Any> = toolClasses.map { mock(it) }.toTypedArray()
        val provider = MethodToolCallbackProvider.builder().toolObjects(*mocks).build()
        val tools = provider.toolCallbacks
            .map { callback ->
                val def = callback.toolDefinition
                ToolSchema(
                    name = def.name(),
                    description = def.description(),
                    inputSchema = mapper.readTree(def.inputSchema()),
                )
            }
            .sortedBy { it.name }
        return SchemaDocument(version = version, generatedAt = generatedAt.toString(), tools = tools)
    }

    fun writeTo(output: Path, version: String): SchemaDocument {
        val doc = export(version = version)
        Files.createDirectories(output.parent)
        val payload = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc)
        output.toFile().writeText(payload + "\n")
        return doc
    }

    private fun discoverToolClasses(): List<Class<*>> {
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply {
            addIncludeFilter(AnnotationTypeFilter(Component::class.java))
        }
        return scanner.findCandidateComponents(TOOL_PACKAGE)
            .map { Class.forName(it.beanClassName!!) }
            // Defensive: a stray @Component under tools.* with no @Tool method must not
            // sneak into the catalog (Spring AI would crash later, with a worse trace).
            .filter { clazz -> clazz.methods.any { it.isAnnotationPresent(Tool::class.java) } }
            .sortedBy { it.name }
    }

    private const val TOOL_PACKAGE = "dev.gvart.genesara.api.internal.mcp.tools"
    internal const val SNAPSHOT_VERSION = "0.0.0-SNAPSHOT"
}

fun main(args: Array<String>) {
    val outputPath = args.getOrNull(0)?.let(::File)
        ?: error("usage: McpSchemaExporterKt <output-path> [version]")
    val version = args.getOrNull(1).orEmpty().ifBlank { McpSchemaExporter.SNAPSHOT_VERSION }
    val doc = McpSchemaExporter.writeTo(outputPath.toPath(), version)
    println("mcp-schema: wrote ${doc.tools.size} tools (version=${doc.version}) to ${outputPath.absolutePath}")
}
