package dev.gvart.genesara.player.internal.store

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.Perk
import dev.gvart.genesara.player.PerkChoice
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.RecordPerkResult
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENTS
import dev.gvart.genesara.player.internal.jooq.tables.references.AGENT_PERKS
import dev.gvart.genesara.player.internal.testsupport.PlayerFlyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

@Testcontainers
class JooqAgentPerksRegistryIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("player_perks_it")
            .withUsername("test")
            .withPassword("test")

        private lateinit var dataSource: HikariDataSource
        private lateinit var dsl: DSLContext

        @BeforeAll
        @JvmStatic
        fun migrateOnce() {
            dataSource = PlayerFlyway.pooledDataSource(postgres)
            PlayerFlyway.migrate(dataSource)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @AfterAll
        @JvmStatic
        fun closePool() {
            dataSource.close()
        }
    }

    private val sword = SkillId("SWORD")
    private val bleeder = stubPerk(
        id = "SWORD_BLEEDER",
        skill = sword,
        milestoneLevel = 50,
        effect = PerkEffect.TriggeredPassive(
            trigger = dev.gvart.genesara.player.TriggeredPassiveTrigger.ON_HIT_DEALT,
            effectKind = dev.gvart.genesara.player.TriggeredPassiveEffectKind.APPLY_STATUS_TO_TARGET,
            params = mapOf("status" to "BLEED"),
            internalCooldownTicks = 8,
        ),
    )
    private val sharpen = stubPerk(
        id = "SWORD_SHARPEN_EDGE",
        skill = sword,
        milestoneLevel = 50,
        effect = PerkEffect.PassiveAura(target = ScalingEffect.SLASH_DAMAGE_BONUS, magnitude = 5),
    )
    private val perks = StubPerkLookup(listOf(bleeder, sharpen))

    private lateinit var registry: JooqAgentPerksRegistry
    private var agent: AgentId = AgentId(UUID.randomUUID())

    @BeforeEach
    fun resetTables() {
        dsl.truncate(AGENT_PERKS).cascade().execute()
        dsl.truncate(AGENTS).cascade().execute()
        agent = createAgent()
        registry = JooqAgentPerksRegistry(dsl, perks)
    }

    @Test
    fun `recordChoice writes a row and snapshot reads it back`() {
        val result = registry.recordChoice(agent, bleeder.id, tick = 42L)
        assertEquals(RecordPerkResult.Recorded, result)

        val snap = registry.snapshot(agent)
        assertEquals(1, snap.perks.size)
        val pick = snap.perks.single()
        assertEquals(sword, pick.skill)
        assertEquals(50, pick.milestoneLevel)
        assertEquals(bleeder.id, pick.perkId)
        assertEquals(42L, pick.chosenAtTick)
    }

    @Test
    fun `recordChoice rejects an unknown perk`() {
        val result = registry.recordChoice(agent, PerkId("PHANTOM"), tick = 0L)
        val unknown = assertIs<RecordPerkResult.UnknownPerk>(result)
        assertEquals(PerkId("PHANTOM"), unknown.perk)

        val rowCount = dsl.fetchCount(AGENT_PERKS)
        assertEquals(0, rowCount, "rejected pick must not write a row")
    }

    @Test
    fun `recordChoice rejects re-pick at the same milestone, naming the existing pick`() {
        assertEquals(RecordPerkResult.Recorded, registry.recordChoice(agent, bleeder.id, tick = 1L))

        val second = registry.recordChoice(agent, sharpen.id, tick = 2L)
        val already = assertIs<RecordPerkResult.MilestoneAlreadyChosen>(second)
        assertEquals(sword, already.skill)
        assertEquals(50, already.milestoneLevel)
        assertEquals(bleeder.id, already.existing)

        val pick = registry.snapshot(agent).perks.single()
        assertEquals(bleeder.id, pick.perkId)
        assertEquals(1L, pick.chosenAtTick)
    }

    @Test
    fun `snapshot is empty for a fresh agent`() {
        assertEquals(emptyList(), registry.snapshot(agent).perks)
    }

    @Test
    fun `adminRevoke removes the perk row and returns true`() {
        assertEquals(RecordPerkResult.Recorded, registry.recordChoice(agent, bleeder.id, tick = 0L))

        val removed = registry.adminRevoke(agent, bleeder.id)

        assertEquals(true, removed)
        assertEquals(emptyList(), registry.snapshot(agent).perks)
    }

    @Test
    fun `adminRevoke returns false when the perk row does not exist`() {
        assertEquals(false, registry.adminRevoke(agent, bleeder.id))
    }

    @Test
    fun `snapshot orders rows by skill ascending then milestone ascending`() {
        val bowQuickDraw = stubPerk(
            id = "BOW_QUICK_DRAW",
            skill = SkillId("BOW"),
            milestoneLevel = 50,
            effect = PerkEffect.PassiveAura(target = ScalingEffect.PIERCE_DAMAGE_BONUS, magnitude = 1),
        )
        val swordHundredA = stubPerk(
            id = "SWORD_RIPOSTE",
            skill = SkillId("SWORD"),
            milestoneLevel = 100,
            effect = PerkEffect.PassiveAura(target = ScalingEffect.SLASH_DAMAGE_BONUS, magnitude = 1),
        )
        val multiSkillRegistry = JooqAgentPerksRegistry(
            dsl,
            StubPerkLookup(listOf(bleeder, sharpen, bowQuickDraw, swordHundredA)),
        )

        multiSkillRegistry.recordChoice(agent, swordHundredA.id, tick = 3L)
        multiSkillRegistry.recordChoice(agent, bleeder.id, tick = 2L)
        multiSkillRegistry.recordChoice(agent, bowQuickDraw.id, tick = 1L)

        val snap = multiSkillRegistry.snapshot(agent)
        assertEquals(
            listOf(
                SkillId("BOW") to 50,
                SkillId("SWORD") to 50,
                SkillId("SWORD") to 100,
            ),
            snap.perks.map { it.skill to it.milestoneLevel },
        )
    }

    private fun createAgent(): AgentId {
        val id = AgentId(UUID.randomUUID())
        dsl.insertInto(AGENTS)
            .set(AGENTS.ID, id.id)
            .set(AGENTS.OWNER_ID, UUID.randomUUID())
            .set(AGENTS.NAME, "test-${id.id.toString().take(6)}")
            .set(AGENTS.RACE_ID, "human_commoner")
            .set(AGENTS.LEVEL, 1)
            .set(AGENTS.XP_CURRENT, 0)
            .set(AGENTS.XP_TO_NEXT, 100)
            .set(AGENTS.UNSPENT_ATTRIBUTE_POINTS, 5)
            .set(AGENTS.STRENGTH, 5)
            .set(AGENTS.DEXTERITY, 5)
            .set(AGENTS.CONSTITUTION, 5)
            .set(AGENTS.PERCEPTION, 5)
            .set(AGENTS.INTELLIGENCE, 5)
            .set(AGENTS.LUCK, 5)
            .execute()
        return id
    }

    private fun stubPerk(id: String, skill: SkillId, milestoneLevel: Int, effect: PerkEffect) = Perk(
        id = PerkId(id),
        skill = skill,
        milestoneLevel = milestoneLevel,
        displayName = id,
        description = id,
        effect = effect,
    )

    private class StubPerkLookup(private val perks: List<Perk>) : PerkLookup {
        private val byId = perks.associateBy { it.id }
        override fun byId(id: PerkId): Perk? = byId[id]
        override fun choicesAt(skill: SkillId, milestoneLevel: Int): PerkChoice? {
            val opts = perks.filter { it.skill == skill && it.milestoneLevel == milestoneLevel }
            return if (opts.isEmpty()) null else PerkChoice(skill, milestoneLevel, opts)
        }
        override fun choicesFor(skill: SkillId): List<PerkChoice> =
            perks.filter { it.skill == skill }
                .groupBy { it.milestoneLevel }
                .toSortedMap()
                .map { (level, list) -> PerkChoice(skill, level, list) }
        override fun all(): List<Perk> = perks
    }
}
