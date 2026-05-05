package dev.gvart.genesara.world.internal.death

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.AttributePointLoss
import dev.gvart.genesara.player.DeathPenaltyOutcome
import dev.gvart.genesara.world.AgentKillStreak
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentInstance
import dev.gvart.genesara.world.EquipmentInstanceStore
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.inventory.AgentInventory
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeathSweepTest {

    private val regionId = RegionId(1L)
    private val nodeAId = NodeId(1L)
    private val nodeBId = NodeId(2L)

    private val region = Region(
        id = regionId,
        worldId = WorldId(1L),
        sphereIndex = 0,
        biome = Biome.PLAINS,
        climate = Climate.CONTINENTAL,
        centroid = Vec3(0.0, 0.0, 1.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )
    private val nodeA = Node(nodeAId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())
    private val nodeB = Node(nodeBId, regionId, q = 1, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())

    @Test
    fun `no agents at HP=0 — sweep is a no-op`() {
        val agentId = AgentId(UUID.randomUUID())
        val state = stateWith(agentId, hp = 50, atNode = nodeAId)
        val agents = StubRegistry()
        val equipment = StubEquipmentStore()
        val groundItems = StubGroundItemStore()

        val (next, events) = processDeaths(state, balance(), agents, equipment, groundItems, tick = 1)

        assertEquals(state, next)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `single agent at HP=0 — partial bar branch reports xpLost, no de-level`() {
        val agentId = AgentId(UUID.randomUUID())
        val state = stateWith(agentId, hp = 0, atNode = nodeAId)
        val agents = StubRegistry(
            mapOf(
                agentId to DeathPenaltyOutcome(
                    xpLost = 25,
                    deleveled = false,
                    attributePointLost = null,
                ),
            ),
        )
        val equipment = StubEquipmentStore()
        val groundItems = StubGroundItemStore()

        val (next, events) = processDeaths(state, balance(), agents, equipment, groundItems, tick = 7)

        assertTrue(agentId !in next.positions, "agent removed from positions on death")
        assertEquals(0, next.bodyOf(agentId)?.hp)
        val died = assertIs<WorldEvent.AgentDied>(events.single())
        assertEquals(agentId, died.agent)
        assertEquals(nodeAId, died.at)
        assertEquals(25, died.xpLost)
        assertEquals(false, died.deleveled)
        assertEquals(null, died.attributePointLost)
        assertEquals(7L, died.tick)
        assertEquals(null, died.causedBy, "starvation deaths have no causing command")
        assertNull(died.droppedItem, "no streak → no drop")
        assertEquals(emptyList(), groundItems.deposits, "no streak → ground item store untouched")
    }

    @Test
    fun `empty bar branch reports deleveled=true and attributePointLost=UNSPENT`() {
        val agentId = AgentId(UUID.randomUUID())
        val state = stateWith(agentId, hp = 0, atNode = nodeAId)
        val agents = StubRegistry(
            mapOf(
                agentId to DeathPenaltyOutcome(
                    xpLost = 0,
                    deleveled = true,
                    attributePointLost = AttributePointLoss.Unspent,
                ),
            ),
        )

        val (_, events) = processDeaths(state, balance(), agents, StubEquipmentStore(), StubGroundItemStore(), tick = 1)

        val died = assertIs<WorldEvent.AgentDied>(events.single())
        assertEquals("UNSPENT", died.attributePointLost)
        assertEquals(true, died.deleveled)
    }

    @Test
    fun `empty bar branch with allocated stat loss reports the attribute name`() {
        val agentId = AgentId(UUID.randomUUID())
        val state = stateWith(agentId, hp = 0, atNode = nodeAId)
        val agents = StubRegistry(
            mapOf(
                agentId to DeathPenaltyOutcome(
                    xpLost = 0,
                    deleveled = true,
                    attributePointLost = AttributePointLoss.Allocated(Attribute.STRENGTH),
                ),
            ),
        )

        val (_, events) = processDeaths(state, balance(), agents, StubEquipmentStore(), StubGroundItemStore(), tick = 1)

        val died = assertIs<WorldEvent.AgentDied>(events.single())
        assertEquals("STRENGTH", died.attributePointLost)
    }

    @Test
    fun `multiple agents dying at the same tick are sorted by agent id`() {
        val firstId = AgentId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val secondId = AgentId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
        val state = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeAId to nodeA, nodeBId to nodeB),
            positions = mapOf(firstId to nodeAId, secondId to nodeBId),
            bodies = mapOf(firstId to bodyAt(0), secondId to bodyAt(0)),
            inventories = emptyMap(),
        )
        val agents = StubRegistry(
            mapOf(
                firstId to DeathPenaltyOutcome(xpLost = 25, deleveled = false, attributePointLost = null),
                secondId to DeathPenaltyOutcome(xpLost = 25, deleveled = false, attributePointLost = null),
            ),
        )

        val (next, events) = processDeaths(state, balance(), agents, StubEquipmentStore(), StubGroundItemStore(), tick = 1)

        assertEquals(emptyMap(), next.positions)
        val deaths = events.filterIsInstance<WorldEvent.AgentDied>()
        assertEquals(listOf(firstId, secondId), deaths.map { it.agent })
    }

    @Test
    fun `state-corruption agent (registry returns null) is removed from positions but no event fires`() {
        val agentId = AgentId(UUID.randomUUID())
        val state = stateWith(agentId, hp = 0, atNode = nodeAId)
        val agents = StubRegistry(returnNull = true)

        val (next, events) = processDeaths(state, balance(), agents, StubEquipmentStore(), StubGroundItemStore(), tick = 1)

        assertTrue(agentId !in next.positions, "position cleared so the same row doesn't loop forever")
        assertEquals(emptyList(), events, "no AgentDied event for an agent we couldn't penalize")
    }

    @Test
    fun `unpositioned agent at HP=0 is not swept (already dead, awaiting respawn)`() {
        val agentId = AgentId(UUID.randomUUID())
        val state = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeAId to nodeA),
            positions = emptyMap(),
            bodies = mapOf(agentId to bodyAt(0)),
            inventories = emptyMap(),
        )
        val agents = StubRegistry()

        val (next, events) = processDeaths(state, balance(), agents, StubEquipmentStore(), StubGroundItemStore(), tick = 1)

        assertEquals(state, next)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `kill-streak triggers stackable drop and emits paired ground-item event`() {
        val agentId = AgentId(UUID.fromString("00000000-0000-0000-0000-000000000010"))
        val wood = ItemId("WOOD")
        val state = stateWith(agentId, hp = 0, atNode = nodeAId).copy(
            inventories = mapOf(agentId to AgentInventory(mapOf(wood to 50))),
            killStreaks = mapOf(agentId to AgentKillStreak(killCount = 10, windowStartTick = 0L)),
        )
        val agents = StubRegistry(scripted(agentId, xpLost = 25))
        val groundItems = StubGroundItemStore()
        // Seeded RNG: at killCount=10, dropChance=1.0, so the first nextDouble always
        // clears it. Pool has one entry (the WOOD stack), so nextInt(1) = 0 picks it.
        val rng = Random(seed = 42)

        val (next, events) = processDeaths(
            state, balance(), agents, StubEquipmentStore(), groundItems,
            tick = 100L, rng = rng,
        )

        val died = assertIs<WorldEvent.AgentDied>(events.first())
        val droppedOnGround = assertIs<WorldEvent.ItemDroppedOnGround>(events[1])
        val drop = assertIs<DroppedItemView.Stackable>(died.droppedItem)
        assertEquals(wood, drop.item)
        assertEquals(50, drop.quantity, "whole stack drops on a successful roll")
        assertEquals(drop.dropId, droppedOnGround.drop.dropId, "AgentDied + ItemDroppedOnGround share dropId")
        assertEquals(nodeAId, droppedOnGround.at)
        assertEquals(agentId, droppedOnGround.byAgent)
        assertEquals(1, groundItems.deposits.size)
        assertEquals(nodeAId, groundItems.deposits.single().first)
        assertEquals(0, next.inventoryOf(agentId).quantityOf(wood), "stack removed from inventory")
        assertEquals(AgentKillStreak.EMPTY, next.killStreakOf(agentId), "streak resets after death")
    }

    @Test
    fun `kill-streak window expired — drop does not fire`() {
        val agentId = AgentId(UUID.randomUUID())
        val wood = ItemId("WOOD")
        // killCount=10 but windowStartTick=0 and current tick > windowTicks (1000).
        val state = stateWith(agentId, hp = 0, atNode = nodeAId).copy(
            inventories = mapOf(agentId to AgentInventory(mapOf(wood to 5))),
            killStreaks = mapOf(agentId to AgentKillStreak(killCount = 10, windowStartTick = 0L)),
        )
        val agents = StubRegistry(scripted(agentId, xpLost = 25))
        val groundItems = StubGroundItemStore()

        val (next, events) = processDeaths(
            state, balance(), agents, StubEquipmentStore(), groundItems,
            tick = 5_000L, rng = Random(seed = 1),
        )

        val died = assertIs<WorldEvent.AgentDied>(events.single())
        assertNull(died.droppedItem, "expired window → effective kills 0 → no drop")
        assertEquals(emptyList(), groundItems.deposits)
        assertEquals(5, next.inventoryOf(agentId).quantityOf(wood), "inventory untouched")
    }

    @Test
    fun `equipment-only agent drops an equipment instance and the store deletes it`() {
        val agentId = AgentId(UUID.randomUUID())
        val instance = EquipmentInstance(
            instanceId = UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
            agentId = agentId,
            itemId = ItemId("IRON_SWORD"),
            rarity = Rarity.RARE,
            durabilityCurrent = 80,
            durabilityMax = 100,
            creatorAgentId = null,
            createdAtTick = 5L,
            equippedInSlot = EquipSlot.MAIN_HAND,
        )
        val state = stateWith(agentId, hp = 0, atNode = nodeAId).copy(
            killStreaks = mapOf(agentId to AgentKillStreak(killCount = 10, windowStartTick = 0L)),
        )
        val agents = StubRegistry(scripted(agentId, xpLost = 25))
        val equipment = StubEquipmentStore(equippedByAgent = mapOf(agentId to mapOf(EquipSlot.MAIN_HAND to instance)))
        val groundItems = StubGroundItemStore()

        val (_, events) = processDeaths(
            state, balance(), agents, equipment, groundItems,
            tick = 100L, rng = Random(seed = 7),
        )

        val died = assertIs<WorldEvent.AgentDied>(events.first())
        val drop = assertIs<DroppedItemView.Equipment>(died.droppedItem)
        assertEquals(instance.instanceId, drop.instanceId)
        assertEquals(Rarity.RARE, drop.rarity)
        assertEquals(80, drop.durabilityCurrent)
        assertEquals(100, drop.durabilityMax)
        assertEquals(listOf(instance.instanceId), equipment.deletedInstanceIds, "equipment store deleted the instance")
    }

    @Test
    fun `empty inventory and no equipment — drop roll succeeds but no event added`() {
        val agentId = AgentId(UUID.randomUUID())
        val state = stateWith(agentId, hp = 0, atNode = nodeAId).copy(
            killStreaks = mapOf(agentId to AgentKillStreak(killCount = 10, windowStartTick = 0L)),
        )
        val agents = StubRegistry(scripted(agentId, xpLost = 25))
        val groundItems = StubGroundItemStore()

        val (next, events) = processDeaths(
            state, balance(), agents, StubEquipmentStore(), groundItems,
            tick = 50L, rng = Random(seed = 1),
        )

        val died = assertIs<WorldEvent.AgentDied>(events.single())
        assertNull(died.droppedItem, "roll succeeded but pool empty → graceful no-drop")
        assertEquals(emptyList(), groundItems.deposits)
        assertEquals(AgentKillStreak.EMPTY, next.killStreakOf(agentId), "streak still resets on death even when no drop")
    }

    private fun stateWith(agentId: AgentId, hp: Int, atNode: NodeId): WorldState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeAId to nodeA, nodeBId to nodeB),
        positions = mapOf(agentId to atNode),
        bodies = mapOf(agentId to bodyAt(hp)),
        inventories = emptyMap(),
    )

    private fun bodyAt(hp: Int) = AgentBody(
        hp = hp, maxHp = 100,
        stamina = 0, maxStamina = 100,
        mana = 0, maxMana = 0,
    )

    private fun scripted(agentId: AgentId, xpLost: Int): Map<AgentId, DeathPenaltyOutcome> = mapOf(
        agentId to DeathPenaltyOutcome(xpLost = xpLost, deleveled = false, attributePointLost = null),
    )

    private fun balance(xpLoss: Int = 25): BalanceLookup = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain) = 1
        override fun staminaRegenPerTick(climate: Climate) = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 5
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 25
        override fun starvationDamagePerTick(): Int = 1
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 25
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun xpLossOnDeath(): Int = xpLoss
        override fun killStreakWindowTicks(): Long = 1000L
        override fun dropChanceForKillCount(killCount: Int): Double = (killCount * 0.1).coerceIn(0.0, 1.0)
    }

    private class StubRegistry(
        private val scriptedOutcomes: Map<AgentId, DeathPenaltyOutcome> = emptyMap(),
        private val returnNull: Boolean = false,
    ) : AgentRegistry {
        override fun find(id: AgentId): Agent? = error("not used")
        override fun listForOwner(owner: PlayerId): List<Agent> = error("not used")

        override fun applyDeathPenalty(agentId: AgentId, xpLossOnDeath: Int): DeathPenaltyOutcome? {
            if (returnNull) return null
            return scriptedOutcomes[agentId]
                ?: error("no scripted outcome for $agentId — test setup mismatch")
        }
    }

    private class StubEquipmentStore(
        private val equippedByAgent: Map<AgentId, Map<EquipSlot, EquipmentInstance>> = emptyMap(),
    ) : EquipmentInstanceStore {
        val deletedInstanceIds: MutableList<UUID> = mutableListOf()

        override fun insert(instance: EquipmentInstance) = error("not used")
        override fun findById(instanceId: UUID): EquipmentInstance? = error("not used")
        override fun listByAgent(agentId: AgentId): List<EquipmentInstance> = error("not used")
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> =
            equippedByAgent[agentId] ?: emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): EquipmentInstance? =
            error("not used")
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): EquipmentInstance? = error("not used")
        override fun decrementDurability(instanceId: UUID, amount: Int): EquipmentInstance? = error("not used")
        override fun delete(instanceId: UUID): Boolean {
            deletedInstanceIds += instanceId
            return true
        }
    }

    private class StubGroundItemStore : GroundItemStore {
        val deposits: MutableList<Pair<NodeId, DroppedItemView>> = mutableListOf()

        override fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long) {
            deposits += node to drop
        }
        override fun atNode(node: NodeId): List<GroundItemView> = error("not used")
        override fun take(node: NodeId, dropId: UUID): GroundItemView? = error("not used")
    }
}
