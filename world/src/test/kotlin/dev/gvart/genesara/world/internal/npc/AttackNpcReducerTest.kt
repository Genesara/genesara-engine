package dev.gvart.genesara.world.internal.npc

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.DeathPenaltyOutcome
import dev.gvart.genesara.player.LevelScalingAggregator.Companion.NoScaling
import dev.gvart.genesara.player.PassiveAuraAggregator.Companion.NoAura
import dev.gvart.genesara.player.ScalingEffect
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.AggressionProfile
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentBonusAggregator
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.GroundItemView
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
import dev.gvart.genesara.world.ItemLookup
import dev.gvart.genesara.world.LootEntry
import dev.gvart.genesara.world.LootTableCatalog
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Npc
import dev.gvart.genesara.world.NpcCatalog
import dev.gvart.genesara.world.NpcDef
import dev.gvart.genesara.world.NpcId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.Region
import dev.gvart.genesara.world.RegionId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.Vec3
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.commands.CombatCommand
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.balance.RarityRoller
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import dev.gvart.genesara.world.internal.testsupport.InMemoryPendingAttackScaleStore
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class AttackNpcReducerTest {

    private val attacker = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeAId = NodeId(1L)
    private val nodeBId = NodeId(2L)
    private val nodeCId = NodeId(3L)
    private val npcId = NpcId(UUID.randomUUID())
    private val wolfType = NpcType("GRAY_WOLF")
    private val rabbitType = NpcType("RABBIT")

    private val region = Region(
        id = regionId,
        worldId = WorldId(1L),
        sphereIndex = 0,
        biome = Biome.FOREST,
        climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0),
        faceVertices = emptyList(),
        neighbors = emptySet(),
    )

    @Test
    fun `killing blow accrues HUNTING xp on top of weapon-skill xp`() {
        val state = battleState(npcHp = 1, npcDef = hostileWolf(range = 1))
        val skills = RecordingSkills()
        val publisher = RecordingPublisher()
        val result = reduceAttackNpc(
            state = state,
            command = CombatCommand.AttackNpc(attacker, npcId),
            balance = balance(),
            items = itemsWithSword(),
            agents = singleAttacker(strength = 10),
            equipment = swordEquipped(),
            progression = SkillProgression(skills, publisher),
            scaling = NoScaling,
            passiveAura = NoAura,
            equipmentBonuses = EquipmentBonusAggregator.NoBonuses,
            pendingScales = InMemoryPendingAttackScaleStore(),
            behaviorTracker = InMemoryBehaviorTracker(),
            catalog = catalogFor(hostileWolf(range = 1)),
            lootRoll = noOpLootRoll(),
            rng = Random(0L),
            tick = 5L,
        ).getOrNull()
        assertNotNull(result)

        val swordXp = skills.xpAddCalls.filter { it.first == SkillId("SWORD") }
        val huntingXp = skills.xpAddCalls.filter { it.first == SkillId("HUNTING") }
        assertEquals(2, swordXp.size, "per-swing XP + npc-kill bonus XP")
        assertEquals(1, huntingXp.size, "killing blow accrues HUNTING xp once")
        assertEquals(5, huntingXp.single().second, "HUNTING xp matches huntingKillXp default")
    }

    @Test
    fun `non-killing strike does NOT accrue HUNTING xp`() {
        val state = battleState(npcHp = 100, npcDef = hostileWolf(range = 1))
        val skills = RecordingSkills()
        val publisher = RecordingPublisher()
        reduceAttackNpc(
            state = state,
            command = CombatCommand.AttackNpc(attacker, npcId),
            balance = balance(),
            items = itemsWithSword(),
            agents = singleAttacker(strength = 1),
            equipment = swordEquipped(),
            progression = SkillProgression(skills, publisher),
            scaling = NoScaling,
            passiveAura = NoAura,
            equipmentBonuses = EquipmentBonusAggregator.NoBonuses,
            pendingScales = InMemoryPendingAttackScaleStore(),
            behaviorTracker = InMemoryBehaviorTracker(),
            catalog = catalogFor(hostileWolf(range = 1)),
            lootRoll = noOpLootRoll(),
            rng = Random(0L),
            tick = 5L,
        ).getOrNull()

        assertTrue(skills.xpAddCalls.none { it.first == SkillId("HUNTING") })
    }

    @Test
    fun `PASSIVE flee with fleeDistance=2 picks a node two hops from origin`() {
        val rabbit = passiveRabbit(fleeDistance = 2)
        // Chain A — B — C — D, rabbit on B. Attacker on A (range = 2 reaches B).
        val nodeDId = NodeId(4L)
        val nodes = mapOf(
            nodeAId to Node(nodeAId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeBId)),
            nodeBId to Node(nodeBId, regionId, q = 1, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeAId, nodeCId)),
            nodeCId to Node(nodeCId, regionId, q = 2, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeBId, nodeDId)),
            nodeDId to Node(nodeDId, regionId, q = 3, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeCId)),
        )
        val npc = Npc(
            id = npcId, type = rabbit.type, nodeId = nodeBId, spawnNodeId = nodeBId,
            hpCurrent = 100, hpMax = 100, spawnedAtTick = 0L, lastAttackTick = 0L,
        )
        val state = WorldState(
            regions = mapOf(regionId to region),
            nodes = nodes,
            positions = mapOf(attacker to nodeAId),
            bodies = mapOf(attacker to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0)),
            inventories = emptyMap(),
            npcs = mapOf(npcId to npc),
        )
        val skills = RecordingSkills()
        val publisher = RecordingPublisher()

        val (_, events) = assertNotNull(
            reduceAttackNpc(
                state = state,
                command = CombatCommand.AttackNpc(attacker, npcId),
                balance = balance(),
                items = itemsWithBow(),
                agents = singleAttacker(strength = 0, dexterity = 1),
                equipment = bowEquipped(),
                progression = SkillProgression(skills, publisher),
                scaling = NoScaling,
                passiveAura = NoAura,
                equipmentBonuses = EquipmentBonusAggregator.NoBonuses,
                pendingScales = InMemoryPendingAttackScaleStore(),
                behaviorTracker = InMemoryBehaviorTracker(),
                catalog = catalogFor(rabbit),
                lootRoll = noOpLootRoll(),
                rng = Random(0L),
                tick = 5L,
            ).getOrNull(),
        )

        val moved = events.filterIsInstance<EnvironmentEvent.NpcMoved>().single()
        // fleeDistance=2 reaches {C, D} from origin B (excluding attacker A and origin B).
        assertTrue(
            moved.to == nodeCId || moved.to == nodeDId,
            "PASSIVE flee with fleeDistance=2 should reach a node 1–2 hops from B (got ${moved.to})",
        )
    }

    @Test
    fun `PASSIVE flee never routes through the attacker node`() {
        // Topology: A — B(rabbit) — X(attacker) — Z. With fleeDistance=2 and
        // routing-through-attacker semantics, Z would be reachable from B via X
        // and could be picked as the flee destination. Avoidance semantics
        // forbid that: only A is in the candidate set, so flee must land on A.
        val rabbit = passiveRabbit(fleeDistance = 2)
        val nodeZId = NodeId(5L)
        val nodes = mapOf(
            nodeAId to Node(nodeAId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeBId)),
            nodeBId to Node(nodeBId, regionId, q = 1, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeAId, nodeCId)),
            nodeCId to Node(nodeCId, regionId, q = 2, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeBId, nodeZId)),
            nodeZId to Node(nodeZId, regionId, q = 3, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeCId)),
        )
        val npc = Npc(
            id = npcId, type = rabbit.type, nodeId = nodeBId, spawnNodeId = nodeBId,
            hpCurrent = 100, hpMax = 100, spawnedAtTick = 0L, lastAttackTick = 0L,
        )
        val state = WorldState(
            regions = mapOf(regionId to region),
            nodes = nodes,
            positions = mapOf(attacker to nodeCId),
            bodies = mapOf(attacker to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0)),
            inventories = emptyMap(),
            npcs = mapOf(npcId to npc),
        )
        val skills = RecordingSkills()
        val publisher = RecordingPublisher()

        val (_, events) = assertNotNull(
            reduceAttackNpc(
                state = state,
                command = CombatCommand.AttackNpc(attacker, npcId),
                balance = balance(),
                items = itemsWithBow(),
                agents = singleAttacker(strength = 0, dexterity = 1),
                equipment = bowEquipped(),
                progression = SkillProgression(skills, publisher),
                scaling = NoScaling,
                passiveAura = NoAura,
                equipmentBonuses = EquipmentBonusAggregator.NoBonuses,
                pendingScales = InMemoryPendingAttackScaleStore(),
                behaviorTracker = InMemoryBehaviorTracker(),
                catalog = catalogFor(rabbit),
                lootRoll = noOpLootRoll(),
                rng = Random(0L),
                tick = 5L,
            ).getOrNull(),
        )

        val moved = events.filterIsInstance<EnvironmentEvent.NpcMoved>().single()
        assertEquals(nodeAId, moved.to, "flee must avoid routing through attacker on $nodeCId — only $nodeAId is reachable")
    }

    @Test
    fun `PASSIVE flee with fleeDistance=1 stays in the adjacent neighbour set`() {
        val rabbit = passiveRabbit(fleeDistance = 1)
        val nodeDId = NodeId(4L)
        val nodes = mapOf(
            nodeAId to Node(nodeAId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeBId)),
            nodeBId to Node(nodeBId, regionId, q = 1, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeAId, nodeCId)),
            nodeCId to Node(nodeCId, regionId, q = 2, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeBId, nodeDId)),
            nodeDId to Node(nodeDId, regionId, q = 3, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeCId)),
        )
        val npc = Npc(
            id = npcId, type = rabbit.type, nodeId = nodeBId, spawnNodeId = nodeBId,
            hpCurrent = 100, hpMax = 100, spawnedAtTick = 0L, lastAttackTick = 0L,
        )
        val state = WorldState(
            regions = mapOf(regionId to region),
            nodes = nodes,
            positions = mapOf(attacker to nodeAId),
            bodies = mapOf(attacker to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0)),
            inventories = emptyMap(),
            npcs = mapOf(npcId to npc),
        )
        val skills = RecordingSkills()
        val publisher = RecordingPublisher()

        val (_, events) = assertNotNull(
            reduceAttackNpc(
                state = state,
                command = CombatCommand.AttackNpc(attacker, npcId),
                balance = balance(),
                items = itemsWithBow(),
                agents = singleAttacker(strength = 0, dexterity = 1),
                equipment = bowEquipped(),
                progression = SkillProgression(skills, publisher),
                scaling = NoScaling,
                passiveAura = NoAura,
                equipmentBonuses = EquipmentBonusAggregator.NoBonuses,
                pendingScales = InMemoryPendingAttackScaleStore(),
                behaviorTracker = InMemoryBehaviorTracker(),
                catalog = catalogFor(rabbit),
                lootRoll = noOpLootRoll(),
                rng = Random(0L),
                tick = 5L,
            ).getOrNull(),
        )

        val moved = events.filterIsInstance<EnvironmentEvent.NpcMoved>().single()
        assertEquals(nodeCId, moved.to, "fleeDistance=1 from B excluding attacker A leaves only C")
    }

    private fun battleState(npcHp: Int, npcDef: NpcDef): WorldState {
        val a = Node(nodeAId, regionId, q = 0, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeBId))
        val b = Node(nodeBId, regionId, q = 1, r = 0, terrain = Terrain.FOREST, adjacency = setOf(nodeAId))
        val npc = Npc(
            id = npcId, type = npcDef.type, nodeId = nodeAId, spawnNodeId = nodeAId,
            hpCurrent = npcHp, hpMax = npcDef.hpMax, spawnedAtTick = 0L, lastAttackTick = 0L,
        )
        return WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeAId to a, nodeBId to b),
            positions = mapOf(attacker to nodeAId),
            bodies = mapOf(attacker to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0)),
            inventories = emptyMap(),
            npcs = mapOf(npcId to npc),
        )
    }

    private fun hostileWolf(range: Int): NpcDef = NpcDef(
        type = wolfType,
        displayName = "Gray Wolf",
        hpMax = 30,
        damage = 1,
        damageType = DamageType.PIERCE,
        range = range,
        attackIntervalTicks = 4,
        defense = 0,
        dodgeChancePercent = 0,
        aggressionProfile = AggressionProfile.HOSTILE,
        territoryRadius = 0,
        spawnBiomes = setOf(Biome.FOREST),
        spawnWeight = 1,
        fleeDistance = 1,
    )

    private fun passiveRabbit(fleeDistance: Int): NpcDef = NpcDef(
        type = rabbitType,
        displayName = "Rabbit",
        hpMax = 100,
        damage = 0,
        damageType = DamageType.BLUNT,
        range = 1,
        attackIntervalTicks = 4,
        defense = 0,
        dodgeChancePercent = 0,
        aggressionProfile = AggressionProfile.PASSIVE,
        territoryRadius = 0,
        spawnBiomes = setOf(Biome.FOREST),
        spawnWeight = 1,
        fleeDistance = fleeDistance,
    )

    private fun catalogFor(vararg defs: NpcDef): NpcCatalog {
        val byType = defs.associateBy { it.type }
        return object : NpcCatalog {
            override fun byType(type: NpcType): NpcDef? = byType[type]
            override fun all(): Collection<NpcDef> = byType.values
            override fun byBiome(biome: Biome): List<NpcDef> = byType.values.filter { biome in it.spawnBiomes }
        }
    }

    private fun noOpLootRoll(): LootRoll = LootRoll(
        lootTables = object : LootTableCatalog {
            override fun byMob(mob: NpcType): List<LootEntry> = emptyList()
            override fun allMobs(): Set<NpcType> = emptySet()
        },
        items = object : ItemLookup {
            override fun byId(id: ItemId): Item? = null
            override fun all(): List<Item> = emptyList()
        },
        groundItems = object : GroundItemStore {
            override fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long) = Unit
            override fun atNode(node: NodeId): List<GroundItemView> = emptyList()
            override fun take(node: NodeId, dropId: UUID): GroundItemView? = null
        },
        rarityRoller = RarityRoller(Random(0L)),
    )

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

    private fun itemsWithSword(): ItemLookup = StubItemLookup(
        mapOf(
            ItemId("RUSTY_SWORD") to Item(
                id = ItemId("RUSTY_SWORD"),
                displayName = "Rusty Sword",
                description = "",
                category = ItemCategory.EQUIPMENT,
                weightPerUnit = 1500,
                maxStack = 1,
                damageType = DamageType.SLASH,
                weaponPower = 8,
                combatSkill = SkillId("SWORD"),
                validSlots = setOf(EquipSlot.MAIN_HAND),
            ),
        ),
    )

    private fun itemsWithBow(): ItemLookup = StubItemLookup(
        mapOf(
            ItemId("WOODEN_BOW") to Item(
                id = ItemId("WOODEN_BOW"),
                displayName = "Wooden Bow",
                description = "",
                category = ItemCategory.EQUIPMENT,
                weightPerUnit = 1500,
                maxStack = 1,
                damageType = DamageType.PIERCE,
                weaponPower = 7,
                combatSkill = SkillId("BOW"),
                range = 2,
                validSlots = setOf(EquipSlot.MAIN_HAND),
                twoHanded = true,
            ),
        ),
    )

    private fun swordEquipped(): AgentItemInstancesStore = StubEquipmentStore(
        equippedByAgent = mapOf(
            attacker to mapOf(
                EquipSlot.MAIN_HAND to ItemInstance.Equipment(
                    instanceId = UUID.randomUUID(),
                    agentId = attacker,
                    itemId = ItemId("RUSTY_SWORD"),
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

    private fun bowEquipped(): AgentItemInstancesStore = StubEquipmentStore(
        equippedByAgent = mapOf(
            attacker to mapOf(
                EquipSlot.MAIN_HAND to ItemInstance.Equipment(
                    instanceId = UUID.randomUUID(),
                    agentId = attacker,
                    itemId = ItemId("WOODEN_BOW"),
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

    private fun singleAttacker(strength: Int, dexterity: Int = 0): AgentRegistry =
        StubAgentRegistry(
            byId = mapOf(attacker to AgentAttributes(strength = strength, dexterity = dexterity)),
        )

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private class StubAgentRegistry(
        private val byId: Map<AgentId, AgentAttributes>,
    ) : AgentRegistry {
        override fun find(id: AgentId): Agent? = byId[id]?.let {
            Agent(id = id, owner = PlayerId(UUID.randomUUID()), name = "test", attributes = it)
        }
        override fun listForOwner(owner: PlayerId): List<Agent> = error("not used")
        override fun applyDeathPenalty(agentId: AgentId, xpLossOnDeath: Int): DeathPenaltyOutcome? = null
    }

    private class StubEquipmentStore(
        private val equippedByAgent: Map<AgentId, Map<EquipSlot, ItemInstance.Equipment>> = emptyMap(),
    ) : dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore() {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> =
            equippedByAgent[agentId] ?: emptyMap()
        override fun assignToSlot(instanceId: UUID, agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? =
            error("not used")
        override fun clearSlot(agentId: AgentId, slot: EquipSlot): ItemInstance.Equipment? = error("not used")
        override fun decrementDurability(instanceId: UUID, amount: Int): ItemInstance.Equipment? = error("not used")
        override fun delete(instanceId: UUID): Boolean = true
    }

    private class RecordingSkills : AgentSkillsRegistry {
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
        override fun publishEvent(event: Any) { events += event }
    }
}
