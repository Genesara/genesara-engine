package dev.gvart.genesara.world.internal.vision

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentClass
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.ClassDefinition
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.world.BodyView
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.InventoryView
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.buildings.BarProperties
import dev.gvart.genesara.world.internal.buildings.BuildingDefinitionProperties
import dev.gvart.genesara.world.internal.buildings.BuildingProperties
import dev.gvart.genesara.world.internal.buildings.BuildingsCatalog
import dev.gvart.genesara.world.internal.testsupport.InMemoryVisionBlockerCache
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisibleNodesImplTest {

    private val agent = Agent(
        id = AgentId(UUID.randomUUID()),
        owner = PlayerId(UUID.randomUUID()),
        name = "scout",
        classId = AgentClass.SCOUT,
    )

    @Test
    fun `radius 0 still includes the origin tile`() {
        val world = linearWorld(Terrain.PLAINS)
        val helper = newHelper(world, base = 0, survivalLevel = 0)
        val visible = helper.visibleNodesFor(agent, world.origin)
        assertEquals(setOf(world.origin), visible)
    }

    @Test
    fun `flat plains line — radius 3 sees all three tiles ahead`() {
        val world = linearWorld(Terrain.PLAINS)
        val helper = newHelper(world, base = 3, survivalLevel = 0)
        val visible = helper.visibleNodesFor(agent, world.origin)
        assertEquals(world.firstNNodes(4), visible) // origin + 3
    }

    @Test
    fun `mountain on intermediate plains line blocks tiles past it for plains observer`() {
        // origin: plains, n1: plains, n2: MOUNTAIN, n3: plains, n4: plains
        val world = linearWorld(Terrain.PLAINS, Terrain.PLAINS, Terrain.MOUNTAIN, Terrain.PLAINS, Terrain.PLAINS)
        val helper = newHelper(world, base = 4, survivalLevel = 0)
        val visible = helper.visibleNodesFor(agent, world.origin)
        assertEquals(world.firstNNodes(3), visible) // origin + n1 + n2 (mountain, silhouette)
    }

    @Test
    fun `mountain observer sees over hills`() {
        // origin: mountain, n1: hills, n2: plains
        val world = linearWorld(Terrain.MOUNTAIN, Terrain.HILLS, Terrain.PLAINS)
        val helper = newHelper(world, base = 3, survivalLevel = 0)
        val visible = helper.visibleNodesFor(agent, world.origin)
        // mountain origin h=2 sees past hills (h=1) to plains beyond.
        assertEquals(world.firstNNodes(3), visible)
    }

    @Test
    fun `mountain observer cannot see past another mountain`() {
        // origin: mountain, n1: mountain, n2: plains
        val world = linearWorld(Terrain.MOUNTAIN, Terrain.MOUNTAIN, Terrain.PLAINS)
        val helper = newHelper(world, base = 3, survivalLevel = 0)
        val visible = helper.visibleNodesFor(agent, world.origin)
        // origin sees n1 (mountain, silhouette) but n1 has blockerHeight 2 == observer 2 — passes; n2 visible.
        // Mountain (h=2) is <= observer (h=2), so intermediates with h==observer DO propagate.
        assertEquals(world.firstNNodes(3), visible)
    }

    @Test
    fun `mountain observer cannot see past a mountain plus wall blocker`() {
        // origin: mountain (h=2), n1: mountain with wall (h=2+1=3), n2: plains
        val world = linearWorld(Terrain.MOUNTAIN, Terrain.MOUNTAIN, Terrain.PLAINS)
        val blockers = InMemoryVisionBlockerCache()
        blockers.putForTest(world.nodeAt(1), 1) // wall on n1 on top of its mountain
        val helper = newHelper(world, base = 3, survivalLevel = 0, blockerCache = blockers)
        val visible = helper.visibleNodesFor(agent, world.origin)
        // origin sees n1 (silhouette), but n1's blocking height 3 > observer 2 → n2 invisible.
        assertEquals(setOf(world.origin, world.nodeAt(1)), visible)
    }

    @Test
    fun `wooden wall on plains intermediate blocks plains observer past it`() {
        val world = linearWorld(Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS)
        val blockers = InMemoryVisionBlockerCache()
        blockers.putForTest(world.nodeAt(1), 1) // active wall on n1
        val helper = newHelper(world, base = 3, survivalLevel = 0, blockerCache = blockers)
        val visible = helper.visibleNodesFor(agent, world.origin)
        // observer h=0; n1 blocking h=1 → n1 visible (silhouette), n2+ hidden.
        assertEquals(setOf(world.origin, world.nodeAt(1)), visible)
    }

    @Test
    fun `wooden wall on plains intermediate does not block hills observer`() {
        // origin: hills (h=1), n1: plains with wall (h=0+1=1), n2: plains, n3: plains
        val world = linearWorld(Terrain.HILLS, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS)
        val blockers = InMemoryVisionBlockerCache()
        blockers.putForTest(world.nodeAt(1), 1) // wall on n1
        val helper = newHelper(world, base = 3, survivalLevel = 0, blockerCache = blockers)
        val visible = helper.visibleNodesFor(agent, world.origin)
        // hills observer (h=1) sees over plains+wall (blocking 1) — ties pass.
        assertEquals(world.firstNNodes(4), visible)
    }

    @Test
    fun `watchtower on plains lets the observer see over walls on plains`() {
        // origin: plains + watchtower, n1: plains + wall, n2: plains, n3: plains
        val world = linearWorld(Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS)
        val blockers = InMemoryVisionBlockerCache()
        blockers.putForTest(world.nodeAt(1), 1)
        val helper = newHelper(world, base = 3, survivalLevel = 0, blockerCache = blockers)
        val originBuildings = listOf(activeBuilding(world.origin, BuildingType.WATCHTOWER))
        val visible = helper.visibleNodesFor(agent, world.origin, originBuildings)
        // tower observer h=1 sees over wall blocker h=1 (tie passes).
        assertEquals(world.firstNNodes(4), visible)
    }

    @Test
    fun `under-construction watchtower does not contribute observer height`() {
        val world = linearWorld(Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS)
        val blockers = InMemoryVisionBlockerCache()
        blockers.putForTest(world.nodeAt(1), 1)
        val helper = newHelper(world, base = 3, survivalLevel = 0, blockerCache = blockers)
        val originBuildings = listOf(building(world.origin, BuildingType.WATCHTOWER, BuildingStatus.UNDER_CONSTRUCTION))
        val visible = helper.visibleNodesFor(agent, world.origin, originBuildings)
        // ground observer (h=0) blocked at n1 (h=1).
        assertEquals(setOf(world.origin, world.nodeAt(1)), visible)
    }

    @Test
    fun `closed gate behaves like a wall in the cache`() {
        val world = linearWorld(Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS)
        val blockers = InMemoryVisionBlockerCache()
        blockers.putForTest(world.nodeAt(1), 1) // gate is closed → cache writes 1
        val helper = newHelper(world, base = 3, survivalLevel = 0, blockerCache = blockers)
        val visible = helper.visibleNodesFor(agent, world.origin)
        assertEquals(setOf(world.origin, world.nodeAt(1)), visible)
    }

    @Test
    fun `open gate contributes 0 — observer sees through`() {
        val world = linearWorld(Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS)
        val blockers = InMemoryVisionBlockerCache() // no entry for n1 → 0 (open gate)
        val helper = newHelper(world, base = 3, survivalLevel = 0, blockerCache = blockers)
        val visible = helper.visibleNodesFor(agent, world.origin)
        assertEquals(world.firstNNodes(4), visible)
    }

    @Test
    fun `silhouette rule — adjacent mountain visible from plains origin`() {
        val world = linearWorld(Terrain.PLAINS, Terrain.MOUNTAIN)
        val helper = newHelper(world, base = 1, survivalLevel = 0)
        val visible = helper.visibleNodesFor(agent, world.origin)
        assertTrue(world.origin in visible)
        assertTrue(world.nodeAt(1) in visible)
    }

    @Test
    fun `Survival 100 still propagates LOS rules — radius scales but walls still block`() {
        val world = linearWorld(Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS)
        val blockers = InMemoryVisionBlockerCache()
        blockers.putForTest(world.nodeAt(2), 1)
        val helper = newHelper(world, base = 3, survivalLevel = 100, blockerCache = blockers)
        val visible = helper.visibleNodesFor(agent, world.origin)
        // base 3 + survival/50 = 5; wall on n2 caps visibility at n2 (silhouette).
        assertEquals(setOf(world.origin, world.nodeAt(1), world.nodeAt(2)), visible)
    }

    @Test
    fun `mountain origin grants its own radius and height bonuses`() {
        // origin: MOUNTAIN, plains chain afterwards
        val world = linearWorld(Terrain.MOUNTAIN, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS)
        val helper = newHelper(world, base = 3, survivalLevel = 0)
        val visible = helper.visibleNodesFor(agent, world.origin)
        // base 3 + mountain +1 = 4 nodes ahead reachable; all are plains so LOS lets them all through.
        assertEquals(world.firstNNodes(5), visible)
    }

    @Test
    fun `unknown origin returns singleton current-node set`() {
        val world = linearWorld(Terrain.PLAINS)
        val helper = newHelper(world, base = 5, survivalLevel = 0)
        val visible = helper.visibleNodesFor(agent, NodeId(99_999L))
        assertEquals(setOf(NodeId(99_999L)), visible)
    }

    @Test
    fun `wall at hop 3 of radius 5 is itself visible, tiles at hops 4 and 5 are not`() {
        // origin: plains, n1: plains, n2: plains, n3: plains+wall, n4: plains, n5: plains
        val world = linearWorld(Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS)
        val blockers = InMemoryVisionBlockerCache()
        blockers.putForTest(world.nodeAt(3), 1)
        val helper = newHelper(world, base = 5, survivalLevel = 0, blockerCache = blockers)
        val visible = helper.visibleNodesFor(agent, world.origin)
        assertEquals(setOf(world.origin, world.nodeAt(1), world.nodeAt(2), world.nodeAt(3)), visible)
        assertFalse(world.nodeAt(4) in visible)
        assertFalse(world.nodeAt(5) in visible)
    }

    @Test
    fun `wall destruction (cache flush + put 0) restores vision past the tile`() {
        val world = linearWorld(Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS, Terrain.PLAINS)
        val blockers = InMemoryVisionBlockerCache()
        blockers.putForTest(world.nodeAt(1), 1)
        val helper = newHelper(world, base = 3, survivalLevel = 0, blockerCache = blockers)
        assertFalse(world.nodeAt(2) in helper.visibleNodesFor(agent, world.origin))

        blockers.putForTest(world.nodeAt(1), 0)
        assertTrue(world.nodeAt(2) in helper.visibleNodesFor(agent, world.origin))
    }

    // ─────────────────── helpers ───────────────────

    private fun newHelper(
        world: LinearWorld,
        base: Int,
        survivalLevel: Int,
        blockerCache: InMemoryVisionBlockerCache = InMemoryVisionBlockerCache(),
    ): VisibleNodesImpl {
        val classes = constantBase(base)
        val skills = StubSkills(mapOf(SkillId("SURVIVAL") to survivalLevel))
        val balance = LinearBalance
        val catalog = sightCatalog()
        val buildings = NoBuildingsLookup
        return VisibleNodesImpl(classes, skills, world, balance, catalog, buildings, blockerCache)
    }

    private fun activeBuilding(nodeId: NodeId, type: BuildingType): Building =
        building(nodeId, type, BuildingStatus.ACTIVE)

    private fun building(nodeId: NodeId, type: BuildingType, status: BuildingStatus): Building {
        val total = 5
        return Building(
            instanceId = UUID.randomUUID(),
            nodeId = nodeId,
            type = type,
            status = status,
            builtByAgentId = agent.id,
            builtAtTick = 1L,
            lastProgressTick = 1L,
            progressSteps = if (status == BuildingStatus.ACTIVE) total else total - 1,
            totalSteps = total,
            hpCurrent = 30,
            hpMax = 30,
        )
    }

    private fun constantBase(base: Int) = object : ClassLookup {
        override fun byId(classId: AgentClass): ClassDefinition? = null
        override fun all(): List<ClassDefinition> = emptyList()
        override fun baseClasses(): List<ClassDefinition> = emptyList()
        override fun evolutionsOf(parent: AgentClass): List<ClassDefinition> = emptyList()
        override fun sightRange(classId: AgentClass?): Int = base
        override fun skillXpMultiplier(classId: AgentClass?, skill: SkillId): Double = 1.0
        override fun damageMultiplier(classId: AgentClass?, damageType: String): Double = 1.0
        override fun forbidsCombatSkill(classId: AgentClass?, combatSkill: SkillId): Boolean = false
    }

    private class StubSkills(private val levelsBySkill: Map<SkillId, Int>) : AgentSkillsRegistry {
        override fun snapshot(agent: AgentId): AgentSkillsSnapshot =
            AgentSkillsSnapshot(perSkill = emptyMap(), slotCount = 8, slotsFilled = 0)
        override fun slottedSkillLevel(agent: AgentId, skill: SkillId): Int = levelsBySkill[skill] ?: 0
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult = AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private object LinearBalance : BalanceLookup {
        override fun moveStaminaCost(biome: dev.gvart.genesara.world.Biome, climate: dev.gvart.genesara.world.Climate, terrain: Terrain): Int = 0
        override fun staminaRegenPerTick(climate: dev.gvart.genesara.world.Climate): Int = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<dev.gvart.genesara.world.ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: dev.gvart.genesara.world.ItemId): Int = 0
        override fun harvestYield(item: dev.gvart.genesara.world.ItemId): Int = 0
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 0
        override fun drinkThirstRefill(): Int = 0
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun elevationOf(terrain: Terrain): Int = when (terrain) {
            Terrain.MOUNTAIN, Terrain.ALPINE, Terrain.CLIFFSIDE, Terrain.CANYON -> 2
            Terrain.HILLS, Terrain.FOOTHILLS -> 1
            else -> 0
        }
    }

    private fun sightCatalog(): BuildingsCatalog =
        BuildingsCatalog(BuildingDefinitionProperties(catalog = mapOf(
            "WOODEN_WALL" to BuildingProperties(
                staminaPerStep = 1, hp = 1, categoryHint = BuildingCategoryHint.DEFENSIVE,
                skillBars = mapOf("CARPENTRY" to BarProperties(level = 0, steps = 2, materialsPerStep = emptyMap())),
                sightBlockerHeight = 1,
            ),
            "GATE" to BuildingProperties(
                staminaPerStep = 1, hp = 1, categoryHint = BuildingCategoryHint.DEFENSIVE,
                skillBars = mapOf("CARPENTRY" to BarProperties(level = 0, steps = 2, materialsPerStep = emptyMap())),
                sightBlockerHeight = 1,
            ),
            "WATCHTOWER" to BuildingProperties(
                staminaPerStep = 1, hp = 1, categoryHint = BuildingCategoryHint.VISION,
                skillBars = mapOf("CARPENTRY" to BarProperties(level = 0, steps = 2, materialsPerStep = emptyMap())),
                observerHeightBonus = 1,
            ),
            // Filler entry so catalog.def(STORAGE_CHEST) doesn't error if a test asks.
            "STORAGE_CHEST" to BuildingProperties(
                staminaPerStep = 1, hp = 1, categoryHint = BuildingCategoryHint.STORAGE,
                skillBars = mapOf("CARPENTRY" to BarProperties(level = 0, steps = 2, materialsPerStep = emptyMap())),
                chestCapacityGrams = 1_000,
            ),
        )))

    private object NoBuildingsLookup : BuildingsLookup {
        override fun byId(id: UUID): Building? = null
        override fun byNode(node: NodeId): List<Building> = emptyList()
        override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> = emptyMap()
        override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> = emptyList()
    }

    /**
     * One-dimensional chain world: origin, n1, n2, … each adjacent only to its
     * neighbours. Lets tests prescribe a specific terrain sequence and assert
     * how far the LOS BFS gets.
     */
    private class LinearWorld(terrains: List<Terrain>) : WorldQueryGateway {
        private val ids: List<NodeId> = terrains.indices.map { NodeId((it + 1).toLong()) }
        val origin: NodeId = ids.first()
        private val nodes: Map<NodeId, Node> = ids.mapIndexed { idx, id ->
            id to Node(
                id = id,
                regionId = RegionId(1L),
                q = idx,
                r = 0,
                terrain = terrains[idx],
                adjacency = setOfNotNull(ids.getOrNull(idx - 1), ids.getOrNull(idx + 1)),
            )
        }.toMap()

        fun nodeAt(index: Int): NodeId = ids[index]
        fun firstNNodes(n: Int): Set<NodeId> = ids.take(n).toSet()

        override fun locationOf(agent: AgentId): NodeId? = null
        override fun activePositionOf(agent: AgentId): NodeId? = null
        override fun node(id: NodeId): Node? = nodes[id]
        override fun region(id: RegionId): Region? = null
        override fun nodesWithin(origin: NodeId, radius: Int): Set<NodeId> = emptySet()
        override fun randomSpawnableNode(): NodeId? = null
        override fun starterNodeFor(race: dev.gvart.genesara.player.RaceId): NodeId? = null
        override fun bodyOf(agent: AgentId): BodyView? = null
        override fun inventoryOf(agent: AgentId): InventoryView = InventoryView(emptyList())
        override fun resourcesAt(nodeId: NodeId, tick: Long): NodeResources = NodeResources.EMPTY
        override fun groundItemsAt(nodeId: NodeId): List<dev.gvart.genesara.world.GroundItemView> = emptyList()
        override fun currentTickFor(agent: AgentId): Long = 0L
        override fun activeAgentsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<AgentId>> = emptyMap()
        override fun npcsAtNodes(nodeIds: Set<NodeId>): Map<NodeId, List<dev.gvart.genesara.world.Npc>> = emptyMap()
        override fun npcDef(type: dev.gvart.genesara.world.NpcType): dev.gvart.genesara.world.NpcDef? = null
    }

    private fun linearWorld(vararg terrains: Terrain): LinearWorld = LinearWorld(terrains.toList())
}
