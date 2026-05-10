package dev.gvart.genesara.player.classes

import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.ClassDefinition
import dev.gvart.genesara.player.SkillId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ClassFingerprintScorerTest {

    @Test
    fun `picks the two highest dot-product scores`() {
        val classes = listOf(
            classFor(AgentClass.SOLDIER, mapOf("COMBAT" to 1.0)),
            classFor(AgentClass.HUNTER, mapOf("COMBAT" to 0.6, "GATHER" to 0.4)),
            classFor(AgentClass.ARTISAN, mapOf("CRAFT" to 1.0)),
            classFor(AgentClass.MERCHANT, mapOf("TRADE" to 1.0)),
        )
        val snapshot = mapOf("COMBAT" to 100, "GATHER" to 50)

        val topTwo = ClassFingerprintScorer.scoreTopTwo(snapshot, classes)

        // SOLDIER: 100. HUNTER: 60 + 20 = 80. ARTISAN/MERCHANT: 0.
        assertEquals(listOf(AgentClass.SOLDIER, AgentClass.HUNTER), topTwo)
    }

    @Test
    fun `ties break by AgentClass ordinal so the outcome is deterministic`() {
        val classes = listOf(
            classFor(AgentClass.MEDIC, mapOf("MEDICAL" to 1.0)),
            classFor(AgentClass.SOLDIER, mapOf("MEDICAL" to 1.0)),
            classFor(AgentClass.RESEARCHER, mapOf("MEDICAL" to 1.0)),
        )
        val snapshot = mapOf("MEDICAL" to 5)

        val topTwo = ClassFingerprintScorer.scoreTopTwo(snapshot, classes)

        // All three score 5; ordinal order is SOLDIER(0), MEDIC(5), RESEARCHER(7).
        assertEquals(listOf(AgentClass.SOLDIER, AgentClass.MEDIC), topTwo)
    }

    @Test
    fun `axes missing from the snapshot contribute zero`() {
        val classes = listOf(
            classFor(AgentClass.SCOUT, mapOf("EXPLORE" to 1.0, "SOCIAL" to 0.5)),
            classFor(AgentClass.MERCHANT, mapOf("SOCIAL" to 1.0, "TRADE" to 1.0)),
        )
        val snapshot = mapOf("EXPLORE" to 10)

        val topTwo = ClassFingerprintScorer.scoreTopTwo(snapshot, classes)

        // SCOUT: 10. MERCHANT: 0.
        assertEquals(listOf(AgentClass.SCOUT, AgentClass.MERCHANT), topTwo)
    }

    @Test
    fun `empty snapshot still produces a top-2 ordered by enum ordinal`() {
        val classes = listOf(
            classFor(AgentClass.RESEARCHER, mapOf("SOCIAL" to 1.0)),
            classFor(AgentClass.SOLDIER, mapOf("COMBAT" to 1.0)),
            classFor(AgentClass.SCOUT, mapOf("EXPLORE" to 1.0)),
        )

        val topTwo = ClassFingerprintScorer.scoreTopTwo(emptyMap(), classes)

        assertEquals(listOf(AgentClass.SOLDIER, AgentClass.SCOUT), topTwo)
    }

    @Test
    fun `single-class catalog returns just that class`() {
        val classes = listOf(classFor(AgentClass.SOLDIER, mapOf("COMBAT" to 1.0)))

        val topTwo = ClassFingerprintScorer.scoreTopTwo(mapOf("COMBAT" to 1), classes)

        assertEquals(listOf(AgentClass.SOLDIER), topTwo)
    }

    private fun classFor(id: AgentClass, fingerprint: Map<String, Double>) = ClassDefinition(
        id = id,
        displayName = id.name,
        description = "",
        sightRange = 3,
        primarySkills = emptySet<SkillId>(),
        neutralSkills = emptySet(),
        forbiddenCombatSkills = emptySet(),
        damageMultipliers = emptyMap(),
        behaviorFingerprint = fingerprint,
    )
}
