package dev.gvart.genesara.api.internal.mcp.jackson

import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.execution.ToolExecutionException
import org.springframework.ai.tool.metadata.ToolMetadata
import org.springframework.ai.util.json.JsonParser
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.exc.InvalidFormatException
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.node.ObjectNode

/** Wraps Spring AI's [org.springframework.ai.tool.method.MethodToolCallback] argument binder so its
 *  failures surface as a structured `{"kind":"rejected", "reason":..., "detail":..., "validValues":[...]}`
 *  payload instead of a Kotlin / Jackson stack trace with fully-qualified internal class names. Two
 *  layers: a schema-driven pre-check rejects obvious cases (missing top-level required, unknown
 *  top-level enum) before the binder runs; a try/catch around the delegate translates anything the
 *  pre-check can't see (Map-key enums, Kotlin non-null NPE) into the same shape. Runs *inside*
 *  [EnumCaseInsensitiveToolCallback] so case-normalised enums reach this layer in canonical form. */
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
        val mapper = JsonParser.getJsonMapper()
        val preCheck = preValidate(mapper, toolInput)
        if (preCheck != null) return preCheck
        return try {
            delegate.call(toolInput, toolContext)
        } catch (e: ToolExecutionException) {
            translate(mapper, e.cause ?: e) ?: throw e
        }
    }

    private fun preValidate(mapper: ObjectMapper, toolInput: String): String? {
        if (required.isEmpty() && enumValues.isEmpty()) return null
        val node = runCatching { mapper.readTree(toolInput) }.getOrNull()
        if (node !is ObjectNode) return null

        for (param in required) {
            val value = node.get(param)
            if (value == null || value.isNull) return missingRequired(mapper, param)
            if (value.isString && value.asString().isBlank()) return blankRequired(mapper, param)
        }

        for ((param, allowed) in enumValues) {
            val value = node.get(param) ?: continue
            if (!value.isString) continue
            val raw = value.asString()
            if (raw !in allowed) return unknownEnum(mapper, param, raw, allowed)
        }
        return null
    }

    private fun translate(mapper: ObjectMapper, cause: Throwable): String? {
        cause.findKotlinNonNullParameter()?.let { return missingRequired(mapper, it) }
        (cause as? MismatchedInputException)?.let { return fromJackson(mapper, it) }
        cause.findEnumValueOf()?.let { (klass, value) ->
            return unknownEnum(mapper, klass.simpleName, value, klass.enumConstantNames())
        }
        return null
    }

    private fun fromJackson(mapper: ObjectMapper, exc: MismatchedInputException): String? {
        val target = exc.targetType ?: return null
        if (!target.isEnum) return null
        val attemptedValue = (exc as? InvalidFormatException)?.value?.toString()
            ?: extractJacksonAttemptedValue(exc.originalMessage)
            ?: return null
        val parameter = exc.path.firstOrNull()?.propertyName ?: target.simpleName
        return unknownEnum(mapper, parameter, attemptedValue, target.enumConstantNames())
    }

    private fun missingRequired(mapper: ObjectMapper, parameter: String): String =
        rejection(mapper, "missing_required", "required parameter '$parameter' was not provided", parameter = parameter)

    private fun blankRequired(mapper: ObjectMapper, parameter: String): String =
        rejection(mapper, "missing_required", "required parameter '$parameter' must not be blank", parameter = parameter)

    private fun unknownEnum(
        mapper: ObjectMapper,
        parameter: String,
        received: String,
        validValues: List<String>,
    ): String = rejection(
        mapper,
        reason = "unknown_enum_value",
        detail = "$parameter: '$received' is not one of [${validValues.joinToString()}]",
        parameter = parameter,
        received = received,
        validValues = validValues,
    )

    private fun rejection(
        mapper: ObjectMapper,
        reason: String,
        detail: String,
        parameter: String? = null,
        received: String? = null,
        validValues: List<String>? = null,
    ): String {
        val root = mapper.createObjectNode()
        root.put("kind", "rejected")
        root.put("reason", reason)
        root.put("detail", detail)
        if (parameter != null) root.put("parameter", parameter)
        if (received != null) root.put("received", received)
        if (validValues != null) {
            val arr = root.putArray("validValues")
            validValues.forEach { arr.add(it) }
        }
        return mapper.writeValueAsString(root)
    }

    private companion object {
        private val NON_NULL_PARAM = Regex("""Parameter specified as non-null is null:.*parameter\s+(\w+)""")
        private val NO_ENUM_CONSTANT = Regex("""No enum constant ([\w.$]+)\.(\w+)""")
        private val JACKSON_FROM_STRING = Regex("""from String "([^"]*)"""")

        fun Throwable.findKotlinNonNullParameter(): String? {
            val msg = this.message ?: return null
            return NON_NULL_PARAM.find(msg)?.groupValues?.get(1)
        }

        fun Throwable.findEnumValueOf(): Pair<Class<*>, String>? {
            val msg = this.message ?: return null
            val match = NO_ENUM_CONSTANT.find(msg) ?: return null
            val klass = runCatching { Class.forName(match.groupValues[1]) }.getOrNull() ?: return null
            if (!klass.isEnum) return null
            return klass to match.groupValues[2]
        }

        fun extractJacksonAttemptedValue(message: String?): String? {
            if (message == null) return null
            return JACKSON_FROM_STRING.find(message)?.groupValues?.get(1)
        }

        fun Class<*>.enumConstantNames(): List<String> =
            (enumConstants ?: emptyArray<Any>()).map { (it as Enum<*>).name }

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
