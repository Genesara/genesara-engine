package dev.gvart.genesara.world.combat.internal.combat

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
import dev.gvart.genesara.player.RelationshipAdjustmentOutcome
import dev.gvart.genesara.player.RelationshipRow
import dev.gvart.genesara.player.RelationshipsGateway
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.EquipmentBonusAggregator
import dev.gvart.genesara.world.Item
import dev.gvart.genesara.world.ItemCategory
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ItemInstance
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
import dev.gvart.genesara.world.commands.CombatCommand
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import dev.gvart.genesara.world.internal.testsupport.InMemoryPendingAttackScaleStore
import dev.gvart.genesara.world.internal.testsupport.NoOpTriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.worldstate.WorldState
import dev.gvart.genesara.world.internal.worldstate.applyEffects
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore

class WitnessCascadeTest {

    private val attacker = AgentId(UUID.randomUUID())
    private val victim = AgentId(UUID.randomUUID())
    private val witnessA = AgentId(UUID.randomUUID())
    private val witnessB = AgentId(UUID.randomUUID())
    private val offNode = AgentId(UUID.randomUUID())

    private val regionId = RegionId(1L)
    private val combatNodeId = NodeId(1L)
    private val offNodeId = NodeId(2L)
    private val region = Region(
        id = regionId, worldId = WorldId(1L), sphereIndex = 0,
        biome = Biome.PLAINS, climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
    )
    private val combatNode = Node(combatNodeId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())
    private val otherNode = Node(offNodeId, regionId, q = 1, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())

    private val rustySword = ItemId("RUSTY_SWORD")
    private val swordSkill = SkillId("SWORD")

    private fun reduceAttack(
        state: WorldState,
        command: CombatCommand.AttackTarget,
        balance: BalanceLookup,
        items: ItemLookup,
        agents: AgentRegistry,
        equipment: AgentItemInstancesStore,
        progression: SkillProgression,
        scaling: dev.gvart.genesara.player.LevelScalingAggregator = NoScaling,
        passiveAura: dev.gvart.genesara.player.PassiveAuraAggregator = NoAura,
        equipmentBonuses: EquipmentBonusAggregator = EquipmentBonusAggregator.NoBonuses,
        deathProcessor: DeathProcessor,
        triggeredPassives: dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher,
        pendingScales: dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore,
        behaviorTracker: dev.gvart.genesara.world.internal.behavior.BehaviorTracker,
        relationships: RelationshipsGateway = RelationshipsGateway.NoOp,
        rng: Random,
        tick: Long,
    ): arrow.core.Either<dev.gvart.genesara.world.WorldRejection, Pair<WorldState, List<dev.gvart.genesara.world.events.WorldEvent>>> =
        dev.gvart.genesara.world.combat.internal.combat.reduceAttack(
            combat = state.combat,
            bodyView = state.body,
            coreView = state.core,
            envView = state.environment,
            command = command,
            balance = balance,
            items = items,
            agents = agents,
            equipment = equipment,
            progression = progression,
            scaling = scaling,
            passiveAura = passiveAura,
            equipmentBonuses = equipmentBonuses,
            deathProcessor = deathProcessor,
            triggeredPassives = triggeredPassives,
            pendingScales = pendingScales,
            behaviorTracker = behaviorTracker,
            relationships = relationships,
            rng = rng,
            tick = tick,
        ).map { out ->
            val next = state.copy(combat = out.sliceDelta).applyEffects(out.effects)
            next to out.events
        }

    @Test
    fun `non-lethal attack — co-located witnesses get the attack-tier delta against the attacker`() {
        val recorder = RecordingRelationships()
        val state = state(victimHp = 1_000, others = mapOf(witnessA to combatNodeId, witnessB to combatNodeId))

        runAttack(state = state, victimFame = 50, recorder = recorder)

        val batch = recorder.batches.single()
        assertEquals(attacker, batch.anchor)
        assertEquals(setOf(witnessA, witnessB), batch.others.toSet())
        assertEquals(-2, batch.delta)
    }

    @Test
    fun `lethal attack — co-located witnesses get the kill-tier delta`() {
        val recorder = RecordingRelationships()
        val state = state(victimHp = 1, others = mapOf(witnessA to combatNodeId))

        runAttack(state = state, victimFame = 50, recorder = recorder)

        val batch = recorder.batches.single()
        assertEquals(setOf(witnessA), batch.others.toSet())
        assertEquals(-10, batch.delta)
    }

    @Test
    fun `victim below Fame threshold suppresses the cascade entirely`() {
        val recorder = RecordingRelationships()
        val state = state(victimHp = 100, others = mapOf(witnessA to combatNodeId, witnessB to combatNodeId))

        runAttack(state = state, victimFame = 9, recorder = recorder)

        assertTrue(recorder.batches.isEmpty(), "low-Fame victim must not trigger relationship deltas")
    }

    @Test
    fun `attacker and victim never witness themselves`() {
        val recorder = RecordingRelationships()
        val state = state(victimHp = 100, others = emptyMap())

        runAttack(state = state, victimFame = 50, recorder = recorder)

        assertTrue(recorder.batches.isEmpty(), "no third-party bystander means no cascade")
    }

    @Test
    fun `off-node bystanders are not witnesses`() {
        val recorder = RecordingRelationships()
        val state = state(
            victimHp = 100,
            others = mapOf(witnessA to combatNodeId, offNode to offNodeId),
        )

        runAttack(state = state, victimFame = 50, recorder = recorder)

        assertEquals(setOf(witnessA), recorder.batches.single().others.toSet())
    }

    @Test
    fun `victim at exactly the threshold is protected — strict less-than rule`() {
        val recorder = RecordingRelationships()
        val state = state(victimHp = 100, others = mapOf(witnessA to combatNodeId))

        runAttack(state = state, victimFame = 10, recorder = recorder)

        assertEquals(setOf(witnessA), recorder.batches.single().others.toSet(), "Fame == threshold still triggers — only strictly below suppresses")
    }


    private fun runAttack(
        state: WorldState,
        victimFame: Int,
        recorder: RecordingRelationships,
    ) {
        val rng = Random(seed = 42L)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val agents = StubAgents(victimFame = victimFame)
        reduceAttack(
            state, CombatCommand.AttackTarget(attacker, victim),
            balance(), itemsWithSword(), agents, swordEquipped(),
            SkillProgression(skills, publisher),
            equipmentBonuses = EquipmentBonusAggregator.NoBonuses,
            deathProcessor = stubDeathProcessor(skills, publisher),
            relationships = recorder,
            rng = rng,
            scaling = NoScaling, passiveAura = NoAura,
            triggeredPassives = NoOpTriggeredPassiveDispatcher,
            pendingScales = InMemoryPendingAttackScaleStore(),
            behaviorTracker = InMemoryBehaviorTracker(),
            tick = 5L,
        )
    }

    private fun state(victimHp: Int, others: Map<AgentId, NodeId>): WorldState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(combatNodeId to combatNode, offNodeId to otherNode),
        positions = mapOf(attacker to combatNodeId, victim to combatNodeId) + others,
        bodies = buildMap {
            put(attacker, AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0))
            put(victim, AgentBody(hp = victimHp, maxHp = victimHp.coerceAtLeast(100), stamina = 50, maxStamina = 50, mana = 0, maxMana = 0))
            others.keys.forEach { put(it, AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0)) }
        },
        inventories = emptyMap(),
    )

    private fun balance(): BalanceLookup = object : BalanceLookup {
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
        override fun dropChanceForKillCount(killCount: Int): Double = 0.0
    }

    private fun itemsWithSword(): ItemLookup = StubItemLookup(
        mapOf(
            rustySword to Item(
                id = rustySword,
                displayName = "Rusty Sword",
                description = "",
                category = ItemCategory.EQUIPMENT,
                weightPerUnit = 1500,
                maxStack = 1,
                damageType = DamageType.SLASH,
                weaponPower = 100,
                combatSkill = swordSkill,
                validSlots = setOf(EquipSlot.MAIN_HAND),
            ),
        ),
    )

    private fun swordEquipped(): AgentItemInstancesStore = StubEquipmentStore(
        equippedByAgent = mapOf(
            attacker to mapOf(
                EquipSlot.MAIN_HAND to ItemInstance.Equipment(
                    instanceId = UUID.randomUUID(),
                    itemId = rustySword,
                    agentId = attacker,
                    rarity = Rarity.COMMON,
                    durabilityCurrent = 10,
                    durabilityMax = 10,
                    creatorAgentId = null,
                    createdAtTick = 0L,
                ),
            ),
        ),
    )

    private fun stubDeathProcessor(
        skills: AgentSkillsRegistry,
        publisher: ApplicationEventPublisher,
    ): DeathProcessor = DeathProcessor(
        balance = balance(),
        agents = StubAgents(scriptedDeath = mapOf(victim to DeathPenaltyOutcome(0, false, null))),
        equipment = StubEquipmentStore(),
        groundItems = StubGroundItemStore(),
    )

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private inner class StubAgents(
        private val victimFame: Int = 0,
        private val scriptedDeath: Map<AgentId, DeathPenaltyOutcome> = emptyMap(),
    ) : AgentRegistry {
        override fun find(id: AgentId): Agent? = Agent(
            id = id, owner = PlayerId(UUID.randomUUID()), name = "test",
            attributes = AgentAttributes(strength = 5, constitution = 1),
            fame = if (id == victim) victimFame else 0,
        )
        override fun listForOwner(owner: PlayerId): List<Agent> = error("not used")
        override fun applyDeathPenalty(agentId: AgentId, xpLossOnDeath: Int): DeathPenaltyOutcome? =
            scriptedDeath[agentId]
    }

    private class StubEquipmentStore(
        private val equippedByAgent: Map<AgentId, Map<EquipSlot, ItemInstance.Equipment>> = emptyMap(),
    ) : InMemoryAgentItemInstancesStore() {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> =
            equippedByAgent[agentId] ?: emptyMap()
    }

    private class StubGroundItemStore : dev.gvart.genesara.world.GroundItemStore {
        override fun deposit(node: NodeId, drop: dev.gvart.genesara.world.DroppedItemView, droppedAtTick: Long) = Unit
        override fun atNode(node: NodeId): List<dev.gvart.genesara.world.GroundItemView> = error("not used")
        override fun take(node: NodeId, dropId: UUID): dev.gvart.genesara.world.GroundItemView? = error("not used")
    }

    private class StubSkillsRegistry : AgentSkillsRegistry {
        override fun snapshot(agent: AgentId): AgentSkillsSnapshot = AgentSkillsSnapshot(emptyMap(), 0, 0)
        override fun slottedSkillLevel(agent: AgentId, skill: SkillId): Int = 0
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult = AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        override fun publishEvent(event: Any) = Unit
    }

    private data class CascadeBatch(val anchor: AgentId, val others: List<AgentId>, val delta: Int)

    private class RecordingRelationships : RelationshipsGateway {
        val batches: MutableList<CascadeBatch> = mutableListOf()
        override fun adjust(a: AgentId, b: AgentId, delta: Int, tick: Long): RelationshipAdjustmentOutcome {
            batches += CascadeBatch(a, listOf(b), delta)
            return RelationshipAdjustmentOutcome(currentScore = 0)
        }
        override fun adjustMany(anchor: AgentId, others: Collection<AgentId>, delta: Int, tick: Long) {
            batches += CascadeBatch(anchor, others.toList(), delta)
        }
        override fun find(a: AgentId, b: AgentId): RelationshipRow? = null
        override fun scoresFor(agentId: AgentId): Map<AgentId, RelationshipRow> = emptyMap()
    }
}
