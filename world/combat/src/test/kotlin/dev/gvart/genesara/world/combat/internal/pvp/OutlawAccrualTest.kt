package dev.gvart.genesara.world.combat.internal.pvp

import arrow.core.Either
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
import dev.gvart.genesara.player.MisconductOutcome
import dev.gvart.genesara.player.OutlawState
import dev.gvart.genesara.player.PassiveAuraAggregator.Companion.NoAura
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
import dev.gvart.genesara.world.Gauge
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
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.combat.internal.combat.reduceAttack
import dev.gvart.genesara.world.commands.CombatCommand
import dev.gvart.genesara.world.events.SocialEvent
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.context.ApplicationEventPublisher

class OutlawAccrualTest {

    private val attacker = AgentId(UUID.randomUUID())
    private val victim = AgentId(UUID.randomUUID())

    private val regionId = RegionId(1L)
    private val openNodeId = NodeId(1L)
    private val greenNodeId = NodeId(2L)
    private val region = Region(
        id = regionId, worldId = WorldId(1L), sphereIndex = 0,
        biome = Biome.PLAINS, climate = Climate.OCEANIC,
        centroid = Vec3(0.0, 0.0, 1.0), faceVertices = emptyList(), neighbors = emptySet(),
    )
    private val openNode = Node(openNodeId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())
    private val greenNode = Node(
        greenNodeId, regionId, q = 1, r = 0, terrain = Terrain.PLAINS,
        adjacency = emptySet(), pvpEnabled = false,
    )

    private val rustySword = ItemId("RUSTY_SWORD")
    private val swordSkill = SkillId("SWORD")

    @Test
    fun `green-zone targeted attack is rejected before any side-effect`() {
        val agents = RecordingAgents(victimFame = 50)
        val result = run(
            state = state(victimHp = 100, attackerAt = openNodeId, victimAt = greenNodeId),
            agents = agents,
        )

        val rejection = (result as Either.Left).value
        assertTrue(rejection is WorldRejection.GreenZone, "expected GreenZone, got $rejection")
        assertEquals(greenNodeId, rejection.node)
        assertEquals(0, agents.misconductCalls, "rejected attack must not write misconduct")
    }

    @Test
    fun `kill against Fame-protected victim raises misconduct and emits OutlawStateChanged on transition`() {
        val agents = RecordingAgents(
            victimFame = 50,
            scriptedDeath = mapOf(victim to DeathPenaltyOutcome(0, false, null)),
            scriptedAdjustment = MisconductOutcome(
                attacker, oldScore = 0, newScore = 50,
                oldState = OutlawState.CLEAN, newState = OutlawState.WATCHED,
            ),
        )
        val result = run(
            state = state(victimHp = 1, attackerAt = openNodeId, victimAt = openNodeId),
            agents = agents,
        )

        val (_, events) = (result as Either.Right).value
        val transition = events.filterIsInstance<SocialEvent.OutlawStateChanged>().single()
        assertEquals(attacker, transition.agent)
        assertEquals(OutlawState.CLEAN, transition.previousState)
        assertEquals(OutlawState.WATCHED, transition.newState)
        assertEquals(setOf(attacker), transition.listeners)
        assertEquals(50, agents.lastDelta, "kill delta routed through balance.outlawMisconductOnKillProtected")
    }

    @Test
    fun `non-lethal hit against Fame-protected victim uses the smaller delta`() {
        val agents = RecordingAgents(
            victimFame = 50,
            scriptedAdjustment = MisconductOutcome(
                attacker, oldScore = 0, newScore = 5,
                oldState = OutlawState.CLEAN, newState = OutlawState.CLEAN,
            ),
        )
        run(
            state = state(victimHp = 1_000, attackerAt = openNodeId, victimAt = openNodeId),
            agents = agents,
        )

        assertEquals(5, agents.lastDelta, "non-lethal delta routed through balance.outlawMisconductOnAttackProtected")
    }

    @Test
    fun `attack against low-Fame victim is fully suppressed — no misconduct write`() {
        val agents = RecordingAgents(victimFame = 0)
        run(
            state = state(victimHp = 1_000, attackerAt = openNodeId, victimAt = openNodeId),
            agents = agents,
        )

        assertEquals(0, agents.misconductCalls, "Fame-protection threshold gates accrual as well as the cascade")
    }

    @Test
    fun `kill against low-Fame victim is also suppressed — gate holds on the lethal branch`() {
        val agents = RecordingAgents(
            victimFame = 0,
            scriptedDeath = mapOf(victim to DeathPenaltyOutcome(0, false, null)),
        )
        run(
            state = state(victimHp = 1, attackerAt = openNodeId, victimAt = openNodeId),
            agents = agents,
        )

        assertEquals(0, agents.misconductCalls, "kill-on-nobody must not accrue misconduct — symmetric with cascade suppression")
    }

    @Test
    fun `same-bucket misconduct write does not emit a transition event`() {
        val agents = RecordingAgents(
            victimFame = 50,
            scriptedAdjustment = MisconductOutcome(
                attacker, oldScore = 5, newScore = 10,
                oldState = OutlawState.CLEAN, newState = OutlawState.CLEAN,
            ),
        )
        val result = run(
            state = state(victimHp = 1_000, attackerAt = openNodeId, victimAt = openNodeId),
            agents = agents,
        )

        val (_, events) = (result as Either.Right).value
        assertNull(events.filterIsInstance<SocialEvent.OutlawStateChanged>().firstOrNull())
    }

    private fun run(
        state: WorldState,
        agents: RecordingAgents,
    ): Either<WorldRejection, Pair<WorldState, List<WorldEvent>>> {
        val skills = StubSkillsRegistry()
        val publisher = NoopPublisher()
        return reduceAttack(
            combat = state.combat,
            bodyView = state.body,
            coreView = state.core,
            envView = state.environment,
            command = CombatCommand.AttackTarget(attacker, victim),
            balance = balance,
            items = itemsWithSword,
            agents = agents,
            equipment = swordEquipped,
            progression = SkillProgression(skills, publisher),
            scaling = NoScaling,
            passiveAura = NoAura,
            equipmentBonuses = EquipmentBonusAggregator.NoBonuses,
            deathProcessor = DeathProcessor(
                balance = balance,
                agents = agents,
                equipment = swordEquipped,
                groundItems = NoopGroundItemStore(),
            ),
            triggeredPassives = NoOpTriggeredPassiveDispatcher,
            pendingScales = InMemoryPendingAttackScaleStore(),
            behaviorTracker = InMemoryBehaviorTracker(),
            relationships = RelationshipsGateway.NoOp,
            rng = Random(seed = 42L),
            tick = 5L,
        ).map { out -> state.copy(combat = out.sliceDelta).applyEffects(out.effects) to out.events }
    }

    private fun state(
        victimHp: Int,
        attackerAt: NodeId,
        victimAt: NodeId,
    ): WorldState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(openNodeId to openNode, greenNodeId to greenNode),
        positions = mapOf(attacker to attackerAt, victim to victimAt),
        bodies = mapOf(
            attacker to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
            victim to AgentBody(hp = victimHp, maxHp = victimHp.coerceAtLeast(100), stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
        ),
        inventories = emptyMap(),
    )

    private val balance: BalanceLookup = object : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int = 1
        override fun staminaRegenPerTick(climate: Climate): Int = 0
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
        override fun dropChanceForKillCount(killCount: Int): Double = 0.0
        override fun fameWitnessProtectionThreshold(): Int = 10
        override fun outlawMisconductOnAttackProtected(): Int = 5
        override fun outlawMisconductOnKillProtected(): Int = 50
        override fun outlawWatchedScore(): Int = 25
        override fun outlawOutlawScore(): Int = 100
    }

    private val itemsWithSword: ItemLookup = StubItemLookup(
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

    private val swordEquipped: AgentItemInstancesStore = StubEquipmentStore(
        mapOf(
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

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private class StubEquipmentStore(
        private val equipped: Map<AgentId, Map<EquipSlot, ItemInstance.Equipment>>,
    ) : InMemoryAgentItemInstancesStore() {
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, ItemInstance.Equipment> =
            equipped[agentId] ?: emptyMap()
    }

    private inner class RecordingAgents(
        private val victimFame: Int,
        private val scriptedDeath: Map<AgentId, DeathPenaltyOutcome> = emptyMap(),
        private val scriptedAdjustment: MisconductOutcome? = null,
    ) : AgentRegistry {
        var misconductCalls: Int = 0
            private set
        var lastDelta: Int? = null
            private set

        override fun find(id: AgentId): Agent? = Agent(
            id = id, owner = PlayerId(UUID.randomUUID()), name = "test",
            attributes = AgentAttributes(strength = 5, constitution = 1),
            fame = if (id == victim) victimFame else 0,
        )

        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()

        override fun applyDeathPenalty(agentId: AgentId, xpLossOnDeath: Int): DeathPenaltyOutcome? =
            scriptedDeath[agentId]

        override fun adjustMisconduct(
            agentId: AgentId,
            delta: Int,
            watchedAt: Int,
            outlawAt: Int,
        ): MisconductOutcome? {
            misconductCalls++
            lastDelta = delta
            return scriptedAdjustment?.also { assertEquals(attacker, agentId, "accrual must target the attacker") }
        }
    }

    private class StubSkillsRegistry : AgentSkillsRegistry {
        override fun snapshot(agent: AgentId): AgentSkillsSnapshot = AgentSkillsSnapshot(emptyMap(), 0, 0)
        override fun slottedSkillLevel(agent: AgentId, skill: SkillId): Int = 0
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult = AddXpResult.Unslotted
        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? = null
        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private class NoopPublisher : ApplicationEventPublisher {
        override fun publishEvent(event: Any) = Unit
    }

    private class NoopGroundItemStore : dev.gvart.genesara.world.GroundItemStore {
        override fun deposit(node: NodeId, drop: dev.gvart.genesara.world.DroppedItemView, droppedAtTick: Long) = Unit
        override fun atNode(node: NodeId): List<dev.gvart.genesara.world.GroundItemView> = emptyList()
        override fun take(node: NodeId, dropId: UUID): dev.gvart.genesara.world.GroundItemView? = null
    }
}
