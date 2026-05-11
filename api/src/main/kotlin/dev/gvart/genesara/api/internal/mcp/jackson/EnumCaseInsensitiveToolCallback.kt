package dev.gvart.genesara.api.internal.mcp.jackson

import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata
import org.springframework.ai.util.json.JsonParser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** Spring AI's [org.springframework.ai.tool.method.MethodToolCallback] binds top-level
 *  enum-typed `@ToolParam` values via raw [java.lang.Enum.valueOf], bypassing Jackson —
 *  so [CaseInsensitiveEnumModule] never sees them. This wrapper rewrites the agent's JSON
 *  to canonical casing using the tool's own inputSchema as the source of truth. */
internal class EnumCaseInsensitiveToolCallback(
    private val delegate: ToolCallback,
) : ToolCallback {

    private val enumNormalizers: Map<String, EnumNormalizer> =
        parseEnumNormalizers(delegate.toolDefinition.inputSchema())

    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

    override fun getToolMetadata(): ToolMetadata = delegate.toolMetadata

    override fun call(toolInput: String): String = call(toolInput, null)

    override fun call(toolInput: String, toolContext: ToolContext?): String {
        if (enumNormalizers.isEmpty()) return delegate.call(toolInput, toolContext)
        val rewritten = rewriteEnumFields(toolInput) ?: toolInput
        return delegate.call(rewritten, toolContext)
    }

    private fun rewriteEnumFields(toolInput: String): String? {
        val mapper = JsonParser.getJsonMapper()
        val node = runCatching { mapper.readTree(toolInput) }.getOrNull() ?: return null
        if (node !is ObjectNode) return null
        var changed = false
        for ((field, normalizer) in enumNormalizers) {
            val current = node.get(field) ?: continue
            if (!current.isString) continue
            val raw = current.asString()
            val canonical = normalizer.canonicalFor(raw) ?: continue
            if (canonical == raw) continue
            node.put(field, canonical)
            changed = true
        }
        return if (changed) mapper.writeValueAsString(node) else null
    }

    private class EnumNormalizer(values: List<String>) {
        private val byLowercase: Map<String, String> = values.associateBy { it.lowercase() }

        fun canonicalFor(raw: String): String? = byLowercase[raw.trim().lowercase()]
    }

    private companion object {
        fun parseEnumNormalizers(schema: String): Map<String, EnumNormalizer> {
            val tree = runCatching { JsonParser.getJsonMapper().readTree(schema) }.getOrNull()
                ?: return emptyMap()
            val properties = tree.get("properties") as? ObjectNode ?: return emptyMap()
            val result = mutableMapOf<String, EnumNormalizer>()
            for (entry in properties.properties()) {
                val enumNode: JsonNode = entry.value.get("enum") ?: continue
                if (!enumNode.isArray) continue
                val values = enumNode.mapNotNull { it.asString().takeIf { v -> v.isNotEmpty() } }
                if (values.isNotEmpty()) result[entry.key] = EnumNormalizer(values)
            }
            return result
        }
    }
}

internal class EnumCaseInsensitiveToolCallbackProvider(
    private val delegate: ToolCallbackProvider,
) : ToolCallbackProvider {

    override fun getToolCallbacks(): Array<ToolCallback> =
        delegate.toolCallbacks.map { EnumCaseInsensitiveToolCallback(it) }.toTypedArray()
}
