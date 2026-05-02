package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.world.AgentSafeNodeGateway
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val wood = ItemId("WOOD")
    private val stone = ItemId("STONE")
    private val carpentry = SkillId("CARPENTRY")

    private val region = Region(
        id = regionId,
        worldId = WorldId(1L),
        sphereIndex = 0,
        biome = Biome.PLAINS,
        climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )

    private val campfireDef = BuildingProperties(
        requiredSkill = "CARPENTRY",
        totalSteps = 5,
        staminaPerStep = 8,
        hp = 30,
        categoryHint = BuildingCategoryHint.COOKING,
        totalMaterials = mapOf("WOOD" to 10, "STONE" to 5),
    )
    private val shelterDef = BuildingProperties(
        requiredSkill = "CARPENTRY",
        totalSteps = 2,
        staminaPerStep = 8,
        hp = 80,
        categoryHint = BuildingCategoryHint.RESIDENCE,
        totalMaterials = mapOf("WOOD" to 4),
    )

    private val catalog = BuildingsCatalog(
        BuildingDefinitionProperties(catalog = mapOf("CAMPFIRE" to campfireDef, "SHELTER" to shelterDef)),
    )

    private fun stateWith(
        positioned: Boolean = true,
        stamina: Int = 50,
        inventory: Map<ItemId, Int> = mapOf(wood to 100, stone to 100),
    ): WorldState {
        var inv = AgentInventory()
        for ((item, qty) in inventory) inv = inv.add(item, qty)
        return WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet())),
            positions = if (positioned) mapOf(agent to nodeId) else emptyMap(),
            bodies = mapOf(agent to AgentBody(hp = 50, maxHp = 50, stamina = stamina, maxStamina = 50, mana = 0, maxMana = 0)),
            inventories = mapOf(agent to inv),
        )
    }

    @Test
    fun `first call inserts the building UNDER_CONSTRUCTION at progress 1 and emits BuildingPlaced`() {
        val state = stateWith()
        val store = StubBuildingsStore()
        val safeNodes = StubSafeNodes()
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()

        val (next, event) = assertNotNull(
            reduceBuild(
                state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
                catalog, skills, store, safeNodes, publisher, tick = 7,
            ).getOrNull(),
        )

        val placed = assertIs<WorldEvent.BuildingPlaced>(event)
        assertEquals(BuildingStatus.UNDER_CONSTRUCTION, placed.building.status)
        assertEquals(1, placed.building.progressSteps)
        assertEquals(5, placed.building.totalSteps)
        assertEquals(7L, placed.building.builtAtTick)
        assertEquals(7L, placed.building.lastProgressTick)
        assertEquals(30, placed.building.hpCurrent)
        assertEquals(1, store.inserted.size)
        // step 1 of CAMPFIRE: floor(10/5)=2 wood, floor(5/5)=1 stone
        assertEquals(98, next.inventoryOf(agent).quantityOf(wood))
        assertEquals(99, next.inventoryOf(agent).quantityOf(stone))
        assertEquals(42, next.bodyOf(agent)!!.stamina)
    }

    @Test
    fun `subsequent call advances an existing in-progress instance by one step and emits BuildingProgressed`() {
        val state = stateWith()
        val existing = sampleBuilding(progress = 2)
        val store = StubBuildingsStore(rows = mutableListOf(existing))
        val safeNodes = StubSafeNodes()
        val publisher = RecordingPublisher()

        val (next, event) = assertNotNull(
            reduceBuild(
                state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
                catalog, StubSkillsRegistry(), store, safeNodes, publisher, tick = 9,
            ).getOrNull(),
        )

        val progressed = assertIs<WorldEvent.BuildingProgressed>(event)
        assertEquals(3, progressed.building.progressSteps)
        assertEquals(BuildingStatus.UNDER_CONSTRUCTION, progressed.building.status)
        assertEquals(9L, progressed.building.lastProgressTick)
        assertEquals(1, store.advanced.size)
        assertEquals(0, store.completed.size)
        assertEquals(98, next.inventoryOf(agent).quantityOf(wood))
        assertEquals(99, next.inventoryOf(agent).quantityOf(stone))
    }

    @Test
    fun `terminal step flips status to ACTIVE and emits BuildingCompleted`() {
        val state = stateWith()
        val nearlyDone = sampleBuilding(progress = 4)
        val store = StubBuildingsStore(rows = mutableListOf(nearlyDone))
        val publisher = RecordingPublisher()

        val (_, event) = assertNotNull(
            reduceBuild(
                state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
                catalog, StubSkillsRegistry(), store, StubSafeNodes(), publisher, tick = 11,
            ).getOrNull(),
        )

        val completed = assertIs<WorldEvent.BuildingCompleted>(event)
        assertEquals(BuildingStatus.ACTIVE, completed.building.status)
        assertEquals(5, completed.building.progressSteps)
        assertEquals(0, store.advanced.size)
        assertEquals(1, store.completed.size)
    }

    @Test
    fun `final step charges the leftover material remainder, not floor(total over steps)`() {
        val customCatalog = BuildingsCatalog(
            BuildingDefinitionProperties(
                catalog = mapOf(
                    "CAMPFIRE" to campfireDef.copy(totalSteps = 3, totalMaterials = mapOf("WOOD" to 10)),
                ),
            ),
        )
        val state = stateWith(inventory = mapOf(wood to 10))
        val nearlyDone = sampleBuilding(progress = 2, totalSteps = 3)
        val store = StubBuildingsStore(rows = mutableListOf(nearlyDone))

        val (next, _) = assertNotNull(
            reduceBuild(
                state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
                customCatalog, StubSkillsRegistry(), store, StubSafeNodes(), RecordingPublisher(), tick = 1,
            ).getOrNull(),
        )

        assertEquals(6, next.inventoryOf(agent).quantityOf(wood))
    }

    @Test
    fun `SHELTER completion sets the builder's safe node to the shelter's node`() {
        val state = stateWith(inventory = mapOf(wood to 10))
        val nearlyDone = sampleBuilding(type = BuildingType.SHELTER, progress = 1, totalSteps = 2, hp = 80)
        val store = StubBuildingsStore(rows = mutableListOf(nearlyDone))
        val safeNodes = StubSafeNodes()

        reduceBuild(
            state, WorldCommand.BuildStructure(agent, BuildingType.SHELTER),
            catalog, StubSkillsRegistry(), store, safeNodes, RecordingPublisher(), tick = 11,
        )

        assertEquals(nodeId, safeNodes.set[agent])
    }

    @Test
    fun `non-SHELTER completion leaves safe node untouched`() {
        val state = stateWith()
        val nearlyDone = sampleBuilding(progress = 4)
        val store = StubBuildingsStore(rows = mutableListOf(nearlyDone))
        val safeNodes = StubSafeNodes()

        reduceBuild(
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, StubSkillsRegistry(), store, safeNodes, RecordingPublisher(), tick = 11,
        )

        assertEquals(emptyMap(), safeNodes.set)
    }

    @Test
    fun `rejects when agent is not in the world`() {
        val state = stateWith(positioned = false)
        val result = reduceBuild(
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, StubSkillsRegistry(), StubBuildingsStore(), StubSafeNodes(), RecordingPublisher(), tick = 1,
        )

        assertEquals(WorldRejection.NotInWorld(agent), result.leftOrNull())
    }

    @Test
    fun `rejects when stamina is below the per-step cost`() {
        val state = stateWith(stamina = 3)
        val result = reduceBuild(
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, StubSkillsRegistry(), StubBuildingsStore(), StubSafeNodes(), RecordingPublisher(), tick = 1,
        )

        assertEquals(WorldRejection.NotEnoughStamina(agent, required = 8, available = 3), result.leftOrNull())
    }

    @Test
    fun `rejects with InsufficientMaterials and reports the missing item`() {
        // Step 1 of CAMPFIRE needs 2 wood + 1 stone. Agent has wood but no stone, so the
        // single-missing case pins exactly which material the rejection names.
        val state = stateWith(inventory = mapOf(wood to 100))
        val store = StubBuildingsStore()

        val result = reduceBuild(
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, StubSkillsRegistry(), store, StubSafeNodes(), RecordingPublisher(), tick = 1,
        )

        val rejection = assertIs<WorldRejection.InsufficientMaterials>(result.leftOrNull())
        assertEquals(BuildingType.CAMPFIRE, rejection.type)
        assertEquals(stone, rejection.item)
        assertEquals(1, rejection.required)
        assertEquals(0, rejection.available)
        assertTrue(store.inserted.isEmpty(), "no row inserted on a rejected step")
    }

    @Test
    fun `agent B starting a build of the same type does NOT advance agent A's in-progress row`() {
        val agentB = AgentId(UUID.randomUUID())
        val state = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet())),
            positions = mapOf(agent to nodeId, agentB to nodeId),
            bodies = mapOf(
                agent to AgentBody(50, 50, 50, 50, 0, 0),
                agentB to AgentBody(50, 50, 50, 50, 0, 0),
            ),
            inventories = mapOf(
                agentB to AgentInventory().add(wood, 100).add(stone, 100),
            ),
        )
        val agentARow = sampleBuilding(progress = 2)
        val store = StubBuildingsStore(rows = mutableListOf(agentARow))

        val (_, event) = assertNotNull(
            reduceBuild(
                state, WorldCommand.BuildStructure(agentB, BuildingType.CAMPFIRE),
                catalog, StubSkillsRegistry(), store, StubSafeNodes(), RecordingPublisher(), tick = 5,
            ).getOrNull(),
        )

        assertIs<WorldEvent.BuildingPlaced>(event)
        assertEquals(2, store.rows.size)
        assertEquals(2, store.rows.first { it.builtByAgentId == agent }.progressSteps)
    }

    @Test
    fun `after completion a follow-up build call starts a fresh UNDER_CONSTRUCTION instance`() {
        val state = stateWith()
        val finished = sampleBuilding(progress = 5, totalSteps = 5)
        val store = StubBuildingsStore(rows = mutableListOf(finished))

        val (_, event) = assertNotNull(
            reduceBuild(
                state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
                catalog, StubSkillsRegistry(), store, StubSafeNodes(), RecordingPublisher(), tick = 12,
            ).getOrNull(),
        )

        assertIs<WorldEvent.BuildingPlaced>(event)
        assertEquals(2, store.rows.size)
        assertEquals(0, store.completed.size)
    }

    @Test
    fun `rejects with BuildingSkillTooLow when the def gates on a level the agent has not reached`() {
        // Forward-compat path: v1 buildings ship with requiredSkillLevel=0 (no gate).
        // Future tiers will set >0; assert the reducer enforces the threshold via the
        // agent's snapshot.
        val gatedCatalog = BuildingsCatalog(
            BuildingDefinitionProperties(
                catalog = mapOf(
                    "CAMPFIRE" to campfireDef.copy(requiredSkillLevel = 5),
                ),
            ),
        )
        val state = stateWith()
        val skills = StubSkillsRegistry().apply { slot(carpentry, level = 2) }

        val result = reduceBuild(
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            gatedCatalog, skills, StubBuildingsStore(), StubSafeNodes(), RecordingPublisher(), tick = 1,
        )

        val rejection = assertIs<WorldRejection.BuildingSkillTooLow>(result.leftOrNull())
        assertEquals(carpentry, rejection.skill)
        assertEquals(5, rejection.required)
        assertEquals(2, rejection.current)
    }

    @Test
    fun `accepts a build when the agent meets the gated requiredSkillLevel`() {
        val gatedCatalog = BuildingsCatalog(
            BuildingDefinitionProperties(
                catalog = mapOf(
                    "CAMPFIRE" to campfireDef.copy(requiredSkillLevel = 2),
                ),
            ),
        )
        val state = stateWith()
        val skills = StubSkillsRegistry().apply { slot(carpentry, level = 2) }

        val result = reduceBuild(
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            gatedCatalog, skills, StubBuildingsStore(), StubSafeNodes(), RecordingPublisher(), tick = 1,
        )

        assertNotNull(result.getOrNull())
    }

    @Test
    fun `walking the full step ladder accumulates per-step stamina cost and ends ACTIVE`() {
        var state = stateWith(inventory = mapOf(wood to 100, stone to 100))
        val store = StubBuildingsStore()
        val initialStamina = state.bodyOf(agent)!!.stamina
        var lastEvent: WorldEvent? = null

        repeat(5) { i ->
            val (next, event) = assertNotNull(
                reduceBuild(
                    state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
                    catalog, StubSkillsRegistry(), store, StubSafeNodes(), RecordingPublisher(), tick = (10 + i).toLong(),
                ).getOrNull(),
            )
            state = next
            lastEvent = event
        }

        assertIs<WorldEvent.BuildingCompleted>(lastEvent)
        assertEquals(initialStamina - 5 * 8, state.bodyOf(agent)!!.stamina)
        assertEquals(1, store.rows.size)
        assertEquals(BuildingStatus.ACTIVE, store.rows.single().status)
        assertEquals(5, store.rows.single().progressSteps)
        assertEquals(90, state.inventoryOf(agent).quantityOf(wood))
        assertEquals(95, state.inventoryOf(agent).quantityOf(stone))
    }

    @Test
    fun `slotted skill receives one XP per build step`() {
        val state = stateWith()
        val skills = StubSkillsRegistry().apply { slot(carpentry) }

        reduceBuild(
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, StubBuildingsStore(), StubSafeNodes(), RecordingPublisher(), tick = 1,
        )

        assertEquals(listOf(carpentry to 1), skills.xpAddCalls)
    }

    @Test
    fun `unslotted skill triggers a SkillRecommended event when maybeRecommend says yes`() {
        val state = stateWith()
        val skills = StubSkillsRegistry().apply { recommendOnNext[carpentry] = 1 }
        val publisher = RecordingPublisher()

        reduceBuild(
            state, WorldCommand.BuildStructure(agent, BuildingType.CAMPFIRE),
            catalog, skills, StubBuildingsStore(), StubSafeNodes(), publisher, tick = 5,
        )

        val rec = publisher.events.filterIsInstance<WorldEvent.SkillRecommended>().single()
        assertEquals(carpentry, rec.skill)
        assertEquals(1, rec.recommendCount)
    }

    private fun sampleBuilding(
        type: BuildingType = BuildingType.CAMPFIRE,
        progress: Int = 1,
        totalSteps: Int = 5,
        hp: Int = 30,
    ): Building = Building(
        instanceId = UUID.randomUUID(),
        nodeId = nodeId,
        type = type,
        status = if (progress == totalSteps) BuildingStatus.ACTIVE else BuildingStatus.UNDER_CONSTRUCTION,
        builtByAgentId = agent,
        builtAtTick = 1L,
        lastProgressTick = 1L,
        progressSteps = progress,
        totalSteps = totalSteps,
        hpCurrent = hp,
        hpMax = hp,
    )

    private inner class StubBuildingsStore(
        val rows: MutableList<Building> = mutableListOf(),
    ) : BuildingsStore {
        val inserted = mutableListOf<Building>()
        val advanced = mutableListOf<UUID>()
        val completed = mutableListOf<UUID>()

        override fun insert(building: Building) {
            inserted += building
            rows += building
        }
        override fun findById(id: UUID): Building? = rows.firstOrNull { it.instanceId == id }
        override fun findInProgress(node: NodeId, agent: AgentId, type: BuildingType): Building? =
            rows.firstOrNull {
                it.nodeId == node && it.builtByAgentId == agent && it.type == type &&
                    it.status == BuildingStatus.UNDER_CONSTRUCTION
            }
        override fun listAtNode(node: NodeId): List<Building> = rows.filter { it.nodeId == node }
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> =
            rows.filter { it.nodeId in nodes }.groupBy { it.nodeId }

        override fun advanceProgress(id: UUID, newProgress: Int, asOfTick: Long): Building? {
            val idx = rows.indexOfFirst { it.instanceId == id }.takeIf { it >= 0 } ?: return null
            advanced += id
            val updated = rows[idx].copy(progressSteps = newProgress, lastProgressTick = asOfTick)
            rows[idx] = updated
            return updated
        }

        override fun complete(id: UUID, asOfTick: Long): Building? {
            val idx = rows.indexOfFirst { it.instanceId == id }.takeIf { it >= 0 } ?: return null
            completed += id
            val original = rows[idx]
            val updated = original.copy(
                status = BuildingStatus.ACTIVE,
                progressSteps = original.totalSteps,
                lastProgressTick = asOfTick,
            )
            rows[idx] = updated
            return updated
        }
    }

    private class StubSafeNodes : AgentSafeNodeGateway {
        val set = mutableMapOf<AgentId, NodeId>()
        override fun set(agentId: AgentId, nodeId: NodeId, tick: Long) {
            set[agentId] = nodeId
        }
        override fun find(agentId: AgentId): NodeId? = set[agentId]
        override fun clear(agentId: AgentId) {
            set.remove(agentId)
        }
    }

    private class StubSkillsRegistry : AgentSkillsRegistry {
        private val slottedSkills = mutableSetOf<SkillId>()
        private val levels = mutableMapOf<SkillId, Int>()
        val xpAddCalls = mutableListOf<Pair<SkillId, Int>>()
        val recommendOnNext = mutableMapOf<SkillId, Int?>()
        var slotCount: Int = 8
        var slotsFilled: Int = 0

        fun slot(skill: SkillId, level: Int = 0) {
            slottedSkills += skill
            levels[skill] = level
            slotsFilled = slottedSkills.size
        }

        override fun snapshot(agent: AgentId): AgentSkillsSnapshot = AgentSkillsSnapshot(
            perSkill = slottedSkills.associateWith { skillId ->
                AgentSkillState(
                    skill = skillId,
                    xp = 0,
                    level = levels[skillId] ?: 0,
                    slotIndex = slottedSkills.indexOf(skillId),
                    recommendCount = 0,
                )
            },
            slotCount = slotCount,
            slotsFilled = slotsFilled,
        )

        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult {
            if (skill !in slottedSkills) return AddXpResult.Unslotted
            xpAddCalls += skill to delta
            return AddXpResult.Accrued(emptyList())
        }

        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? =
            if (skill in slottedSkills) null else recommendOnNext.remove(skill)

        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }
}

private fun <L, R> arrow.core.Either<L, R>.leftOrNull(): L? = (this as? arrow.core.Either.Left<L>)?.value
