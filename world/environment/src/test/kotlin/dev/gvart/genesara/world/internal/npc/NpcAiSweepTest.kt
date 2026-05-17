package dev.gvart.genesara.world.internal.npc

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.DeathPenaltyOutcome
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.AggressionProfile
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.events.CombatEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test

class NpcAiSweepTest {

    private val regionId = RegionId(1L)
    private val nodeAId = NodeId(1L)
    private val nodeBId = NodeId(2L)
    private val nodeCId = NodeId(3L)
    private val ranger = AgentId(UUID.randomUUID())
    private val npcId = NpcId(UUID.randomUUID())
    private val region = Region(
        id = regionId, worldId = WorldId(1L), sphereIndex = 0,
        biome = Biome.FOREST, climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
    )

    @Test
    fun `HOSTILE NPC with range=2 attacks an agent two hops away`() {
        val def = hostile(range = 2)
        // Chain A — B — C: NPC on A, agent on C (2 hops).
        val state = chainStateWithAgentAt(nodeCId, def)
        val sweep = NpcAiSweep(catalogFor(def), balance(), stubAgents(), stubDeathProcessor())

        val (_, events) = sweep.apply(state, tick = 10L, rng = Random(0L))

        val attack = events.filterIsInstance<CombatEvent.NpcAttackedAgent>().single()
        assertEquals(ranger, attack.target)
        assertEquals(nodeAId, attack.at)
    }

    @Test
    fun `HOSTILE NPC with range=2 also attacks an agent one hop away`() {
        val def = hostile(range = 2)
        val state = chainStateWithAgentAt(nodeBId, def)
        val sweep = NpcAiSweep(catalogFor(def), balance(), stubAgents(), stubDeathProcessor())

        val (_, events) = sweep.apply(state, tick = 10L, rng = Random(0L))

        assertNotNull(events.filterIsInstance<CombatEvent.NpcAttackedAgent>().singleOrNull())
    }

    @Test
    fun `HOSTILE NPC with range=1 cannot reach an agent two hops away`() {
        val def = hostile(range = 1)
        val state = chainStateWithAgentAt(nodeCId, def)
        val sweep = NpcAiSweep(catalogFor(def), balance(), stubAgents(), stubDeathProcessor())

        val (_, events) = sweep.apply(state, tick = 10L, rng = Random(0L))

        assertEquals(emptyList(), events.filterIsInstance<CombatEvent.NpcAttackedAgent>())
    }

    private fun chainStateWithAgentAt(agentNode: NodeId, def: NpcDef): WorldState {
        val a = Node(nodeAId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeBId))
        val b = Node(nodeBId, regionId, q = 1, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeAId, nodeCId))
        val c = Node(nodeCId, regionId, q = 2, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeBId))
        val npc = Npc(
            id = npcId, type = def.type, nodeId = nodeAId, spawnNodeId = nodeAId,
            hpCurrent = def.hpMax, hpMax = def.hpMax, spawnedAtTick = 0L, lastAttackTick = 0L,
        )
        return WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeAId to a, nodeBId to b, nodeCId to c),
            positions = mapOf(ranger to agentNode),
            bodies = mapOf(ranger to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0)),
            inventories = emptyMap(),
            npcs = mapOf(npcId to npc),
        )
    }

    private fun hostile(range: Int): NpcDef = NpcDef(
        type = NpcType("WOLF_AT_RANGE"),
        displayName = "Wolf",
        hpMax = 30,
        damage = 5,
        damageType = DamageType.PIERCE,
        range = range,
        attackIntervalTicks = 1,
        defense = 0,
        dodgeChancePercent = 0,
        aggressionProfile = AggressionProfile.HOSTILE,
        territoryRadius = 0,
        spawnBiomes = setOf(Biome.FOREST),
        spawnWeight = 1,
        fleeDistance = 1,
    )

    private fun catalogFor(vararg defs: NpcDef): NpcCatalog {
        val byType = defs.associateBy { it.type }
        return object : NpcCatalog {
            override fun byType(type: NpcType): NpcDef? = byType[type]
            override fun all(): Collection<NpcDef> = byType.values
            override fun byBiome(biome: Biome): List<NpcDef> = byType.values.filter { biome in it.spawnBiomes }
        }
    }

    private fun stubAgents(): AgentRegistry = object : AgentRegistry {
        override fun find(id: AgentId): Agent? =
            Agent(id = id, owner = PlayerId(UUID.randomUUID()), name = "test", attributes = AgentAttributes())
        override fun listForOwner(owner: PlayerId): List<Agent> = error("not used")
        override fun applyDeathPenalty(agentId: AgentId, xpLossOnDeath: Int): DeathPenaltyOutcome? = null
    }

    private fun stubDeathProcessor(): DeathProcessor = DeathProcessor(
        balance = balance(),
        agents = stubAgents(),
        equipment = stubEquipment(),
        groundItems = stubGround(),
    )

    private fun stubEquipment(): AgentItemInstancesStore = object : InMemoryAgentItemInstancesStore() {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> = emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = null
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = null
        override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? = null
        override fun delete(instanceId: UUID): Boolean = false
    }

    private fun stubGround(): GroundItemStore = object : GroundItemStore {
        override fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long) = Unit
        override fun atNode(node: NodeId): List<GroundItemView> = emptyList()
        override fun take(node: NodeId, dropId: UUID): GroundItemView? = null
    }

    private fun balance(): BalanceLookup = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 5
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun xpLossOnDeath(): Int = 0
        override fun killStreakWindowTicks(): Long = 1000L
        override fun dropChanceForKillCount(killCount: Int): Double = 0.0
    }
}
