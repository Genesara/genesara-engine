package dev.gvart.genesara.world.internal.gather

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NodeResources
import dev.gvart.genesara.world.NodeResourceView
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.resources.InitialResourceRow
import dev.gvart.genesara.world.internal.resources.NodeResourceCell
import dev.gvart.genesara.world.internal.resources.NodeResourceStore
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GatherReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val wood = ItemId("WOOD")
    private val stone = ItemId("STONE")
    private val berry = ItemId("BERRY")
    private val foraging = SkillId("FORAGING")
    private val lumberjacking = SkillId("LUMBERJACKING")

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

    private fun stateWith(
        terrain: Terrain = Terrain.FOREST,
        positioned: Boolean = true,
        stamina: Int = 30,
    ): WorldState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = terrain, adjacency = emptySet())),
        positions = if (positioned) mapOf(agent to nodeId) else emptyMap(),
        bodies = mapOf(agent to AgentBody(hp = 50, maxHp = 50, stamina = stamina, maxStamina = 50, mana = 0, maxMana = 0)),
        inventories = emptyMap(),
    )

    private val balance = balance(
        spawns = mapOf(Terrain.FOREST to listOf(rule(wood, 1.0, 50..200))),
        staminaCost = 5,
    )
    private val items = StubItemLookup(
        mapOf(
            wood to itemFor(wood, gatheringSkill = "LUMBERJACKING"),
            stone to itemFor(stone, gatheringSkill = "MINING"),
            berry to itemFor(berry, gatheringSkill = "FORAGING"),
        ),
    )

    private val agents: AgentRegistry = StubAgentRegistry(strength = 100)
    private val equipment: EquipmentInstanceStore = StubEquipmentStore()

    @Test
    fun `happy path adds yield to inventory, spends stamina, emits ResourceGathered`() {
        val state = stateWith()
        val command = WorldCommand.GatherResource(agent, wood)
        val store = StubResourceStore(initial = mapOf(wood to 100))
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()

        val result = reduceGather(
            state, command, balance, items, store, skills, agents, equipment, publisher, tick = 7,
        )

        val (next, event) = assertNotNull(result.getOrNull())
        assertEquals(1, next.inventoryOf(agent).quantityOf(wood))
        assertEquals(25, next.bodyOf(agent)!!.stamina)
        assertEquals(99, store.quantity(wood))
        val gathered = assertIs<WorldEvent.ResourceGathered>(event)
        assertEquals(agent, gathered.agent)
        assertEquals(nodeId, gathered.at)
        assertEquals(wood, gathered.item)
        assertEquals(1, gathered.quantity)
        assertEquals(7L, gathered.tick)
        assertEquals(command.commandId, gathered.causedBy)
    }

    @Test
    fun `rejects when agent is not in the world`() {
        val state = stateWith(positioned = false)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()

        val result = reduceGather(
            state, WorldCommand.GatherResource(agent, wood), balance, items,
            StubResourceStore(), skills, agents, equipment, publisher, tick = 1,
        )

        assertEquals(WorldRejection.NotInWorld(agent), result.leftOrNull())
    }

    @Test
    fun `rejects when item is not in the catalog`() {
        val state = stateWith()
        val unknown = ItemId("PHANTOM")
        val emptyCatalog = StubItemLookup(emptyMap())

        val result = reduceGather(
            state, WorldCommand.GatherResource(agent, unknown), balance, emptyCatalog,
            StubResourceStore(), StubSkillsRegistry(), agents, equipment, RecordingPublisher(), tick = 1,
        )

        assertEquals(WorldRejection.UnknownItem(unknown), result.leftOrNull())
    }

    @Test
    fun `rejects when this node has no row for the item — wrong place to look`() {
        // Use BERRY (FORAGING-skill) to exercise the cell-null path. STONE is now
        // gated by the WrongVerbForItem check that fires before cell lookup.
        val state = stateWith(terrain = Terrain.FOREST)
        val store = StubResourceStore(initial = mapOf(wood to 50))

        val result = reduceGather(
            state, WorldCommand.GatherResource(agent, berry), balance, items,
            store, StubSkillsRegistry(), agents, equipment, RecordingPublisher(), tick = 1,
        )

        assertEquals(
            WorldRejection.ResourceNotAvailableHere(agent, nodeId, berry),
            result.leftOrNull(),
        )
    }

    @Test
    fun `rejects with WrongVerbForItem when item's gathering-skill is MINING (e g STONE)`() {
        val state = stateWith()

        val result = reduceGather(
            state, WorldCommand.GatherResource(agent, stone), balance, items,
            // Pre-seed the cell so cell-not-null isn't what's catching this; the
            // verb gate must fire first.
            StubResourceStore(initial = mapOf(stone to 50)),
            StubSkillsRegistry(), agents, equipment, RecordingPublisher(), tick = 1,
        )

        assertEquals(
            WorldRejection.WrongVerbForItem(agent, stone, expectedVerb = "mine"),
            result.leftOrNull(),
        )
    }

    @Test
    fun `rejects with NodeResourceDepleted when row exists but quantity is zero`() {
        val state = stateWith(terrain = Terrain.FOREST)
        val store = StubResourceStore(initial = mapOf(wood to 0), initialMaxima = mapOf(wood to 100))

        val result = reduceGather(
            state, WorldCommand.GatherResource(agent, wood), balance, items,
            store, StubSkillsRegistry(), agents, equipment, RecordingPublisher(), tick = 1,
        )

        assertEquals(
            WorldRejection.NodeResourceDepleted(agent, nodeId, wood),
            result.leftOrNull(),
        )
    }

    @Test
    fun `rejects when stamina is below the gather cost`() {
        val state = stateWith(stamina = 3)
        val store = StubResourceStore(initial = mapOf(wood to 50))

        val result = reduceGather(
            state, WorldCommand.GatherResource(agent, wood), balance, items,
            store, StubSkillsRegistry(), agents, equipment, RecordingPublisher(), tick = 1,
        )

        assertEquals(
            WorldRejection.NotEnoughStamina(agent, required = 5, available = 3),
            result.leftOrNull(),
        )
    }

    @Test
    fun `accepts when stamina equals the gather cost exactly, leaving stamina at zero`() {
        val state = stateWith(stamina = 5)
        val store = StubResourceStore(initial = mapOf(wood to 50))

        val result = reduceGather(
            state, WorldCommand.GatherResource(agent, wood), balance, items,
            store, StubSkillsRegistry(), agents, equipment, RecordingPublisher(), tick = 1,
        )

        val (next, _) = assertNotNull(result.getOrNull())
        assertEquals(0, next.bodyOf(agent)!!.stamina)
        assertEquals(1, next.inventoryOf(agent).quantityOf(wood))
    }

    @Test
    fun `gather yield is clamped by the available cell quantity`() {
        val state = stateWith()
        val store = StubResourceStore(initial = mapOf(wood to 1), initialMaxima = mapOf(wood to 100))
        val highYield = balance(
            spawns = mapOf(Terrain.FOREST to listOf(rule(wood, 1.0, 50..200))),
            staminaCost = 5,
            yield = 5,
        )

        val result = reduceGather(
            state, WorldCommand.GatherResource(agent, wood), highYield, items,
            store, StubSkillsRegistry(), agents, equipment, RecordingPublisher(), tick = 1,
        )

        val (next, event) = assertNotNull(result.getOrNull())
        assertEquals(1, next.inventoryOf(agent).quantityOf(wood))
        assertEquals(0, store.quantity(wood))
        val gathered = assertIs<WorldEvent.ResourceGathered>(event)
        assertEquals(1, gathered.quantity)
    }

    // ─────────────────────── Carry-weight cap ───────────────────────

    @Test
    fun `rejects when adding the gathered yield would exceed the carry cap`() {
        val state = stateWith().copy(
            inventories = mapOf(
                agent to dev.gvart.genesara.world.internal.inventory.AgentInventory.EMPTY.add(wood, 5),
            ),
        )
        val tightBalance = balance(
            spawns = mapOf(Terrain.FOREST to listOf(rule(wood, 1.0, 50..200))),
            staminaCost = 5,
            carryGramsPerStrengthPoint = 100,
        )
        val skinnyAgents = StubAgentRegistry(strength = 1)
        val store = StubResourceStore(initial = mapOf(wood to 50))

        val result = reduceGather(
            state, WorldCommand.GatherResource(agent, wood), tightBalance, items, store,
            StubSkillsRegistry(), skinnyAgents, equipment, RecordingPublisher(), tick = 1,
        )

        assertEquals(
            WorldRejection.OverEncumbered(agent, requested = 600, capacity = 100),
            result.leftOrNull(),
        )
        assertEquals(50, store.quantity(wood))
    }

    @Test
    fun `accepts the gather that lands exactly at the carry cap`() {
        val tightBalance = balance(
            spawns = mapOf(Terrain.FOREST to listOf(rule(wood, 1.0, 50..200))),
            staminaCost = 5,
            carryGramsPerStrengthPoint = 100,
        )
        val skinnyAgents = StubAgentRegistry(strength = 1)
        val store = StubResourceStore(initial = mapOf(wood to 50))

        val result = reduceGather(
            state = stateWith(),
            WorldCommand.GatherResource(agent, wood), tightBalance, items, store,
            StubSkillsRegistry(), skinnyAgents, equipment, RecordingPublisher(), tick = 1,
        )

        val (next, _) = assertNotNull(result.getOrNull())
        assertEquals(1, next.inventoryOf(agent).quantityOf(wood))
    }

    @Test
    fun `equipped items count toward the carry cap`() {
        val tightBalance = balance(
            spawns = mapOf(Terrain.FOREST to listOf(rule(wood, 1.0, 50..200))),
            staminaCost = 5,
            carryGramsPerStrengthPoint = 100,
        )
        val skinnyAgents = StubAgentRegistry(strength = 1)
        val heavyHelmet = EquipmentInstance(
            instanceId = UUID.randomUUID(),
            agentId = agent,
            itemId = ItemId("HEAVY_HELMET"),
            rarity = Rarity.COMMON,
            durabilityCurrent = 100,
            durabilityMax = 100,
            creatorAgentId = null,
            createdAtTick = 0L,
            equippedInSlot = EquipSlot.HELMET,
        )
        val itemsWithHelmet = StubItemLookup(
            mapOf(
                wood to itemFor(wood, gatheringSkill = "LUMBERJACKING"),
                stone to itemFor(stone, gatheringSkill = "MINING"),
                berry to itemFor(berry, gatheringSkill = "FORAGING"),
                ItemId("HEAVY_HELMET") to Item(
                    id = ItemId("HEAVY_HELMET"),
                    displayName = "Heavy Helmet",
                    description = "",
                    category = ItemCategory.RESOURCE,
                    weightPerUnit = 200,
                    maxStack = 1,
                ),
            ),
        )
        val helmetEquipped = StubEquipmentStore(equipped = mapOf(EquipSlot.HELMET to heavyHelmet))
        val store = StubResourceStore(initial = mapOf(wood to 50))

        val result = reduceGather(
            state = stateWith(),
            WorldCommand.GatherResource(agent, wood), tightBalance, itemsWithHelmet, store,
            StubSkillsRegistry(), skinnyAgents, helmetEquipped, RecordingPublisher(), tick = 1,
        )

        assertEquals(
            WorldRejection.OverEncumbered(agent, requested = 300, capacity = 100),
            result.leftOrNull(),
        )
    }

    // ─────────────────────── Skill XP / recommendation paths ───────────────────────

    @Test
    fun `slotted skill receives XP equal to the gathered quantity`() {
        val state = stateWith()
        val store = StubResourceStore(initial = mapOf(wood to 50))
        val skills = StubSkillsRegistry().apply { slot(lumberjacking) }
        val publisher = RecordingPublisher()

        reduceGather(
            state, WorldCommand.GatherResource(agent, wood), balance, items,
            store, skills, agents, equipment, publisher, tick = 7,
        )

        assertEquals(1, skills.xpAddCalls.size)
        val (callSkill, callDelta) = skills.xpAddCalls.single()
        assertEquals(lumberjacking, callSkill)
        assertEquals(1, callDelta)
        // No milestone crossed at xp=1.
        assertTrue(publisher.events.none { it is WorldEvent.SkillMilestoneReached })
        // Slot is filled, so no recommendation either.
        assertTrue(publisher.events.none { it is WorldEvent.SkillRecommended })
    }

    @Test
    fun `crossing a milestone publishes SkillMilestoneReached events`() {
        val state = stateWith()
        val store = StubResourceStore(initial = mapOf(wood to 100))
        val skills = StubSkillsRegistry().apply {
            slot(lumberjacking)
            // Pretend the prior addXp would push xp from 49 to 50, crossing one threshold.
            crossedMilestonesOnNextAdd[lumberjacking] = listOf(50)
        }
        val publisher = RecordingPublisher()

        reduceGather(
            state, WorldCommand.GatherResource(agent, wood), balance, items,
            store, skills, agents, equipment, publisher, tick = 7,
        )

        val milestoneEvents = publisher.events.filterIsInstance<WorldEvent.SkillMilestoneReached>()
        assertEquals(1, milestoneEvents.size)
        val ev = milestoneEvents.single()
        assertEquals(agent, ev.agent)
        assertEquals(lumberjacking, ev.skill)
        assertEquals(50, ev.milestone)
        assertEquals(7L, ev.tick)
    }

    @Test
    fun `unslotted skill triggers a SkillRecommended event when maybeRecommend fires`() {
        val state = stateWith()
        val store = StubResourceStore(initial = mapOf(wood to 50))
        val skills = StubSkillsRegistry().apply {
            // No slot assigned for LUMBERJACKING; arrange the registry to recommend.
            recommendOnNext[lumberjacking] = 1
        }
        val publisher = RecordingPublisher()

        reduceGather(
            state, WorldCommand.GatherResource(agent, wood), balance, items,
            store, skills, agents, equipment, publisher, tick = 7,
        )

        val recs = publisher.events.filterIsInstance<WorldEvent.SkillRecommended>()
        assertEquals(1, recs.size)
        val rec = recs.single()
        assertEquals(lumberjacking, rec.skill)
        assertEquals(1, rec.recommendCount)
    }

    @Test
    fun `unslotted skill with maybeRecommend returning null fires no event`() {
        val state = stateWith()
        val store = StubResourceStore(initial = mapOf(wood to 50))
        val skills = StubSkillsRegistry() // recommendOnNext empty → returns null
        val publisher = RecordingPublisher()

        reduceGather(
            state, WorldCommand.GatherResource(agent, wood), balance, items,
            store, skills, agents, equipment, publisher, tick = 7,
        )

        assertTrue(publisher.events.none { it is WorldEvent.SkillRecommended })
        assertTrue(publisher.events.none { it is WorldEvent.SkillMilestoneReached })
    }

    @Test
    fun `item without gathering-skill triggers neither XP nor recommendation`() {
        val state = stateWith()
        val store = StubResourceStore(initial = mapOf(wood to 50))
        // Replace the wood item with one that has no gathering-skill.
        val skillFreeItems = StubItemLookup(mapOf(wood to itemFor(wood, gatheringSkill = null)))
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()

        reduceGather(
            state, WorldCommand.GatherResource(agent, wood), balance, skillFreeItems,
            store, skills, agents, equipment, publisher, tick = 7,
        )

        assertEquals(0, skills.xpAddCalls.size)
        assertEquals(0, skills.recommendCalls.size)
        assertTrue(publisher.events.none { it is WorldEvent.SkillRecommended })
        assertTrue(publisher.events.none { it is WorldEvent.SkillMilestoneReached })
    }

    // ─────────────────────── helpers ───────────────────────

    private fun rule(item: ItemId, chance: Double, qty: IntRange) =
        ResourceSpawnRule(item = item, spawnChance = chance, quantityRange = qty)

    private fun balance(
        spawns: Map<Terrain, List<ResourceSpawnRule>>,
        staminaCost: Int,
        yield: Int = 1,
        carryGramsPerStrengthPoint: Int = 5_000,
    ) = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = spawns[terrain].orEmpty()
        override fun gatherStaminaCost(item: ItemId): Int = staminaCost
        override fun gatherYield(item: ItemId): Int = yield
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun carryGramsPerStrengthPoint(): Int = carryGramsPerStrengthPoint
    }

    private fun itemFor(id: ItemId, gatheringSkill: String? = null) = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 100,
        maxStack = 100,
        gatheringSkill = gatheringSkill,
    )

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    /**
     * In-memory resource store. Pre-seed with `(item → currentQuantity)`.
     */
    private inner class StubResourceStore(
        initial: Map<ItemId, Int> = emptyMap(),
        initialMaxima: Map<ItemId, Int> = emptyMap(),
    ) : NodeResourceStore {
        private val cells = initial.mapValues { (item, qty) ->
            qty to (initialMaxima[item] ?: qty.coerceAtLeast(1))
        }.toMutableMap()

        fun quantity(item: ItemId): Int = cells[item]?.first ?: 0

        override fun read(nodeId: NodeId, tick: Long): NodeResources =
            NodeResources(
                cells.mapValues { (item, qty) ->
                    NodeResourceView(itemId = item, quantity = qty.first, initialQuantity = qty.second)
                },
            )

        override fun availability(nodeId: NodeId, item: ItemId, tick: Long): NodeResourceCell? {
            val (qty, initial) = cells[item] ?: return null
            return NodeResourceCell(nodeId, item, qty, initial)
        }

        override fun decrement(nodeId: NodeId, item: ItemId, amount: Int, tick: Long) {
            val (qty, initial) = cells[item] ?: error("decrement on missing cell ($nodeId, $item)")
            check(qty >= amount) { "decrement under zero: have=$qty want=$amount" }
            cells[item] = (qty - amount) to initial
        }

        override fun seed(rows: Collection<InitialResourceRow>, tick: Long) {
            for (row in rows) {
                cells.putIfAbsent(row.item, row.quantity to row.quantity)
            }
        }
    }

    /**
     * Test double for [AgentSkillsRegistry] — records calls and returns scripted results.
     * Behaviour:
     *  - `slot(skill)` marks the skill as currently slotted; future addXpIfSlotted calls
     *    accept and append to xpAddCalls.
     *  - `crossedMilestonesOnNextAdd[skill] = listOf(50)` makes the next addXpIfSlotted
     *    for that skill return those crossings.
     *  - `recommendOnNext[skill] = N` makes the next maybeRecommend for that skill
     *    return N (and increments through subsequent integers if you set 1, 2, 3 across calls).
     */
    private class StubSkillsRegistry : AgentSkillsRegistry {
        private val slottedSkills = mutableSetOf<SkillId>()
        val xpAddCalls = mutableListOf<Pair<SkillId, Int>>()
        val recommendCalls = mutableListOf<Pair<SkillId, Long>>()
        val crossedMilestonesOnNextAdd = mutableMapOf<SkillId, List<Int>>()
        val recommendOnNext = mutableMapOf<SkillId, Int?>()
        var slotCount: Int = 8
        var slotsFilled: Int = 0

        fun slot(skill: SkillId) {
            slottedSkills += skill
            slotsFilled = slottedSkills.size
        }

        override fun snapshot(agent: AgentId): AgentSkillsSnapshot {
            return AgentSkillsSnapshot(
                perSkill = slottedSkills.associateWith { skillId ->
                    AgentSkillState(
                        skill = skillId,
                        xp = 0,
                        level = 0,
                        slotIndex = slottedSkills.indexOf(skillId),
                        recommendCount = 0,
                    )
                }.mapKeys { it.key },
                slotCount = slotCount,
                slotsFilled = slotsFilled,
            )
        }

        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult {
            if (skill !in slottedSkills) return AddXpResult.Unslotted
            xpAddCalls += skill to delta
            val crossed = crossedMilestonesOnNextAdd.remove(skill) ?: emptyList()
            return AddXpResult.Accrued(crossed)
        }

        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? {
            // Mirror the production contract: slotted skills don't get recommended.
            // Without this, a future test that scripts both slot() and recommendOnNext
            // for the same skill would observe a recommendation event the real impl
            // suppresses.
            if (skill in slottedSkills) return null
            recommendCalls += skill to tick
            return recommendOnNext.remove(skill)
        }

        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? {
            slottedSkills += skill
            slotsFilled = slottedSkills.size
            return null
        }
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }

    private inner class StubAgentRegistry(private val strength: Int) : AgentRegistry {
        override fun find(id: AgentId): Agent? = if (id == agent) {
            Agent(
                id = id,
                owner = PlayerId(UUID.randomUUID()),
                name = "test",
                attributes = AgentAttributes(strength = strength),
            )
        } else {
            null
        }

        override fun listForOwner(owner: PlayerId): List<Agent> = error("not used in this test")
    }

    private class StubEquipmentStore(
        private val equipped: Map<EquipSlot, EquipmentInstance> = emptyMap(),
    ) : EquipmentInstanceStore {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> = equipped
        override fun insert(instance: EquipmentInstance) = error("not used")
        override fun findById(instanceId: UUID): EquipmentInstance? = error("not used")
        override fun listByAgent(agentId: AgentId): List<EquipmentInstance> = error("not used")
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance? =
            error("not used")
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance? = error("not used")
        override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? = error("not used")
        override fun delete(instanceId: UUID): Boolean = error("not used")
    }
}
