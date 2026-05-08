package dev.gvart.genesara.world.internal.combat

import dev.gvart.genesara.world.internal.testsupport.NoOpTriggeredPassiveDispatcher
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.LevelScalingAggregator.Companion.NoScaling
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.DeathPenaltyOutcome
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cross-call attack flow: two consecutive attack reducer invocations against the
 * same target, with state threaded from one call into the next. Verifies:
 *  - Target HP decreases as expected across attacks.
 *  - The killing blow emits both [WorldEvent.AgentAttacked] AND [WorldEvent.AgentDied]
 *    in the second call, both carrying the killing command's id as `causedBy`.
 *  - Attacker's kill-streak counter increments only on the killing blow.
 *  - SWORD-skill XP accrues on EVERY attack, not just the killing one — so the
 *    issue #5 recommendation hook fires from the moment combat starts.
 */
class AttackKillIntegrationTest {

    private val attacker = AgentId(UUID.randomUUID())
    private val target = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)

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
    private val node = Node(nodeId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())
    private val rustySword = ItemId("RUSTY_SWORD")
    private val swordSkill = SkillId("SWORD")

    @Test
    fun `two-attack kill — second blow emits AgentAttacked + AgentDied with shared causedBy and ticks kill streak`() {
        val items = StubItemLookup(
            mapOf(
                rustySword to Item(
                    id = rustySword,
                    displayName = "Rusty Sword",
                    description = "",
                    category = ItemCategory.EQUIPMENT,
                    weightPerUnit = 1500,
                    maxStack = 1,
                    damageType = DamageType.SLASH,
                    weaponPower = 8,
                    combatSkill = swordSkill,
                    validSlots = setOf(EquipSlot.MAIN_HAND),
                ),
            ),
        )
        val agents = StubAgentRegistry(
            byId = mapOf(
                attacker to AgentAttributes(strength = 10, luck = 0, dexterity = 0),
                target to AgentAttributes(strength = 1, luck = 0, dexterity = 0),
            ),
            scriptedDeath = mapOf(
                target to DeathPenaltyOutcome(xpLost = 0, deleveled = false, attributePointLost = null),
            ),
        )
        val equipment = StubEquipmentStore(
            equippedByAgent = mapOf(
                attacker to mapOf(
                    EquipSlot.MAIN_HAND to EquipmentInstance(
                        instanceId = UUID.randomUUID(),
                        agentId = attacker,
                        itemId = rustySword,
                        rarity = Rarity.COMMON,
                        durabilityCurrent = 50,
                        durabilityMax = 50,
                        creatorAgentId = null,
                        createdAtTick = 0L,
                        equippedInSlot = EquipSlot.MAIN_HAND,
                    ),
                ),
            ),
        )
        val groundItems = StubGroundItemStore()
        val balance = combatBalance()
        val deathProcessor = DeathProcessor(balance, agents, equipment, groundItems)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val progression = SkillProgression(skills, publisher)

        val initial = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeId to node),
            positions = mapOf(attacker to nodeId, target to nodeId),
            bodies = mapOf(
                attacker to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
                target to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
            ),
            inventories = emptyMap(),
        )

        val firstCommand = WorldCommand.AttackTarget(attacker, target)
        val (afterFirst, firstEvents) = assertNotNull(
            reduceAttack(
                initial, firstCommand, balance, items, agents, equipment, progression,
                deathProcessor = deathProcessor, rng = Random(seed = 1L), scaling = NoScaling, triggeredPassives = NoOpTriggeredPassiveDispatcher, tick = 1L,
            ).getOrNull(),
        )

        val firstAttacked = assertIs<WorldEvent.AgentAttacked>(firstEvents.single())
        assertEquals(80, firstAttacked.hpLost)
        assertEquals(false, firstAttacked.targetKilled)
        assertEquals(20, afterFirst.bodyOf(target)!!.hp)
        assertEquals(0, afterFirst.killStreakOf(attacker).killCount, "no kill yet → streak still EMPTY")

        val secondCommand = WorldCommand.AttackTarget(attacker, target)
        val (afterSecond, secondEvents) = assertNotNull(
            reduceAttack(
                afterFirst, secondCommand, balance, items, agents, equipment, progression,
                deathProcessor = deathProcessor, rng = Random(seed = 1L), scaling = NoScaling, triggeredPassives = NoOpTriggeredPassiveDispatcher, tick = 2L,
            ).getOrNull(),
        )

        assertEquals(2, secondEvents.size, "killing blow emits AgentAttacked + AgentDied")
        val secondAttacked = assertIs<WorldEvent.AgentAttacked>(secondEvents[0])
        val died = assertIs<WorldEvent.AgentDied>(secondEvents[1])
        assertEquals(true, secondAttacked.targetKilled)
        assertEquals(0, secondAttacked.targetHpAfter)
        assertEquals(secondCommand.commandId, secondAttacked.causedBy)
        assertEquals(secondCommand.commandId, died.causedBy, "AgentDied carries the killing attack's commandId")
        assertTrue(target !in afterSecond.positions, "DeathProcessor removed target from positions")
        assertEquals(1, afterSecond.killStreakOf(attacker).killCount, "kill streak ticks on the killing blow")
        assertEquals(2, skills.xpAddCalls.size, "SWORD XP accrued on each attack — issue #5 hook fires from the start")
        assertEquals(swordSkill to 1, skills.xpAddCalls[0])
        assertEquals(swordSkill to 1, skills.xpAddCalls[1])
    }

    private fun combatBalance(): BalanceLookup = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 5
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: dev.gvart.genesara.world.Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: dev.gvart.genesara.world.Gauge): Int = 25
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

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private class StubAgentRegistry(
        private val byId: Map<AgentId, AgentAttributes>,
        private val scriptedDeath: Map<AgentId, DeathPenaltyOutcome> = emptyMap(),
    ) : AgentRegistry {
        override fun find(id: AgentId): Agent? = byId[id]?.let {
            Agent(
                id = id,
                owner = PlayerId(UUID.randomUUID()),
                name = "test",
                attributes = it,
            )
        }
        override fun listForOwner(owner: PlayerId): List<Agent> = error("not used")
        override fun applyDeathPenalty(agentId: AgentId, xpLossOnDeath: Int): DeathPenaltyOutcome? =
            scriptedDeath[agentId]
    }

    private class StubEquipmentStore(
        private val equippedByAgent: Map<AgentId, Map<EquipSlot, EquipmentInstance>> = emptyMap(),
    ) : EquipmentInstanceStore {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> =
            equippedByAgent[agentId] ?: emptyMap()
        override fun insert(instance: EquipmentInstance) = error("not used")
        override fun findById(instanceId: UUID): EquipmentInstance? = error("not used")
        override fun listByAgent(agentId: AgentId): List<EquipmentInstance> = error("not used")
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance? =
            error("not used")
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance? = error("not used")
        override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? = error("not used")
        override fun delete(instanceId: UUID): Boolean = true
    }

    private class StubGroundItemStore : GroundItemStore {
        override fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long) = Unit
        override fun atNode(node: NodeId): List<GroundItemView> = error("not used")
        override fun take(node: NodeId, dropId: UUID): GroundItemView? = error("not used")
    }

    private class StubSkillsRegistry : AgentSkillsRegistry {
        val xpAddCalls = mutableListOf<Pair<SkillId, Int>>()

        override fun snapshot(agent: AgentId): AgentSkillsSnapshot =
            AgentSkillsSnapshot(perSkill = emptyMap(), slotCount = 8, slotsFilled = 0)

        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult {
            xpAddCalls += skill to delta
            return AddXpResult.Unslotted
        }

        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }
}
