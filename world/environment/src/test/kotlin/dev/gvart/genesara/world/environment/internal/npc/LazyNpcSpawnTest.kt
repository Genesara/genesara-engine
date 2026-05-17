package dev.gvart.genesara.world.environment.internal.npc

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AggressionProfile
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeClearedTimestampStore
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.balance.BiomeProperties
import dev.gvart.genesara.world.internal.balance.WorldDefinitionProperties
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LazyNpcSpawnTest {

    private val regionId = RegionId(1L)
    private val region = Region(
        id = regionId, worldId = WorldId(1L), sphereIndex = 0,
        biome = Biome.FOREST, climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
    )
    private val nodeId = NodeId(42L)
    private val node = Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet())
    private val agent = AgentId(UUID.randomUUID())

    private val wolfDef = NpcDef(
        type = NpcType("GRAY_WOLF"),
        displayName = "Gray Wolf",
        hpMax = 30,
        damage = 6,
        damageType = DamageType.PIERCE,
        range = 1,
        attackIntervalTicks = 4,
        defense = 1,
        dodgeChancePercent = 10,
        aggressionProfile = AggressionProfile.HOSTILE,
        territoryRadius = 0,
        spawnBiomes = setOf(Biome.FOREST),
        spawnWeight = 1,
    )

    private val baseState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeId to node),
        positions = emptyMap(),
        bodies = emptyMap(),
        inventories = emptyMap(),
    )

    @Test
    fun `seeds capacity NPCs on first visit when biome supports it`() {
        val spawn = LazyNpcSpawn(
            catalog = catalogWith(wolfDef),
            balance = balance(respawnTicks = 100),
            worldDef = worldDefWithCapacity(forest = 2),
            clearedStore = StubClearedStore(default = 0L),
        )

        val (after, events) = spawn.maybeSeed(baseState, nodeId, agent, tick = 1000L, rng = Random(0L))

        assertEquals(2, after.npcs.size)
        assertTrue(after.npcs.values.all { it.nodeId == nodeId && it.spawnNodeId == nodeId })
        assertTrue(after.npcs.values.all { it.hpCurrent == it.hpMax })
        assertEquals(2, events.filterIsInstance<EnvironmentEvent.NpcSpawned>().size)
    }

    @Test
    fun `does not reseed before respawn threshold elapses`() {
        val spawn = LazyNpcSpawn(
            catalog = catalogWith(wolfDef),
            balance = balance(respawnTicks = 500),
            worldDef = worldDefWithCapacity(forest = 2),
            clearedStore = StubClearedStore(default = 900L),
        )

        val (after, events) = spawn.maybeSeed(baseState, nodeId, agent, tick = 1000L, rng = Random(0L))

        assertEquals(0, after.npcs.size)
        assertEquals(0, events.size)
    }

    @Test
    fun `skips spawn when biome capacity is zero`() {
        val spawn = LazyNpcSpawn(
            catalog = catalogWith(wolfDef),
            balance = balance(respawnTicks = 0),
            worldDef = worldDefWithCapacity(forest = 0),
            clearedStore = StubClearedStore(default = 0L),
        )

        val (after, events) = spawn.maybeSeed(baseState, nodeId, agent, tick = 100L, rng = Random(0L))

        assertEquals(0, after.npcs.size)
        assertEquals(0, events.size)
    }

    @Test
    fun `skips spawn when an NPC already lives at the node`() {
        val existing = baseState.copy(
            npcs = mapOf(
                dev.gvart.genesara.world.NpcId(UUID.randomUUID()) to dev.gvart.genesara.world.Npc(
                    id = dev.gvart.genesara.world.NpcId(UUID.randomUUID()),
                    type = wolfDef.type,
                    nodeId = nodeId,
                    spawnNodeId = nodeId,
                    hpCurrent = 30,
                    hpMax = 30,
                    spawnedAtTick = 0L,
                    lastAttackTick = 0L,
                ),
            ),
        )
        val spawn = LazyNpcSpawn(
            catalog = catalogWith(wolfDef),
            balance = balance(respawnTicks = 0),
            worldDef = worldDefWithCapacity(forest = 4),
            clearedStore = StubClearedStore(default = 0L),
        )

        val (after, events) = spawn.maybeSeed(existing, nodeId, agent, tick = 100L, rng = Random(0L))

        assertEquals(1, after.npcs.size)
        assertEquals(0, events.size)
    }

    private fun catalogWith(vararg defs: NpcDef): NpcCatalog = object : NpcCatalog {
        private val byType = defs.associateBy { it.type }
        override fun byType(type: NpcType): NpcDef? = byType[type]
        override fun all(): Collection<NpcDef> = byType.values
        override fun byBiome(biome: Biome): List<NpcDef> = byType.values.filter { biome in it.spawnBiomes }
    }

    private fun worldDefWithCapacity(forest: Int): WorldDefinitionProperties =
        WorldDefinitionProperties(
            biomes = mapOf(Biome.FOREST to BiomeProperties(displayName = "Forest", nodeNpcCapacity = forest)),
        )

    private class StubClearedStore(val default: Long) : NodeClearedTimestampStore {
        override fun lastClearedTick(nodeId: NodeId): Long = default
        override fun setLastClearedTick(nodeId: NodeId, tick: Long) = Unit
    }

    private fun balance(respawnTicks: Long): BalanceLookup = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 1
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 0
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 0
        override fun drinkThirstRefill(): Int = 0
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun npcRespawnTicks(): Long = respawnTicks
    }
}
