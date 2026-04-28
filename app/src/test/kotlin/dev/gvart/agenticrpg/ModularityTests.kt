package dev.gvart.agenticrpg

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTests {

    private val modules = ApplicationModules.of(AgenticRpgApplication::class.java)

    @Test
    fun verifiesModuleStructure() {
        modules.verify()
    }
}