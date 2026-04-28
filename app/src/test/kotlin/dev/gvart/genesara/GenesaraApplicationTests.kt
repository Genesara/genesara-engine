package dev.gvart.genesara

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
@Disabled(
    "Boots the full Spring context and so requires a live Postgres + Redis. " +
        "Re-enable once a Testcontainers harness is wired in (issue: open one before flipping). " +
        "ModularityTests covers the boundary-verification sanity check in the meantime.",
)
class GenesaraApplicationTests {

    @Test
    fun contextLoads() {
    }
}