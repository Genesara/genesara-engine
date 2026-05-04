package dev.gvart.genesara.world.internal.crafting

import arrow.core.Either
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
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.ConsumableEffect
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Recipe
import dev.gvart.genesara.world.RecipeId
import dev.gvart.genesara.world.RecipeLookup
import dev.gvart.genesara.world.RecipeOutput
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.progression.SkillProgression
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CraftReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val ownerId = PlayerId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)

    private val ore = ItemId("ORE")
    private val wood = ItemId("WOOD")
    private val ironIngot = ItemId("IRON_INGOT")
    private val ironSword = ItemId("IRON_SWORD")
    private val healingSalve = ItemId("HEALING_SALVE")

    private val smithing = SkillId("SMITHING")
    private val alchemy = SkillId("ALCHEMY")

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

    private val ironSwordRecipe = Recipe(
        id = RecipeId("IRON_SWORD_BASIC"),
        output = RecipeOutput(item = ironSword, quantity = 1),
        inputs = mapOf(ironIngot to 2, wood to 1),
        requiredStation = BuildingCategoryHint.CRAFTING_STATION_METAL,
        requiredSkill = smithing,
        requiredSkillLevel = 5,
        staminaCost = 20,
    )
    private val healingSalveRecipe = Recipe(
        id = RecipeId("HEALING_SALVE_BASIC"),
        output = RecipeOutput(item = healingSalve, quantity = 1),
        inputs = mapOf(ItemId("HERB") to 2, ItemId("MUSHROOM") to 1),
        requiredStation = BuildingCategoryHint.CRAFTING_STATION_POTION,
        requiredSkill = alchemy,
        requiredSkillLevel = 0,
        staminaCost = 10,
    )

    private val items = StubItemLookup(
        mapOf(
            ore to itemDef(ore, ItemCategory.RESOURCE, weightPerUnit = 2000),
            wood to itemDef(wood, ItemCategory.RESOURCE, weightPerUnit = 800),
            ironIngot to itemDef(ironIngot, ItemCategory.RESOURCE, weightPerUnit = 1500),
            healingSalve to itemDef(healingSalve, ItemCategory.RESOURCE, weightPerUnit = 100),
            ItemId("HERB") to itemDef(ItemId("HERB"), ItemCategory.RESOURCE, weightPerUnit = 30),
            ItemId("MUSHROOM") to itemDef(ItemId("MUSHROOM"), ItemCategory.RESOURCE, weightPerUnit = 40),
            ironSword to itemDef(
                ironSword,
                ItemCategory.EQUIPMENT,
                weightPerUnit = 1800,
                maxDurability = 100,
                validSlots = setOf(EquipSlot.MAIN_HAND),
            ),
        ),
    )
    private val recipes = StubRecipeLookup(listOf(ironSwordRecipe, healingSalveRecipe))

    private fun stateWith(
        positioned: Boolean = true,
        stamina: Int = 100,
        inventory: Map<ItemId, Int> = mapOf(ironIngot to 10, wood to 10, ItemId("HERB") to 10, ItemId("MUSHROOM") to 10),
    ): WorldState {
        var inv = AgentInventory()
        for ((item, qty) in inventory) inv = inv.add(item, qty)
        return WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = emptySet())),
            positions = if (positioned) mapOf(agent to nodeId) else emptyMap(),
            bodies = mapOf(agent to AgentBody(hp = 50, maxHp = 50, stamina = stamina, maxStamina = 100, mana = 0, maxMana = 0)),
            inventories = mapOf(agent to inv),
        )
    }

    @Test
    fun `equipment recipe persists an instance signed by the calling agent and emits ItemCrafted`() {
        val state = stateWith()
        val skills = StubSkillsRegistry().apply { slot(smithing, level = 12) }
        val store = StubEquipmentStore()
        val publisher = RecordingPublisher()

        val (next, event) = assertNotNull(
            reduceCraft(
                state,
                WorldCommand.CraftItem(agent, ironSwordRecipe.id),
                stubBalance(),
                items,
                recipes,
                store,
                StubBuildingsLookup(stationsAt = mapOf(nodeId to setOf(BuildingCategoryHint.CRAFTING_STATION_METAL))),
                skills,
                StubAgents(luckyAgent(luck = 5)),
                fixedRoller(Rarity.UNCOMMON),
                SkillProgression(skills, publisher),
                tick = 7,
            ).getOrNull(),
        )

        val crafted = assertIs<WorldEvent.ItemCrafted>(event)
        assertEquals(ironSword, crafted.output)
        assertEquals(Rarity.UNCOMMON, crafted.rarity)
        assertNotNull(crafted.instanceId)
        assertEquals(7L, crafted.tick)

        val instance = store.inserted.single()
        assertEquals(agent, instance.creatorAgentId)
        assertEquals(agent, instance.agentId)
        assertEquals(ironSword, instance.itemId)
        assertEquals(Rarity.UNCOMMON, instance.rarity)
        assertEquals(100, instance.durabilityCurrent)
        assertEquals(100, instance.durabilityMax)
        assertNull(instance.equippedInSlot)

        assertEquals(8, next.inventoryOf(agent).quantityOf(ironIngot))
        assertEquals(9, next.inventoryOf(agent).quantityOf(wood))
        assertEquals(80, next.bodyOf(agent)!!.stamina)
    }

    @Test
    fun `stackable recipe adds quantity to inventory with no signature and a null instance id`() {
        val state = stateWith()
        val skills = StubSkillsRegistry().apply { slot(alchemy, level = 1) }
        val store = StubEquipmentStore()

        val (next, event) = assertNotNull(
            reduceCraft(
                state,
                WorldCommand.CraftItem(agent, healingSalveRecipe.id),
                stubBalance(),
                items,
                recipes,
                store,
                StubBuildingsLookup(stationsAt = mapOf(nodeId to setOf(BuildingCategoryHint.CRAFTING_STATION_POTION))),
                skills,
                StubAgents(luckyAgent(luck = 1)),
                fixedRoller(Rarity.RARE),
                SkillProgression(skills, RecordingPublisher()),
                tick = 3,
            ).getOrNull(),
        )

        val crafted = assertIs<WorldEvent.ItemCrafted>(event)
        assertEquals(healingSalve, crafted.output)
        assertNull(crafted.instanceId)
        assertNull(crafted.rarity)
        assertEquals(1, crafted.quantity)
        assertTrue(store.inserted.isEmpty(), "stackable output must not write to equipment_instances")
        assertEquals(1, next.inventoryOf(agent).quantityOf(healingSalve))
        assertEquals(8, next.inventoryOf(agent).quantityOf(ItemId("HERB")))
        assertEquals(9, next.inventoryOf(agent).quantityOf(ItemId("MUSHROOM")))
    }

    @Test
    fun `rejects with StackFull when stackable craft would push current stack above maxStack`() {
        val tightItems = StubItemLookup(
            mapOf(
                ItemId("HERB") to itemDef(ItemId("HERB"), ItemCategory.RESOURCE, weightPerUnit = 30),
                ItemId("MUSHROOM") to itemDef(ItemId("MUSHROOM"), ItemCategory.RESOURCE, weightPerUnit = 40),
                healingSalve to itemDef(healingSalve, ItemCategory.RESOURCE, weightPerUnit = 100, maxStack = 1),
            ),
        )
        val state = stateWith(
            inventory = mapOf(ItemId("HERB") to 10, ItemId("MUSHROOM") to 10, healingSalve to 1),
        )
        val skills = StubSkillsRegistry().apply { slot(alchemy, level = 1) }
        val result = reduceCraft(
            state,
            WorldCommand.CraftItem(agent, healingSalveRecipe.id),
            stubBalance(),
            tightItems,
            recipes,
            StubEquipmentStore(),
            StubBuildingsLookup(stationsAt = mapOf(nodeId to setOf(BuildingCategoryHint.CRAFTING_STATION_POTION))),
            skills,
            StubAgents(luckyAgent()),
            fixedRoller(Rarity.COMMON),
            SkillProgression(skills, RecordingPublisher()),
            tick = 1,
        )
        val rejection = assertIs<WorldRejection.StackFull>(result.leftOrNull())
        assertEquals(healingSalve, rejection.item)
        assertEquals(1, rejection.current)
        assertEquals(1, rejection.incoming)
        assertEquals(1, rejection.maxStack)
    }

    @Test
    fun `rejects when agent is not in the world`() {
        val result = runReducer(state = stateWith(positioned = false), command = WorldCommand.CraftItem(agent, ironSwordRecipe.id))
        assertEquals(WorldRejection.NotInWorld(agent), result.leftOrNull())
    }

    @Test
    fun `rejects with UnknownRecipe when the catalog does not list the id`() {
        val result = runReducer(command = WorldCommand.CraftItem(agent, RecipeId("BOGUS")))
        val rejection = assertIs<WorldRejection.UnknownRecipe>(result.leftOrNull())
        assertEquals(RecipeId("BOGUS"), rejection.recipe)
    }

    @Test
    fun `rejects with RecipeRequiresStation when the node has no matching station`() {
        val result = runReducer(
            command = WorldCommand.CraftItem(agent, ironSwordRecipe.id),
            buildings = StubBuildingsLookup(stationsAt = emptyMap()),
        )
        val rejection = assertIs<WorldRejection.RecipeRequiresStation>(result.leftOrNull())
        assertEquals(BuildingCategoryHint.CRAFTING_STATION_METAL, rejection.station)
        assertEquals(nodeId, rejection.node)
    }

    @Test
    fun `rejects with CraftSkillTooLow when the agent's level is below the gate`() {
        val skills = StubSkillsRegistry().apply { slot(smithing, level = 2) }
        val result = runReducer(
            command = WorldCommand.CraftItem(agent, ironSwordRecipe.id),
            skills = skills,
        )
        val rejection = assertIs<WorldRejection.CraftSkillTooLow>(result.leftOrNull())
        assertEquals(smithing, rejection.skill)
        assertEquals(5, rejection.required)
        assertEquals(2, rejection.current)
    }

    @Test
    fun `accepts when the gate is zero and the agent has no slot in the skill`() {
        val recipeNoGate = ironSwordRecipe.copy(requiredSkillLevel = 0)
        val recipes = StubRecipeLookup(listOf(recipeNoGate))
        val skills = StubSkillsRegistry()
        val result = reduceCraft(
            stateWith(),
            WorldCommand.CraftItem(agent, recipeNoGate.id),
            stubBalance(),
            items,
            recipes,
            StubEquipmentStore(),
            StubBuildingsLookup(stationsAt = mapOf(nodeId to setOf(BuildingCategoryHint.CRAFTING_STATION_METAL))),
            skills,
            StubAgents(luckyAgent()),
            fixedRoller(Rarity.COMMON),
            SkillProgression(skills, RecordingPublisher()),
            tick = 1,
        )
        assertNotNull(result.getOrNull())
    }

    @Test
    fun `rejects with NotEnoughStamina when stamina is below the recipe cost`() {
        val skills = StubSkillsRegistry().apply { slot(smithing, level = 10) }
        val result = runReducer(
            state = stateWith(stamina = 5),
            command = WorldCommand.CraftItem(agent, ironSwordRecipe.id),
            skills = skills,
        )
        assertEquals(WorldRejection.NotEnoughStamina(agent, required = 20, available = 5), result.leftOrNull())
    }

    @Test
    fun `rejects with InsufficientCraftMaterials and reports the first missing item`() {
        val skills = StubSkillsRegistry().apply { slot(smithing, level = 10) }
        val state = stateWith(inventory = mapOf(ironIngot to 1, wood to 1))
        val result = runReducer(
            state = state,
            command = WorldCommand.CraftItem(agent, ironSwordRecipe.id),
            skills = skills,
        )
        val rejection = assertIs<WorldRejection.InsufficientCraftMaterials>(result.leftOrNull())
        assertEquals(ironIngot, rejection.item)
        assertEquals(2, rejection.required)
        assertEquals(1, rejection.available)
    }

    @Test
    fun `rejects with OverEncumbered when the rolled equipment would push the agent over carry cap`() {
        val skills = StubSkillsRegistry().apply { slot(smithing, level = 12) }
        val weakAgent = luckyAgent(strength = 1, luck = 1)
        val store = StubEquipmentStore()
        val result = reduceCraft(
            stateWith(inventory = mapOf(ironIngot to 4, wood to 2)),
            WorldCommand.CraftItem(agent, ironSwordRecipe.id),
            stubBalance(),
            items,
            recipes,
            store,
            StubBuildingsLookup(stationsAt = mapOf(nodeId to setOf(BuildingCategoryHint.CRAFTING_STATION_METAL))),
            skills,
            StubAgents(weakAgent),
            fixedRoller(Rarity.COMMON),
            SkillProgression(skills, RecordingPublisher()),
            tick = 1,
        )
        assertIs<WorldRejection.OverEncumbered>(result.leftOrNull())
        assertTrue(store.inserted.isEmpty(), "no equipment row written on a rejected craft")
    }

    @Test
    fun `slotted skill receives one XP per craft step`() {
        val skills = StubSkillsRegistry().apply { slot(smithing, level = 12) }
        runReducer(skills = skills, command = WorldCommand.CraftItem(agent, ironSwordRecipe.id))
        assertEquals(listOf(smithing to 1), skills.xpAddCalls)
    }

    @Test
    fun `unslotted skill triggers a SkillRecommended event when maybeRecommend says yes`() {
        val skills = StubSkillsRegistry().apply { recommendOnNext[alchemy] = 1 }
        val publisher = RecordingPublisher()
        val state = stateWith(inventory = mapOf(ItemId("HERB") to 5, ItemId("MUSHROOM") to 5))
        reduceCraft(
            state,
            WorldCommand.CraftItem(agent, healingSalveRecipe.id),
            stubBalance(),
            items,
            recipes,
            StubEquipmentStore(),
            StubBuildingsLookup(stationsAt = mapOf(nodeId to setOf(BuildingCategoryHint.CRAFTING_STATION_POTION))),
            skills,
            StubAgents(luckyAgent()),
            fixedRoller(Rarity.COMMON),
            SkillProgression(skills, publisher),
            tick = 5,
        )
        val rec = publisher.events.filterIsInstance<WorldEvent.SkillRecommended>().single()
        assertEquals(alchemy, rec.skill)
        assertEquals(1, rec.recommendCount)
    }

    @Test
    fun `rolled rarity reflects the agent's actual smithing skill level fed to the roller`() {
        val skills = StubSkillsRegistry().apply { slot(smithing, level = 25) }
        val capturingRoller = CapturingRoller()
        reduceCraft(
            stateWith(),
            WorldCommand.CraftItem(agent, ironSwordRecipe.id),
            stubBalance(),
            items,
            recipes,
            StubEquipmentStore(),
            StubBuildingsLookup(stationsAt = mapOf(nodeId to setOf(BuildingCategoryHint.CRAFTING_STATION_METAL))),
            skills,
            StubAgents(luckyAgent(luck = 7)),
            capturingRoller,
            SkillProgression(skills, RecordingPublisher()),
            tick = 1,
        )
        assertEquals(25, capturingRoller.lastSkill)
        assertEquals(7, capturingRoller.lastLuck)
    }

    private fun runReducer(
        state: WorldState = stateWith(),
        command: WorldCommand.CraftItem = WorldCommand.CraftItem(agent, ironSwordRecipe.id),
        skills: StubSkillsRegistry = StubSkillsRegistry().apply { slot(smithing, level = 10) },
        buildings: BuildingsLookup = StubBuildingsLookup(
            stationsAt = mapOf(nodeId to setOf(BuildingCategoryHint.CRAFTING_STATION_METAL, BuildingCategoryHint.CRAFTING_STATION_POTION)),
        ),
    ): Either<WorldRejection, Pair<WorldState, WorldEvent>> = reduceCraft(
        state,
        command,
        stubBalance(),
        items,
        recipes,
        StubEquipmentStore(),
        buildings,
        skills,
        StubAgents(luckyAgent()),
        fixedRoller(Rarity.COMMON),
        SkillProgression(skills, RecordingPublisher()),
        tick = 1,
    )

    private fun luckyAgent(strength: Int = 20, luck: Int = 1): Agent = Agent(
        id = agent,
        owner = ownerId,
        name = "test",
        attributes = AgentAttributes(strength = strength, dexterity = 1, constitution = 1, perception = 1, intelligence = 1, luck = luck),
    )

    private fun fixedRoller(rarity: Rarity): RarityRoller = object : RarityRoller(Random(0)) {
        override fun roll(skillLevel: Int, luck: Int): Rarity = rarity
    }

    private fun itemDef(
        id: ItemId,
        category: ItemCategory,
        weightPerUnit: Int = 0,
        maxDurability: Int? = null,
        validSlots: Set<EquipSlot> = emptySet(),
        maxStack: Int = 100,
    ): Item = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = category,
        weightPerUnit = weightPerUnit,
        maxStack = maxStack,
        consumable = null,
        regenerating = false,
        regenIntervalTicks = 0,
        regenAmount = 0,
        harvestSkill = null,
        rarity = Rarity.COMMON,
        maxDurability = maxDurability,
        validSlots = validSlots,
        twoHanded = false,
        requiredAttributes = emptyMap(),
        requiredSkills = emptyMap(),
    )

    private fun stubBalance(): BalanceLookup = object : BalanceLookup {
        override fun carryGramsPerStrengthPoint(): Int = 5_000
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int = error("unused")
        override fun staminaRegenPerTick(climate: Climate): Int = error("unused")
        override fun resourceSpawnsFor(terrain: Terrain): List<dev.gvart.genesara.world.ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = error("unused")
        override fun harvestYield(item: ItemId): Int = error("unused")
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = error("unused")
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = error("unused")
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 0
        override fun drinkThirstRefill(): Int = 0
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
    }

    private class StubItemLookup(private val items: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = items[id]
        override fun all(): List<Item> = items.values.toList()
    }

    private class StubRecipeLookup(private val recipes: List<Recipe>) : RecipeLookup {
        private val byId = recipes.associateBy { it.id }
        override fun byId(id: RecipeId): Recipe? = byId[id]
        override fun all(): List<Recipe> = recipes
    }

    private class StubEquipmentStore : EquipmentInstanceStore {
        val inserted = mutableListOf<EquipmentInstance>()
        override fun insert(instance: EquipmentInstance) {
            inserted += instance
        }
        override fun findById(instanceId: UUID): EquipmentInstance? = inserted.firstOrNull { it.instanceId == instanceId }
        override fun listByAgent(agentId: AgentId): List<EquipmentInstance> = inserted.filter { it.agentId == agentId }
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> = emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance? = null
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance? = null
        override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? = null
        override fun delete(instanceId: UUID): Boolean = false
    }

    private class StubBuildingsLookup(
        private val stationsAt: Map<NodeId, Set<BuildingCategoryHint>>,
    ) : BuildingsLookup {
        override fun byId(id: UUID): Building? = null
        override fun byNode(node: NodeId): List<Building> = emptyList()
        override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> = emptyMap()
        override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> {
            val stations = stationsAt[node] ?: return emptyList()
            return if (hint in stations) listOf(stationStub(node)) else emptyList()
        }
        private fun stationStub(node: NodeId): Building = Building(
            instanceId = UUID.randomUUID(),
            nodeId = node,
            type = BuildingType.FORGE,
            status = BuildingStatus.ACTIVE,
            builtByAgentId = AgentId(UUID.randomUUID()),
            builtAtTick = 0,
            lastProgressTick = 0,
            progressSteps = 1,
            totalSteps = 1,
            hpCurrent = 100,
            hpMax = 100,
        )
    }

    private class StubAgents(private val agent: Agent) : AgentRegistry {
        override fun find(id: AgentId): Agent? = if (id == agent.id) agent else null
        override fun listForOwner(owner: PlayerId): List<Agent> = listOf(agent)
    }

    private class StubSkillsRegistry : AgentSkillsRegistry {
        private val slottedSkills = mutableSetOf<SkillId>()
        private val levels = mutableMapOf<SkillId, Int>()
        val xpAddCalls = mutableListOf<Pair<SkillId, Int>>()
        val recommendOnNext = mutableMapOf<SkillId, Int?>()

        fun slot(skill: SkillId, level: Int = 0) {
            slottedSkills += skill
            levels[skill] = level
        }

        override fun snapshot(agent: AgentId): AgentSkillsSnapshot = AgentSkillsSnapshot(
            perSkill = slottedSkills.associateWith { skillId ->
                AgentSkillState(
                    skill = skillId,
                    xp = 0,
                    level = levels[skillId] ?: 0,
                    slotIndex = 0,
                    recommendCount = 0,
                )
            },
            slotCount = 8,
            slotsFilled = slottedSkills.size,
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

    private class CapturingRoller : RarityRoller(Random(0)) {
        var lastSkill: Int = -1
        var lastLuck: Int = -1
        override fun roll(skillLevel: Int, luck: Int): Rarity {
            lastSkill = skillLevel
            lastLuck = luck
            return Rarity.COMMON
        }
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }
}

private fun <L, R> Either<L, R>.leftOrNull(): L? = (this as? Either.Left<L>)?.value
