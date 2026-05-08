package dev.gvart.genesara.api.internal.mcp.tools.getstatus

import dev.gvart.genesara.api.internal.mcp.context.AgentContextHolder
import dev.gvart.genesara.api.internal.mcp.presence.AgentActivityRegistry
import dev.gvart.genesara.engine.TickClock
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentPerk
import dev.gvart.genesara.player.AgentPerksRegistry
import dev.gvart.genesara.player.AgentPerksSnapshot
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.Perk
import dev.gvart.genesara.player.PerkChoice
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.PerkLookup
import dev.gvart.genesara.player.RaceId
import dev.gvart.genesara.player.RecordPerkResult
import dev.gvart.genesara.player.Skill
import dev.gvart.genesara.player.SkillCategory
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillLookup
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.WorldQueryGateway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.model.ToolContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GetStatusToolTest {

    private val agentId = AgentId(UUID.randomUUID())
    private val owner = PlayerId(UUID.randomUUID())
    private val agent = Agent(
        id = agentId,
        owner = owner,
        name = "Komar",
        race = RaceId("human_steppe"),
        level = 3,
        xpCurrent = 42,
        xpToNext = 100,
        unspentAttributePoints = 5,
        attributes = AgentAttributes(
            strength = 4,
            dexterity = 6,
            constitution = 5,
            perception = 7,
            intelligence = 3,
            luck = 2,
        ),
    )
    private val body = BodyView(
        hp = 80, maxHp = 100,
        stamina = 35, maxStamina = 60,
        mana = 5, maxMana = 15,
        hunger = 90, maxHunger = 100,
        thirst = 70, maxThirst = 100,
        sleep = 40, maxSleep = 100,
    )
    private val node = NodeId(99L)

    private val foraging = SkillId("FORAGING")
    private val mining = SkillId("MINING")
    private val skillCatalog = StubSkillLookup(
        listOf(
            Skill(foraging, "Foraging", "plants", SkillCategory.GATHERING),
            Skill(mining, "Mining", "rocks", SkillCategory.GATHERING),
        ),
    )
    private val emptySkills = StubSkillsRegistry(
        AgentSkillsSnapshot(perSkill = emptyMap(), slotCount = 8, slotsFilled = 0),
    )

    private val clock = MutableTestClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val activity = AgentActivityRegistry(clock)
    private val tickClock = StubTickClock(currentTick = 200L)
    private val toolContext = ToolContext(emptyMap())

    @BeforeEach fun setUp() = AgentContextHolder.set(agentId)
    @AfterEach fun tearDown() = AgentContextHolder.clear()

    @Test
    fun `returns the full character snapshot for an active agent`() {
        val tool = GetStatusTool(
            agents = StubRegistry(agent),
            world = StubQuery(active = node, body = body),
            engine = tickClock,
            activity = activity,
            skillsRegistry = emptySkills,
            skillCatalog = skillCatalog,
            perksRegistry = StubPerksRegistry(),
            perkCatalog = StubPerkLookup(),
        )

        val res = tool.invoke(toolContext)

        assertEquals(agentId.id.toString(), res.agentId)
        assertEquals("Komar", res.name)
        assertEquals("human_steppe", res.race)
        assertEquals(3, res.level)
        assertEquals(XpView(current = 42, toNext = 100), res.xp)
        assertEquals(
            AttributesView(
                strength = 4, dexterity = 6, constitution = 5,
                perception = 7, intelligence = 3, luck = 2,
            ),
            res.attributes,
        )
        assertEquals(5, res.unspentAttributePoints)
        assertEquals(PoolView(80, 100), res.hp)
        assertEquals(PoolView(35, 60), res.stamina)
        assertEquals(PoolView(5, 15), res.mana)
        assertEquals(node.value, res.location)
        assertEquals(200L, res.tick)
        assertEquals(emptyList(), res.activeEffects)
        assertEquals(8, res.skills.slotCount)
        assertEquals(0, res.skills.slotsFilled)
        assertEquals(8, res.skills.slots.size)
        assertEquals(emptyList(), res.skills.unslotted)
    }

    @Test
    fun `falls back to last known location when not currently active`() {
        val tool = GetStatusTool(
            agents = StubRegistry(agent),
            world = StubQuery(active = null, lastLocation = node, body = body),
            engine = tickClock,
            activity = activity,
            skillsRegistry = emptySkills,
            skillCatalog = skillCatalog,
            perksRegistry = StubPerksRegistry(),
            perkCatalog = StubPerkLookup(),
        )

        val res = tool.invoke(toolContext)

        assertEquals(node.value, res.location)
    }

    @Test
    fun `reports null location and zero pools when agent has never spawned`() {
        val tool = GetStatusTool(
            agents = StubRegistry(agent),
            world = StubQuery(active = null, lastLocation = null, body = null),
            engine = tickClock,
            activity = activity,
            skillsRegistry = emptySkills,
            skillCatalog = skillCatalog,
            perksRegistry = StubPerksRegistry(),
            perkCatalog = StubPerkLookup(),
        )

        val res = tool.invoke(toolContext)

        assertNull(res.location)
        assertEquals(PoolView(0, 0), res.hp)
        assertEquals(PoolView(0, 0), res.stamina)
        assertEquals(PoolView(0, 0), res.mana)
    }

    @Test
    fun `errors when the agent is not registered`() {
        val tool = GetStatusTool(
            agents = StubRegistry(null),
            world = StubQuery(),
            engine = tickClock,
            activity = activity,
            skillsRegistry = emptySkills,
            skillCatalog = skillCatalog,
            perksRegistry = StubPerksRegistry(),
            perkCatalog = StubPerkLookup(),
        )

        assertThrows<IllegalStateException> { tool.invoke(toolContext) }
    }

    @Test
    fun `skills view places slotted skills by index and lists unslotted-but-discovered separately`() {
        val snapshot = AgentSkillsSnapshot(
            perSkill = mapOf(
                foraging to AgentSkillState(foraging, xp = 35, level = 3, slotIndex = 2, recommendCount = 1),
                mining to AgentSkillState(mining, xp = 0, level = 0, slotIndex = null, recommendCount = 2),
            ),
            slotCount = 8,
            slotsFilled = 1,
        )
        val tool = GetStatusTool(
            agents = StubRegistry(agent),
            world = StubQuery(active = node, body = body),
            engine = tickClock,
            activity = activity,
            skillsRegistry = StubSkillsRegistry(snapshot),
            skillCatalog = skillCatalog,
            perksRegistry = StubPerksRegistry(),
            perkCatalog = StubPerkLookup(),
        )

        val skills = tool.invoke(toolContext).skills

        assertEquals(8, skills.slotCount)
        assertEquals(1, skills.slotsFilled)
        assertEquals(8, skills.slots.size)
        skills.slots.forEachIndexed { idx, slot -> assertEquals(idx, slot.slotIndex) }
        assertEquals("FORAGING", skills.slots[2].skill?.id)
        assertEquals("Foraging", skills.slots[2].skill?.displayName)
        assertEquals("GATHERING", skills.slots[2].skill?.category)
        assertEquals(35, skills.slots[2].skill?.xp)
        assertEquals(3, skills.slots[2].skill?.level)
        assertEquals(1, skills.slots[2].skill?.recommendCount)
        listOf(0, 1, 3, 4, 5, 6, 7).forEach { idx ->
            assertNull(skills.slots[idx].skill)
        }
        assertEquals(1, skills.unslotted.size)
        assertEquals("MINING", skills.unslotted.single().id)
        assertEquals(2, skills.unslotted.single().recommendCount)
    }

    @Test
    fun `skills view handles slotCount of zero without off-by-one`() {
        val snapshot = AgentSkillsSnapshot(perSkill = emptyMap(), slotCount = 0, slotsFilled = 0)
        val tool = GetStatusTool(
            agents = StubRegistry(agent),
            world = StubQuery(active = node, body = body),
            engine = tickClock,
            activity = activity,
            skillsRegistry = StubSkillsRegistry(snapshot),
            skillCatalog = skillCatalog,
            perksRegistry = StubPerksRegistry(),
            perkCatalog = StubPerkLookup(),
        )

        val skills = tool.invoke(toolContext).skills

        assertEquals(0, skills.slotCount)
        assertEquals(emptyList(), skills.slots)
        assertEquals(emptyList(), skills.unslotted)
    }

    @Test
    fun `chosenPerks lists rows from the perks registry, ordered by skill then milestone`() {
        val sword = SkillId("SWORD")
        val bow = SkillId("BOW")
        val snapshot = AgentSkillsSnapshot(
            perSkill = mapOf(
                sword to AgentSkillState(sword, xp = 50, level = 60, slotIndex = 0, recommendCount = 1),
                bow to AgentSkillState(bow, xp = 50, level = 55, slotIndex = 1, recommendCount = 1),
            ),
            slotCount = 8,
            slotsFilled = 2,
        )
        val perksRegistry = StubPerksRegistry(
            picks = listOf(
                AgentPerk(skill = sword, milestoneLevel = 50, perkId = PerkId("SWORD_BLEEDER"), chosenAtTick = 1L),
                AgentPerk(skill = bow, milestoneLevel = 50, perkId = PerkId("BOW_QUICK_DRAW"), chosenAtTick = 2L),
            ),
        )

        val tool = GetStatusTool(
            agents = StubRegistry(agent),
            world = StubQuery(active = node, body = body),
            engine = tickClock,
            activity = activity,
            skillsRegistry = StubSkillsRegistry(snapshot),
            skillCatalog = stubSkillLookupForPerks(),
            perksRegistry = perksRegistry,
            perkCatalog = StubPerkLookup(),
        )

        val skills = tool.invoke(toolContext).skills

        assertEquals(
            listOf(
                ChosenPerkView("BOW", 50, "BOW_QUICK_DRAW"),
                ChosenPerkView("SWORD", 50, "SWORD_BLEEDER"),
            ),
            skills.chosenPerks,
        )
    }

    @Test
    fun `pendingPerkChoices is derived from slotted skill level minus chosen picks`() {
        val sword = SkillId("SWORD")
        val snapshot = AgentSkillsSnapshot(
            perSkill = mapOf(
                sword to AgentSkillState(sword, xp = 200, level = 110, slotIndex = 0, recommendCount = 1),
            ),
            slotCount = 8,
            slotsFilled = 1,
        )
        val perkCatalog = StubPerkLookup(
            listOf(
                stubPerk("SWORD_BLEEDER", sword, 50),
                stubPerk("SWORD_SHARPEN_EDGE", sword, 50),
                stubPerk("SWORD_RIPOSTE", sword, 100),
                stubPerk("SWORD_PARRY", sword, 100),
            ),
        )
        val perksRegistry = StubPerksRegistry(
            picks = listOf(
                AgentPerk(skill = sword, milestoneLevel = 50, perkId = PerkId("SWORD_BLEEDER"), chosenAtTick = 1L),
            ),
        )
        val tool = GetStatusTool(
            agents = StubRegistry(agent),
            world = StubQuery(active = node, body = body),
            engine = tickClock,
            activity = activity,
            skillsRegistry = StubSkillsRegistry(snapshot),
            skillCatalog = stubSkillLookupForPerks(),
            perksRegistry = perksRegistry,
            perkCatalog = perkCatalog,
        )

        val skills = tool.invoke(toolContext).skills

        assertEquals(1, skills.pendingPerkChoices.size)
        val pending = skills.pendingPerkChoices.single()
        assertEquals("SWORD", pending.skillId)
        assertEquals(100, pending.milestone)
        assertEquals(listOf("SWORD_RIPOSTE", "SWORD_PARRY"), pending.options)
    }

    @Test
    fun `pendingPerkChoices surfaces every reached milestone with no pick yet, ascending`() {
        val sword = SkillId("SWORD")
        val snapshot = AgentSkillsSnapshot(
            perSkill = mapOf(
                sword to AgentSkillState(sword, xp = 200, level = 110, slotIndex = 0, recommendCount = 1),
            ),
            slotCount = 8,
            slotsFilled = 1,
        )
        val perkCatalog = StubPerkLookup(
            listOf(
                stubPerk("SWORD_BLEEDER", sword, 50),
                stubPerk("SWORD_SHARPEN_EDGE", sword, 50),
                stubPerk("SWORD_RIPOSTE", sword, 100),
                stubPerk("SWORD_PARRY", sword, 100),
            ),
        )
        val tool = GetStatusTool(
            agents = StubRegistry(agent),
            world = StubQuery(active = node, body = body),
            engine = tickClock,
            activity = activity,
            skillsRegistry = StubSkillsRegistry(snapshot),
            skillCatalog = stubSkillLookupForPerks(),
            perksRegistry = StubPerksRegistry(),
            perkCatalog = perkCatalog,
        )

        val skills = tool.invoke(toolContext).skills

        assertEquals(
            listOf(50, 100),
            skills.pendingPerkChoices.map { it.milestone },
        )
        assertEquals(emptyList(), skills.chosenPerks)
    }

    @Test
    fun `pendingPerkChoices excludes milestones the slotted skill has not yet reached`() {
        val sword = SkillId("SWORD")
        val snapshot = AgentSkillsSnapshot(
            perSkill = mapOf(
                sword to AgentSkillState(sword, xp = 30, level = 40, slotIndex = 0, recommendCount = 1),
            ),
            slotCount = 8,
            slotsFilled = 1,
        )
        val perkCatalog = StubPerkLookup(listOf(stubPerk("SWORD_BLEEDER", sword, 50)))
        val tool = GetStatusTool(
            agents = StubRegistry(agent),
            world = StubQuery(active = node, body = body),
            engine = tickClock,
            activity = activity,
            skillsRegistry = StubSkillsRegistry(snapshot),
            skillCatalog = stubSkillLookupForPerks(),
            perksRegistry = StubPerksRegistry(),
            perkCatalog = perkCatalog,
        )

        val skills = tool.invoke(toolContext).skills

        assertEquals(emptyList(), skills.pendingPerkChoices)
    }

    @Test
    fun `pendingPerkChoices ignores unslotted skills even when level meets milestone`() {
        val sword = SkillId("SWORD")
        val snapshot = AgentSkillsSnapshot(
            perSkill = mapOf(
                sword to AgentSkillState(sword, xp = 100, level = 60, slotIndex = null, recommendCount = 1),
            ),
            slotCount = 8,
            slotsFilled = 0,
        )
        val perkCatalog = StubPerkLookup(listOf(stubPerk("SWORD_BLEEDER", sword, 50)))
        val tool = GetStatusTool(
            agents = StubRegistry(agent),
            world = StubQuery(active = node, body = body),
            engine = tickClock,
            activity = activity,
            skillsRegistry = StubSkillsRegistry(snapshot),
            skillCatalog = stubSkillLookupForPerks(),
            perksRegistry = StubPerksRegistry(),
            perkCatalog = perkCatalog,
        )

        val skills = tool.invoke(toolContext).skills

        assertEquals(emptyList(), skills.pendingPerkChoices)
    }

    private fun stubSkillLookupForPerks() = StubSkillLookup(
        listOf(
            Skill(SkillId("SWORD"), "Sword", "blade", SkillCategory.COMBAT),
            Skill(SkillId("BOW"), "Bow", "ranged", SkillCategory.COMBAT),
        ),
    )

    private fun stubPerk(id: String, skill: SkillId, milestone: Int) = Perk(
        id = PerkId(id),
        skill = skill,
        milestoneLevel = milestone,
        displayName = id,
        description = id,
        effect = PerkEffect.PassiveAura(auraKey = id, magnitude = 1.0),
    )

    @Test
    fun `skills view drops skills missing from the catalog`() {
        val ghost = SkillId("REMOVED_SKILL")
        val snapshot = AgentSkillsSnapshot(
            perSkill = mapOf(
                ghost to AgentSkillState(ghost, xp = 12, level = 1, slotIndex = 0, recommendCount = 1),
            ),
            slotCount = 4,
            slotsFilled = 1,
        )
        val tool = GetStatusTool(
            agents = StubRegistry(agent),
            world = StubQuery(active = node, body = body),
            engine = tickClock,
            activity = activity,
            skillsRegistry = StubSkillsRegistry(snapshot),
            skillCatalog = skillCatalog,
            perksRegistry = StubPerksRegistry(),
            perkCatalog = StubPerkLookup(),
        )

        val skills = tool.invoke(toolContext).skills

        assertEquals(4, skills.slots.size)
        skills.slots.forEach { assertNull(it.skill) }
        assertEquals(emptyList(), skills.unslotted)
    }

    private class StubRegistry(private val agent: Agent?) : AgentRegistry {
        override fun find(id: AgentId): Agent? = agent
        override fun listForOwner(owner: PlayerId): List<Agent> = listOfNotNull(agent)
    }

    private class StubQuery(
        private val active: NodeId? = null,
        private val lastLocation: NodeId? = null,
        private val body: BodyView? = null,
    ) : WorldQueryGateway {
        override fun locationOf(agent: AgentId): NodeId? = lastLocation
        override fun activePositionOf(agent: AgentId): NodeId? = active
        override fun node(id: NodeId): Node? = null
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = body
        override fun inventoryOf(agent: AgentId): dev.gvart.genesara.world.InventoryView =
            dev.gvart.genesara.world.InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): dev.gvart.genesara.world.NodeResources =
            dev.gvart.genesara.world.NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<dev.gvart.genesara.world.GroundItemView> = emptyList()
    }

    private class StubSkillsRegistry(private val snap: AgentSkillsSnapshot) : AgentSkillsRegistry {
        override fun snapshot(agent: AgentId) = snap
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int) = AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private class StubSkillLookup(private val skills: List<Skill>) : SkillLookup {
        private val byId = skills.associateBy { it.id }
        override fun byId(id: SkillId): Skill? = byId[id]
        override fun all(): List<Skill> = skills
    }

    private class StubPerksRegistry(private val picks: List<AgentPerk> = emptyList()) : AgentPerksRegistry {
        override fun snapshot(agent: AgentId) = AgentPerksSnapshot(picks)
        override fun recordChoice(agent: AgentId, perk: PerkId, tick: Long): RecordPerkResult =
            RecordPerkResult.Recorded
    }

    private class StubPerkLookup(private val perks: List<Perk> = emptyList()) : PerkLookup {
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

    private class StubTickClock(private val currentTick: Long) : TickClock {
        override fun currentTick(): Long = currentTick
    }

    private class MutableTestClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }
}
