package dev.gvart.genesara

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTests {

    private val modules = ApplicationModules.of(GenesaraApplication::class.java)

    @Test
    fun verifiesModuleStructure() {
        modules.verify()
    }
}