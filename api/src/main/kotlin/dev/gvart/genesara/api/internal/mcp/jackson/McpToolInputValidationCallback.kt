package dev.gvart.genesara.api.internal.mcp.jackson

import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata
import org.springframework.ai.util.json.JsonParser
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** Catches Spring AI [org.springframework.ai.tool.method.MethodToolCallback] binder errors
 *  before they reach the reflective invocation — Kotlin NPE on a missing required param and
 *  `IllegalArgumentException` on an unknown enum constant both leak fully-qualified internal
 *  class names back to the agent. Pre-validates against the tool's own inputSchema and
 *  returns a structured `{"kind":"error", ...}` JSON shape instead. Runs *inside*
 *  [EnumCaseInsensitiveToolCallback], so any case-normalised enum value has already been
 *  rewritten to its canonical form by the time we validate. */
internal class McpToolInputValidationCallback(
    private val delegate: ToolCallback,
) : ToolCallback {

    private val required: List<String>
    private val enumValues: Map<String, List<String>>

    init {
        val (req, enums) = parseSchema(delegate.toolDefinition.inputSchema())
        required = req
        enumValues = enums
    }

    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

    override fun getToolMetadata(): ToolMetadata = delegate.toolMetadata

    override fun call(toolInput: String): String = call(toolInput, null)

    override fun call(toolInput: String, toolContext: ToolContext?): String {
        if (required.isEmpty() && enumValues.isEmpty()) return delegate.call(toolInput, toolContext)
        val mapper = JsonParser.getJsonMapper()
        val node = runCatching { mapper.readTree(toolInput) }.getOrNull()
        if (node !is ObjectNode) return delegate.call(toolInput, toolContext)

        for (param in required) {
            val value = node.get(param)
            if (value == null || value.isNull) {
                return missingParamError(mapper, param)
            }
            if (value.isString && value.asString().isBlank()) {
                return blankParamError(mapper, param)
            }
        }

        for ((param, allowed) in enumValues) {
            val value = node.get(param) ?: continue
            if (!value.isString) continue
            val raw = value.asString()
            if (raw !in allowed) {
                return unknownEnumError(mapper, param, raw, allowed)
            }
        }

        return delegate.call(toolInput, toolContext)
    }

    private fun missingParamError(mapper: ObjectMapper, parameter: String): String {
        val error = mapper.createObjectNode()
        error.put("code", "MISSING_REQUIRED_PARAMETER")
        error.put("parameter", parameter)
        error.put("message", "Required parameter '$parameter' is missing")
        return wrap(mapper, error)
    }

    private fun blankParamError(mapper: ObjectMapper, parameter: String): String {
        val error = mapper.createObjectNode()
        error.put("code", "MISSING_REQUIRED_PARAMETER")
        error.put("parameter", parameter)
        error.put("message", "Required parameter '$parameter' must not be blank")
        return wrap(mapper, error)
    }

    private fun unknownEnumError(
        mapper: ObjectMapper,
        parameter: String,
        received: String,
        validValues: List<String>,
    ): String {
        val error = mapper.createObjectNode()
        error.put("code", "INVALID_ENUM_VALUE")
        error.put("parameter", parameter)
        error.put("received", received)
        error.put(
            "message",
            "Unknown value '$received' for parameter '$parameter'. Valid values: ${validValues.joinToString()}",
        )
        val arr = error.putArray("validValues")
        validValues.forEach { arr.add(it) }
        return wrap(mapper, error)
    }

    private fun wrap(mapper: ObjectMapper, error: ObjectNode): String {
        val root = mapper.createObjectNode()
        root.put("kind", "error")
        root.set("error", error)
        return mapper.writeValueAsString(root)
    }

    private companion object {
        fun parseSchema(schema: String): Pair<List<String>, Map<String, List<String>>> {
            val tree = runCatching { JsonParser.getJsonMapper().readTree(schema) }.getOrNull()
                ?: return emptyList<String>() to emptyMap()
            val requiredNode = tree.get("required")
            val required = if (requiredNode != null && requiredNode.isArray) {
                val out = mutableListOf<String>()
                requiredNode.forEach { it.asString().takeIf { v -> v.isNotEmpty() }?.let(out::add) }
                out.toList()
            } else emptyList()
            val properties = tree.get("properties") as? ObjectNode ?: return required to emptyMap()
            val enums = mutableMapOf<String, List<String>>()
            for (entry in properties.properties()) {
                val enumNode = entry.value.get("enum") ?: continue
                if (!enumNode.isArray) continue
                val values = mutableListOf<String>()
                enumNode.forEach { it.asString().takeIf { v -> v.isNotEmpty() }?.let(values::add) }
                if (values.isNotEmpty()) enums[entry.key] = values.toList()
            }
            return required to enums
        }
    }
}
