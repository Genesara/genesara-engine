package dev.gvart.genesara.world.body.internal.consume

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AddCharacterXpOutcome
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.CharacterXpSource
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.player.events.AgentEvent
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.ConsumableEffect
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.RecipeLearning
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.BodyCommand
import dev.gvart.genesara.world.events.BodyEvent
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.classes.CharacterXpProgression
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class ConsumeReducerTest {

    private val agent = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val berry = ItemId("BERRY")
    private val wood = ItemId("WOOD")
    private val foraging = SkillId("FORAGING")

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
        positioned: Boolean = true,
        hunger: Int = 50,
        inventory: AgentInventory = AgentInventory(mapOf(berry to 3)),
    ) = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeId to Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())),
        positions = if (positioned) mapOf(agent to nodeId) else emptyMap(),
        bodies = mapOf(agent to AgentBody(
            hp = 50, maxHp = 50,
            stamina = 30, maxStamina = 50,
            mana = 0, maxMana = 0,
            hunger = hunger, maxHunger = 100,
            thirst = 60, maxThirst = 100,
            sleep = 60, maxSleep = 100,
        )),
        inventories = mapOf(agent to inventory),
    )

    private val items = StubItemLookup(
        mapOf(
            berry to item(berry, ConsumableEffect(Gauge.HUNGER, 20), harvestSkill = foraging),
            wood to item(wood, null),
        )
    )

    private val agents: AgentRegistry = StubAgentRegistry()
    private fun noOpProgression(): SkillProgression =
        SkillProgression(StubSkillsRegistry(), RecordingPublisher())

    @Test
    fun `happy path - refills the gauge clamped to max, removes 1 from inventory, emits ItemConsumed`() {
        val state = stateWith(hunger = 90, inventory = AgentInventory(mapOf(berry to 2)))
        val command = BodyCommand.ConsumeItem(agent, berry)

        val result = reduceConsume(state.body, state.core, command, items, agents, noOpProgression(), CharacterXpProgression.NoOp, RecipeLearning.NoOp, tick = 7)

        val (next, _, events) = assertNotNull(result.getOrNull())
        val event = events.single()
        assertEquals(100, next.bodyOf(agent)!!.hunger)
        assertEquals(1, next.inventoryOf(agent).quantityOf(berry))
        val consumed = assertIs<BodyEvent.ItemConsumed>(event)
        assertEquals(agent, consumed.agent)
        assertEquals(berry, consumed.item)
        assertEquals(Gauge.HUNGER, consumed.gauge)
        assertEquals(10, consumed.refilled)
        assertEquals(7L, consumed.tick)
        assertEquals(command.commandId, consumed.causedBy)
    }

    @Test
    fun `grants 1 character XP tagged CONSUME with the command id on every consume`() {
        val state = stateWith(inventory = AgentInventory(mapOf(berry to 1)))
        val command = BodyCommand.ConsumeItem(agent, berry)
        val characterXp = RecordingCharacterXpProgression()

        val result = reduceConsume(
            state.body, state.core, command, items, agents, noOpProgression(),
            characterXp, RecipeLearning.NoOp, tick = 7,
        )

        assertNotNull(result.getOrNull())
        val call = characterXp.calls.single()
        assertEquals(agent, call.agentId)
        assertEquals(CharacterXpSource.CONSUME, call.source)
        assertEquals(1, call.delta)
        assertEquals(command.commandId, call.commandId)
    }

    @Test
    fun `last unit - removing 1 from a stack of 1 drops the entry entirely`() {
        val state = stateWith(inventory = AgentInventory(mapOf(berry to 1)))

        val result = reduceConsume(state.body, state.core, BodyCommand.ConsumeItem(agent, berry), items, agents, noOpProgression(), CharacterXpProgression.NoOp, RecipeLearning.NoOp, tick = 1)

        val (next, _, _) = assertNotNull(result.getOrNull())
        assertEquals(0, next.inventoryOf(agent).quantityOf(berry))
    }

    @Test
    fun `consuming an item with a harvest skill grants that skill 1 XP when slotted`() {
        val state = stateWith(inventory = AgentInventory(mapOf(berry to 2)))
        val skills = StubSkillsRegistry().apply { slot(foraging) }
        val publisher = RecordingPublisher()

        val result = reduceConsume(
            state.body, state.core, BodyCommand.ConsumeItem(agent, berry), items, agents,
            SkillProgression(skills, publisher), CharacterXpProgression.NoOp, RecipeLearning.NoOp, tick = 1,
        )

        assertNotNull(result.getOrNull())
        assertEquals(listOf(foraging to 1), skills.xpAddCalls)
    }

    @Test
    fun `consuming an item with no harvest skill grants no skill XP`() {
        val plainBerry = ItemId("PLAIN_BERRY")
        val plainItems = StubItemLookup(
            mapOf(plainBerry to item(plainBerry, ConsumableEffect(Gauge.HUNGER, 20), harvestSkill = null)),
        )
        val state = stateWith(inventory = AgentInventory(mapOf(plainBerry to 1)))
        val skills = StubSkillsRegistry().apply { slot(foraging) }

        reduceConsume(
            state.body, state.core, BodyCommand.ConsumeItem(agent, plainBerry), plainItems, agents,
            SkillProgression(skills, RecordingPublisher()), CharacterXpProgression.NoOp, RecipeLearning.NoOp, tick = 1,
        )

        assertTrue(skills.xpAddCalls.isEmpty())
    }

    @Test
    fun `consuming an unslotted FORAGING item triggers a SkillRecommended event when maybeRecommend fires`() {
        val state = stateWith(inventory = AgentInventory(mapOf(berry to 1)))
        val skills = StubSkillsRegistry().apply { recommendOnNext[foraging] = 1 }
        val publisher = RecordingPublisher()

        reduceConsume(
            state.body, state.core, BodyCommand.ConsumeItem(agent, berry), items, agents,
            SkillProgression(skills, publisher), CharacterXpProgression.NoOp, RecipeLearning.NoOp, tick = 1,
        )

        assertTrue(publisher.events.any { it is AgentEvent.SkillRecommended })
    }

    @Test
    fun `consume invokes recipeLearning with the consumed item id and the current tick`() {
        val state = stateWith(inventory = AgentInventory(mapOf(berry to 1)))
        val recipeLearning = RecordingRecipeLearning()

        reduceConsume(
            state.body, state.core, BodyCommand.ConsumeItem(agent, berry), items, agents,
            noOpProgression(), CharacterXpProgression.NoOp, recipeLearning, tick = 42,
        )

        assertEquals(listOf(agent to berry to 42L), recipeLearning.itemCalls)
    }

    @Test
    fun `rejects when agent is not in the world`() {
        val state = stateWith(positioned = false)

        val result = reduceConsume(state.body, state.core, BodyCommand.ConsumeItem(agent, berry), items, agents, noOpProgression(), CharacterXpProgression.NoOp, RecipeLearning.NoOp, tick = 1)

        assertEquals(WorldRejection.NotInWorld(agent), result.leftOrNull())
    }

    @Test
    fun `rejects when item is not in the catalog`() {
        val state = stateWith()
        val unknown = ItemId("PHANTOM")

        val result = reduceConsume(state.body, state.core, BodyCommand.ConsumeItem(agent, unknown), items, agents, noOpProgression(), CharacterXpProgression.NoOp, RecipeLearning.NoOp, tick = 1)

        assertEquals(WorldRejection.UnknownItem(unknown), result.leftOrNull())
    }

    @Test
    fun `rejects when item is not consumable`() {
        val state = stateWith(inventory = AgentInventory(mapOf(wood to 2)))

        val result = reduceConsume(state.body, state.core, BodyCommand.ConsumeItem(agent, wood), items, agents, noOpProgression(), CharacterXpProgression.NoOp, RecipeLearning.NoOp, tick = 1)

        assertEquals(WorldRejection.ItemNotConsumable(wood), result.leftOrNull())
    }

    @Test
    fun `rejects when agent does not own the item`() {
        val state = stateWith(inventory = AgentInventory.EMPTY)

        val result = reduceConsume(state.body, state.core, BodyCommand.ConsumeItem(agent, berry), items, agents, noOpProgression(), CharacterXpProgression.NoOp, RecipeLearning.NoOp, tick = 1)

        assertEquals(WorldRejection.ItemNotInInventory(agent, berry), result.leftOrNull())
    }

    @Test
    fun `consumability check wins over ownership when both fail simultaneously`() {
        val state = stateWith(inventory = AgentInventory.EMPTY)

        val result = reduceConsume(state.body, state.core, BodyCommand.ConsumeItem(agent, wood), items, agents, noOpProgression(), CharacterXpProgression.NoOp, RecipeLearning.NoOp, tick = 1)

        assertEquals(WorldRejection.ItemNotConsumable(wood), result.leftOrNull())
    }

    private fun item(id: ItemId, effect: ConsumableEffect?, harvestSkill: SkillId? = null) = Item(
        id = id,
        displayName = id.value,
        description = "",
        category = ItemCategory.RESOURCE,
        weightPerUnit = 100,
        maxStack = 100,
        consumable = effect,
        harvestSkill = harvestSkill,
    )

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private inner class StubAgentRegistry : AgentRegistry {
        override fun find(id: AgentId): Agent? = if (id == agent) {
            Agent(
                id = id,
                owner = PlayerId(UUID.randomUUID()),
                name = "test",
                attributes = AgentAttributes(strength = 1),
            )
        } else null

        override fun listForOwner(owner: PlayerId): List<Agent> = error("not used in this test")
    }

    private class StubSkillsRegistry : AgentSkillsRegistry {
        private val slottedSkills = mutableSetOf<SkillId>()
        val xpAddCalls = mutableListOf<Pair<SkillId, Int>>()
        val recommendOnNext = mutableMapOf<SkillId, Int?>()

        fun slot(skill: SkillId) {
            slottedSkills += skill
        }

        override fun snapshot(agent: AgentId): AgentSkillsSnapshot =
            AgentSkillsSnapshot(
                perSkill = slottedSkills.associateWith { skillId ->
                    AgentSkillState(
                        skill = skillId,
                        xp = 0,
                        level = 0,
                        slotIndex = slottedSkills.indexOf(skillId),
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

        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? {
            if (skill in slottedSkills) return null
            return recommendOnNext.remove(skill)
        }

        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? {
            slottedSkills += skill
            return null
        }
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }

    private class RecordingCharacterXpProgression : CharacterXpProgression {
        data class Call(
            val agentId: AgentId,
            val source: CharacterXpSource,
            val delta: Int,
            val tick: Long,
            val commandId: UUID,
        )

        val calls = mutableListOf<Call>()

        override fun grant(
            agentId: AgentId,
            source: CharacterXpSource,
            delta: Int,
            tick: Long,
            commandId: UUID,
        ): AddCharacterXpOutcome? {
            calls += Call(agentId, source, delta, tick, commandId)
            return null
        }
    }

    private class RecordingRecipeLearning : RecipeLearning {
        val itemCalls = mutableListOf<Pair<Pair<AgentId, ItemId>, Long>>()
        override fun learnFromPerk(agent: AgentId, perk: dev.gvart.genesara.player.PerkId, tick: Long) = emptyList<dev.gvart.genesara.world.RecipeId>()
        override fun learnFromItem(agent: AgentId, item: ItemId, tick: Long): List<dev.gvart.genesara.world.RecipeId> {
            itemCalls += (agent to item) to tick
            return emptyList()
        }
    }
}
