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
import dev.gvart.genesara.world.NpcZone
import dev.gvart.genesara.world.NpcZoneLookup
import dev.gvart.genesara.world.NpcZoneScope
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
    private val boarDef = NpcDef(
        type = NpcType("WILD_BOAR"),
        displayName = "Wild Boar",
        hpMax = 40,
        damage = 5,
        damageType = DamageType.BLUNT,
        range = 1,
        attackIntervalTicks = 4,
        defense = 1,
        dodgeChancePercent = 0,
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
            zoneLookup = StubZoneLookup(),
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
            zoneLookup = StubZoneLookup(),
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
            zoneLookup = StubZoneLookup(),
        )

        val (after, events) = spawn.maybeSeed(baseState, nodeId, agent, tick = 100L, rng = Random(0L))

        assertEquals(0, after.npcs.size)
        assertEquals(0, events.size)
    }

    @Test
    fun `nodeSelectedForSpawn admits each NodeId deterministically at probability 0_1`() {
        // The SplitMix64 constants in nodeSpawnFraction are public-API for the deterministic-spawn
        // promise: agents can map safe corridors only if (nodeId, probability) → outcome stays stable.
        val expectedAt01 = mapOf(
            NodeId(1L) to false,
            NodeId(2L) to true,
            NodeId(3L) to false,
            NodeId(10L) to false,
            NodeId(34L) to true,
            NodeId(42L) to false,
            NodeId(89L) to true,
            NodeId(100L) to false,
            NodeId(1_000L) to false,
            NodeId(99_999L) to false,
        )
        expectedAt01.forEach { (nodeId, expected) ->
            assertEquals(expected, nodeSelectedForSpawn(nodeId, 0.10), "nodeId=$nodeId at p=0.10")
        }
    }

    @Test
    fun `nodeSelectedForSpawn is monotonic in probability for a fixed NodeId`() {
        // NodeId(10) has fraction ~0.567: rejected at 0.05, accepted at 0.70.
        val nodeId = NodeId(10L)
        assertEquals(false, nodeSelectedForSpawn(nodeId, 0.05))
        assertEquals(false, nodeSelectedForSpawn(nodeId, 0.50))
        assertEquals(true, nodeSelectedForSpawn(nodeId, 0.70))
        assertEquals(true, nodeSelectedForSpawn(nodeId, 1.0))
    }

    @Test
    fun `node spawn decision is stable across repeated entries`() {
        val spawn = LazyNpcSpawn(
            catalog = catalogWith(wolfDef),
            balance = balance(respawnTicks = 0),
            worldDef = worldDefWithCapacity(forest = 2, nodeSpawnProbability = 0.10),
            clearedStore = StubClearedStore(default = 0L),
            zoneLookup = StubZoneLookup(),
        )

        for (i in 0 until 50) {
            val sampledNodeId = NodeId(7_000L + i.toLong())
            val sampledNode = Node(
                sampledNodeId, regionId, q = i, r = 0,
                terrain = Terrain.FOREST, adjacency = emptySet(),
            )
            val stateForNode = baseState.copy(nodes = mapOf(sampledNodeId to sampledNode))
            val firstHasNpc = spawn.maybeSeed(stateForNode, sampledNodeId, agent, tick = 1000L, rng = Random(0L))
                .first.npcs.isNotEmpty()
            val secondHasNpc = spawn.maybeSeed(stateForNode, sampledNodeId, agent, tick = 2000L, rng = Random(1L))
                .first.npcs.isNotEmpty()
            assertEquals(firstHasNpc, secondHasNpc, "node ${sampledNodeId.value} flipped between visits")
        }
    }

    @Test
    fun `pre-existing NPCs are preserved even when the node would now be skipped`() {
        val existingNpcId = dev.gvart.genesara.world.NpcId(UUID.randomUUID())
        val existingNpc = dev.gvart.genesara.world.Npc(
            id = existingNpcId,
            type = wolfDef.type,
            nodeId = nodeId,
            spawnNodeId = nodeId,
            hpCurrent = 30,
            hpMax = 30,
            spawnedAtTick = 0L,
            lastAttackTick = 0L,
        )
        val pre = baseState.copy(npcs = mapOf(existingNpcId to existingNpc))

        val spawn = LazyNpcSpawn(
            catalog = catalogWith(wolfDef),
            balance = balance(respawnTicks = 0),
            worldDef = worldDefWithCapacity(forest = 2, nodeSpawnProbability = 0.0),
            clearedStore = StubClearedStore(default = 0L),
            zoneLookup = StubZoneLookup(),
        )

        val (after, events) = spawn.maybeSeed(pre, nodeId, agent, tick = 1000L, rng = Random(0L))

        assertEquals(1, after.npcs.size)
        assertEquals(existingNpcId, after.npcs.keys.single())
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
            zoneLookup = StubZoneLookup(),
        )

        val (after, events) = spawn.maybeSeed(existing, nodeId, agent, tick = 100L, rng = Random(0L))

        assertEquals(1, after.npcs.size)
        assertEquals(0, events.size)
    }

    @Test
    fun `node zone weights override biome catalog selection`() {
        val zone = nodeZone(nodeId, weights = mapOf(wolfDef.type to 1), maxConcurrent = 3)
        val spawn = LazyNpcSpawn(
            catalog = catalogWith(wolfDef, boarDef),
            balance = balance(respawnTicks = 0),
            worldDef = worldDefWithCapacity(forest = 5),
            clearedStore = StubClearedStore(default = 0L),
            zoneLookup = StubZoneLookup(byNode = mapOf(nodeId to zone)),
        )

        val (after, _) = spawn.maybeSeed(baseState, nodeId, agent, tick = 1000L, rng = Random(0L))

        assertEquals(3, after.npcs.size)
        assertTrue(after.npcs.values.all { it.type == wolfDef.type })
    }

    @Test
    fun `node zone resolution beats region zone`() {
        val nodeZone = nodeZone(nodeId, weights = mapOf(wolfDef.type to 1), maxConcurrent = 1)
        val regionZone = regionZone(regionId, weights = mapOf(boarDef.type to 1), maxConcurrent = 5)
        val spawn = LazyNpcSpawn(
            catalog = catalogWith(wolfDef, boarDef),
            balance = balance(respawnTicks = 0),
            worldDef = worldDefWithCapacity(forest = 4),
            clearedStore = StubClearedStore(default = 0L),
            zoneLookup = StubZoneLookup(
                byNode = mapOf(this.nodeId to nodeZone),
                byRegion = mapOf(this.regionId to regionZone),
            ),
        )

        val (after, _) = spawn.maybeSeed(baseState, this.nodeId, agent, tick = 1000L, rng = Random(0L))

        assertEquals(1, after.npcs.size)
        assertEquals(wolfDef.type, after.npcs.values.single().type)
    }

    @Test
    fun `zone with respawn_ticks shorter than balance opens reseed window`() {
        val zone = nodeZone(
            nodeId = nodeId,
            weights = mapOf(wolfDef.type to 1),
            maxConcurrent = 1,
            respawnTicks = 50,
        )
        val spawn = LazyNpcSpawn(
            catalog = catalogWith(wolfDef),
            balance = balance(respawnTicks = 500L),
            worldDef = worldDefWithCapacity(forest = 1),
            clearedStore = StubClearedStore(default = 900L),
            zoneLookup = StubZoneLookup(byNode = mapOf(nodeId to zone)),
        )

        val (after, _) = spawn.maybeSeed(baseState, nodeId, agent, tick = 1000L, rng = Random(0L))

        assertEquals(1, after.npcs.size)
    }

    @Test
    fun `zone respects catalog spawnBiomes — wolves cannot spawn in a non-forest biome`() {
        val desertNode = Node(NodeId(999L), regionId, 0, 0, Terrain.DESERT, emptySet())
        val desertRegion = region.copy(biome = Biome.DESERT)
        val desertState = baseState.copy(
            regions = mapOf(regionId to desertRegion),
            nodes = mapOf(desertNode.id to desertNode),
        )
        val zone = nodeZone(desertNode.id, weights = mapOf(wolfDef.type to 1), maxConcurrent = 3)
        val spawn = LazyNpcSpawn(
            catalog = catalogWith(wolfDef),
            balance = balance(respawnTicks = 0),
            worldDef = WorldDefinitionProperties(
                biomes = mapOf(
                    Biome.DESERT to BiomeProperties(
                        displayName = "Desert",
                        nodeNpcCapacity = 0,
                        nodeSpawnProbability = 1.0,
                    ),
                ),
            ),
            clearedStore = StubClearedStore(default = 0L),
            zoneLookup = StubZoneLookup(byNode = mapOf(desertNode.id to zone)),
        )

        val (after, _) = spawn.maybeSeed(desertState, desertNode.id, agent, tick = 1000L, rng = Random(0L))

        assertEquals(0, after.npcs.size)
    }

    private fun nodeZone(
        nodeId: NodeId,
        weights: Map<NpcType, Int>,
        maxConcurrent: Int,
        respawnTicks: Int? = null,
    ) = NpcZone(
        zoneId = UUID.randomUUID(),
        worldId = WorldId(1L),
        scope = NpcZoneScope.NODE,
        regionId = null,
        nodeId = nodeId,
        weights = weights,
        maxConcurrent = maxConcurrent,
        respawnTicks = respawnTicks,
        active = true,
        createdBy = UUID.randomUUID(),
        createdAtTick = 0L,
    )

    private fun regionZone(
        regionId: RegionId,
        weights: Map<NpcType, Int>,
        maxConcurrent: Int,
        respawnTicks: Int? = null,
    ) = NpcZone(
        zoneId = UUID.randomUUID(),
        worldId = WorldId(1L),
        scope = NpcZoneScope.REGION,
        regionId = regionId,
        nodeId = null,
        weights = weights,
        maxConcurrent = maxConcurrent,
        respawnTicks = respawnTicks,
        active = true,
        createdBy = UUID.randomUUID(),
        createdAtTick = 0L,
    )

    private fun catalogWith(vararg defs: NpcDef): NpcCatalog = object : NpcCatalog {
        private val byType = defs.associateBy { it.type }
        override fun byType(type: NpcType): NpcDef? = byType[type]
        override fun all(): Collection<NpcDef> = byType.values
        override fun byBiome(biome: Biome): List<NpcDef> = byType.values.filter { biome in it.spawnBiomes }
    }

    private fun worldDefWithCapacity(
        forest: Int,
        nodeSpawnProbability: Double = 1.0,
    ): WorldDefinitionProperties =
        WorldDefinitionProperties(
            biomes = mapOf(
                Biome.FOREST to BiomeProperties(
                    displayName = "Forest",
                    nodeNpcCapacity = forest,
                    nodeSpawnProbability = nodeSpawnProbability,
                ),
            ),
        )

    private class StubClearedStore(val default: Long) : NodeClearedTimestampStore {
        override fun lastClearedTick(nodeId: NodeId): Long = default
        override fun setLastClearedTick(nodeId: NodeId, tick: Long) = Unit
    }

    private class StubZoneLookup(
        private val byNode: Map<NodeId, NpcZone> = emptyMap(),
        private val byRegion: Map<RegionId, NpcZone> = emptyMap(),
    ) : NpcZoneLookup {
        override fun resolveFor(nodeId: NodeId, regionId: RegionId): NpcZone? =
            byNode[nodeId] ?: byRegion[regionId]
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
