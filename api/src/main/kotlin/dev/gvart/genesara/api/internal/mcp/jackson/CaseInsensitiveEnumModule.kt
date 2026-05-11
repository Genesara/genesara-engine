package dev.gvart.genesara.api.internal.mcp.jackson

import tools.jackson.core.Version
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JacksonModule
import tools.jackson.databind.deser.DeserializationProblemHandler

/** Picked up via SPI by Spring AI's static [tools.jackson.databind.json.JsonMapper] — fixes
 *  case-sensitive enum binding on Jackson-routed paths (e.g. map keys in `allocate_points`). */
class CaseInsensitiveEnumModule : JacksonModule() {

    override fun getModuleName(): String = "genesara-case-insensitive-enum"

    override fun version(): Version = Version.unknownVersion()

    override fun setupModule(context: SetupContext) {
        context.addHandler(Handler)
    }

    private object Handler : DeserializationProblemHandler() {

        override fun handleWeirdStringValue(
            ctx: DeserializationContext,
            targetType: Class<*>,
            valueToConvert: String,
            failureMsg: String?,
        ): Any = matchEnum(targetType, valueToConvert) ?: NOT_HANDLED

        override fun handleWeirdKey(
            ctx: DeserializationContext,
            rawKeyType: Class<*>,
            keyValue: String,
            failureMsg: String?,
        ): Any = matchEnum(rawKeyType, keyValue) ?: NOT_HANDLED

        private fun matchEnum(targetType: Class<*>, raw: String): Enum<*>? {
            if (!targetType.isEnum) return null
            val trimmed = raw.trim()
            @Suppress("UNCHECKED_CAST")
            val constants = (targetType as Class<out Enum<*>>).enumConstants ?: return null
            return constants.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        }
    }
}
