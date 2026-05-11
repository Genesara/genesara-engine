package dev.gvart.genesara.api.internal.mcp.jackson

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.util.JacksonUtils
import tools.jackson.databind.exc.InvalidFormatException
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals

class CaseInsensitiveEnumModuleTest {

    private enum class Colour { RED, GREEN, BLUE }

    private val mapper: JsonMapper = JsonMapper.builder()
        .addModules(JacksonUtils.instantiateAvailableModules())
        .build()

    @Test
    fun `lowercase enum literal binds to the matching constant`() {
        assertEquals(Colour.RED, mapper.readValue("\"red\"", Colour::class.java))
    }

    @Test
    fun `mixed-case enum literal binds to the matching constant`() {
        assertEquals(Colour.GREEN, mapper.readValue("\"Green\"", Colour::class.java))
    }

    @Test
    fun `exact-match upper-case continues to work`() {
        assertEquals(Colour.BLUE, mapper.readValue("\"BLUE\"", Colour::class.java))
    }

    @Test
    fun `lowercase enum-typed map key binds`() {
        val parsed: Map<Colour, Int> = mapper.readValue(
            "{\"red\": 1, \"Green\": 2}",
            mapper.typeFactory.constructMapType(Map::class.java, Colour::class.java, Integer::class.java),
        )
        assertEquals(mapOf(Colour.RED to 1, Colour.GREEN to 2), parsed)
    }

    @Test
    fun `unknown enum literal still throws — handler defers to default error path`() {
        assertThrows<InvalidFormatException> {
            mapper.readValue("\"PURPLE\"", Colour::class.java)
        }
    }

    @Test
    fun `module is discovered via SPI`() {
        val moduleNames = JacksonUtils.instantiateAvailableModules().map { it.moduleName }
        assert(moduleNames.contains("genesara-case-insensitive-enum")) {
            "expected genesara-case-insensitive-enum in modules, got: $moduleNames"
        }
    }
}
