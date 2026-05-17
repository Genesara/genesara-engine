package dev.gvart.genesara.world.combat.internal.combat

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.DeathPenaltyOutcome
import dev.gvart.genesara.player.LevelScalingAggregator.Companion.NoScaling
import dev.gvart.genesara.player.PassiveAuraAggregator.Companion.NoAura
import dev.gvart.genesara.player.Perk
import dev.gvart.genesara.player.PerkCooldownStore
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.player.TriggeredPassiveEffectKind
import dev.gvart.genesara.player.TriggeredPassiveLookup
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.player.TriggeredPerk
import dev.gvart.genesara.world.AgentItemInstancesStore
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.DroppedItemView
import dev.gvart.genesara.world.EquipSlot
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.GroundItemStore
import dev.gvart.genesara.world.GroundItemView
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
import dev.gvart.genesara.world.events.CombatEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.body.AgentBody
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcherImpl
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import dev.gvart.genesara.world.internal.testsupport.InMemoryPendingAttackScaleStore
import dev.gvart.genesara.world.internal.testsupport.InMemoryPerkCooldownStore
import dev.gvart.genesara.world.internal.worldstate.WorldState
import dev.gvart.genesara.world.internal.worldstate.applyEffects
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore

// Canary for issue #64 — proves the dispatcher is reachable from real reduceAttack
// wiring with the SWORD-50 Bleeder perk. Cooldown gate semantics + band-cross
// edge cases are covered by the dispatcher unit + cooldown-store integration tests;
// here we only assert "the perk fires through the pipeline" and ordering invariants.
class BleederCanaryIntegrationTest {

    private val attacker = AgentId(UUID.randomUUID())
    private val target = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val rustySword = ItemId("RUSTY_SWORD")
    private val swordSkill = SkillId("SWORD")
    private val bleederId = PerkId("SWORD_BLEEDER")
    private val tracker = InMemoryBehaviorTracker()

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
        equipmentBonuses: dev.gvart.genesara.world.EquipmentBonusAggregator =
            dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses,
        deathProcessor: DeathProcessor,
        triggeredPassives: dev.gvart.genesara.world.internal.perks.TriggeredPassiveDispatcher,
        pendingScales: dev.gvart.genesara.world.internal.abilities.PendingAttackScaleStore,
        behaviorTracker: dev.gvart.genesara.world.internal.behavior.BehaviorTracker,
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
            rng = rng,
            tick = tick,
        ).map { out ->
            val next = state.copy(combat = out.sliceDelta).applyEffects(out.effects)
            next to out.events
        }

    @Test
    fun `Bleeder applies BLEED status on hit and respects internal cooldown`() {
        val balance = combatBalance()
        val items = StubItemLookup(swordItem())
        val agents = StubAgentRegistry(
            byId = mapOf(
                attacker to AgentAttributes(strength = 5, luck = 0, dexterity = 0),
                target to AgentAttributes(strength = 1, luck = 0, dexterity = 0),
            ),
        )
        val equipment = StubEquipmentStore(
            equippedByAgent = mapOf(
                attacker to mapOf(
                    EquipSlot.MAIN_HAND to ItemInstance.Equipment(
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
        val deathProcessor = DeathProcessor(balance, agents, equipment, groundItems)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val progression = SkillProgression(skills, publisher)

        val cd = InMemoryPerkCooldownStore()
        val dispatcher = TriggeredPassiveDispatcherImpl(BleederLookup(attacker), dev.gvart.genesara.world.EquipmentSetTriggerLookup.NoSetTriggers, cd)

        val initial = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeId to node),
            positions = mapOf(attacker to nodeId, target to nodeId),
            bodies = mapOf(
                attacker to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
                target to AgentBody(hp = 200, maxHp = 200, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
            ),
            inventories = emptyMap(),
        )

        val firstCommand = CombatCommand.AttackTarget(attacker, target)
        val (afterFirst, firstEvents) = assertNotNull(
            reduceAttack(
                initial, firstCommand, balance, items, agents, equipment, progression,
                equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor, rng = Random(seed = 7L), scaling = NoScaling,
                passiveAura = NoAura, triggeredPassives = dispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 100L,
            ).getOrNull(),
        )

        val firstAttacked = assertIs<CombatEvent.AgentAttacked>(firstEvents[0])
        assertTrue(firstAttacked.hpLost > 0)
        assertEquals(false, firstAttacked.isDodged)
        val firstTriggered = firstEvents.filterIsInstance<CombatEvent.PerkTriggered>()
        assertEquals(1, firstTriggered.size, "Bleeder fires once on the first hit")
        firstTriggered.single().let {
            assertEquals(attacker, it.agent)
            assertEquals(bleederId, it.perkId)
            assertEquals(TriggeredPassiveTrigger.ON_HIT_DEALT, it.trigger)
            assertEquals(TriggeredPassiveEffectKind.APPLY_STATUS_TO_TARGET, it.effectKind)
            assertEquals("BLEED", it.params["status"])
            assertEquals("10", it.params["duration-ticks"])
            assertEquals(target, it.target)
        }
        assertEquals(108L, cd.armedUntil[attacker to bleederId])

        val secondCommand = CombatCommand.AttackTarget(attacker, target)
        val (_, secondEvents) = assertNotNull(
            reduceAttack(
                afterFirst, secondCommand, balance, items, agents, equipment, progression,
                equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor, rng = Random(seed = 7L), scaling = NoScaling,
                passiveAura = NoAura, triggeredPassives = dispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 105L,
            ).getOrNull(),
        )
        assertTrue(secondEvents.none { it is CombatEvent.PerkTriggered }, "still on cooldown — no re-fire")
    }

    @Test
    fun `OnHitTaken precedes OnLowHp in the event stream for the same hit`() {
        val balance = combatBalance()
        val items = StubItemLookup(swordItem())
        val agents = StubAgentRegistry(
            byId = mapOf(
                attacker to AgentAttributes(strength = 10, luck = 0, dexterity = 0),
                target to AgentAttributes(strength = 1, luck = 0, dexterity = 0),
            ),
        )
        val equipment = StubEquipmentStore(
            equippedByAgent = mapOf(
                attacker to mapOf(
                    EquipSlot.MAIN_HAND to ItemInstance.Equipment(
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
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val progression = SkillProgression(skills, publisher)
        val cd = InMemoryPerkCooldownStore()
        val dispatcher = TriggeredPassiveDispatcherImpl(BothTriggersLookup(target), dev.gvart.genesara.world.EquipmentSetTriggerLookup.NoSetTriggers, cd)

        val initial = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeId to node),
            positions = mapOf(attacker to nodeId, target to nodeId),
            bodies = mapOf(
                attacker to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
                // 100 -> ~20 hp puts target across the 25% band in one hit.
                target to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
            ),
            inventories = emptyMap(),
        )

        val (_, events) = assertNotNull(
            reduceAttack(
                initial, CombatCommand.AttackTarget(attacker, target),
                balance, items, agents, equipment, progression,
                equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses,
                deathProcessor = DeathProcessor(balance, agents, equipment, StubGroundItemStore()),
                rng = Random(seed = 7L), scaling = NoScaling,
                passiveAura = NoAura, triggeredPassives = dispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1L,
            ).getOrNull(),
        )

        val hitTakenIdx = events.indexOfFirst {
            it is CombatEvent.PerkTriggered && it.trigger == TriggeredPassiveTrigger.ON_HIT_TAKEN
        }
        val lowHpIdx = events.indexOfFirst {
            it is CombatEvent.PerkTriggered && it.trigger == TriggeredPassiveTrigger.ON_LOW_HP
        }
        assertTrue(hitTakenIdx >= 0 && lowHpIdx >= 0, "both perks must fire on the same band-crossing hit")
        assertTrue(hitTakenIdx < lowHpIdx, "OnHitTaken precedes OnLowHp so consumers can mitigate before low-hp logic")
    }

    private fun combatBalance(): BalanceLookup = object : BalanceLookup {
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

    private fun swordItem() = mapOf(
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
    )

    // Two perks bound to the same defender — one on ON_HIT_TAKEN, one on ON_LOW_HP —
    // so the AttackReducer fires both on the same band-crossing hit.
    private inner class BothTriggersLookup(private val defender: AgentId) : TriggeredPassiveLookup {
        private val onHitTaken = triggered(
            id = "DEFENSIVE_REACTION",
            trigger = TriggeredPassiveTrigger.ON_HIT_TAKEN,
            effectKind = TriggeredPassiveEffectKind.GRANT_SELF_BUFF,
            cd = 5,
            params = mapOf("buff" to "STEELSKIN"),
        )
        private val onLowHp = triggered(
            id = "LAST_STAND",
            trigger = TriggeredPassiveTrigger.ON_LOW_HP,
            effectKind = TriggeredPassiveEffectKind.HEAL_SELF,
            cd = 30,
            params = mapOf("thresholdPct" to "25", "amount" to "40"),
        )

        override fun matching(agent: AgentId, trigger: TriggeredPassiveTrigger): List<TriggeredPerk> =
            if (agent != defender) {
                emptyList()
            } else when (trigger) {
                TriggeredPassiveTrigger.ON_HIT_TAKEN -> listOf(onHitTaken)
                TriggeredPassiveTrigger.ON_LOW_HP -> listOf(onLowHp)
                else -> emptyList()
            }

        private fun triggered(
            id: String,
            trigger: TriggeredPassiveTrigger,
            effectKind: TriggeredPassiveEffectKind,
            cd: Int,
            params: Map<String, String>,
        ): TriggeredPerk {
            val effect = PerkEffect.TriggeredPassive(trigger, effectKind, params, cd)
            val perk = Perk(
                id = PerkId(id),
                skill = swordSkill,
                milestoneLevel = 50,
                displayName = id,
                description = id,
                effect = effect,
            )
            return TriggeredPerk(perk, effect)
        }
    }

    private inner class BleederLookup(private val perkOwner: AgentId) : TriggeredPassiveLookup {
        private val effect = PerkEffect.TriggeredPassive(
            trigger = TriggeredPassiveTrigger.ON_HIT_DEALT,
            effectKind = TriggeredPassiveEffectKind.APPLY_STATUS_TO_TARGET,
            params = mapOf("status" to "BLEED", "duration-ticks" to "10"),
            internalCooldownTicks = 8,
        )
        private val perk = Perk(
            id = bleederId,
            skill = swordSkill,
            milestoneLevel = 50,
            displayName = "Bleeder",
            description = "Inflicts Bleed on hit",
            effect = effect,
        )

        override fun matching(agent: AgentId, trigger: TriggeredPassiveTrigger): List<TriggeredPerk> =
            if (agent == perkOwner && trigger == TriggeredPassiveTrigger.ON_HIT_DEALT) {
                listOf(TriggeredPerk(perk, effect))
            } else {
                emptyList()
            }
    }


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

    private class StubGroundItemStore : GroundItemStore {
        override fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long) = Unit
        override fun atNode(node: NodeId): List<GroundItemView> = error("not used")
        override fun take(node: NodeId, dropId: UUID): GroundItemView? = error("not used")
    }

    private class StubSkillsRegistry : AgentSkillsRegistry {
        override fun snapshot(agent: AgentId): AgentSkillsSnapshot =
            AgentSkillsSnapshot(perSkill = emptyMap(), slotCount = 8, slotsFilled = 0)
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult =
            AddXpResult.Unslotted
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
