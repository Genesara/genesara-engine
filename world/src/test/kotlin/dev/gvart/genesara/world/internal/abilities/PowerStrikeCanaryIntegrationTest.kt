package dev.gvart.genesara.world.internal.abilities

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AbilityCostResource
import dev.gvart.genesara.player.AbilityEffectKind
import dev.gvart.genesara.player.AbilityId
import dev.gvart.genesara.player.AbilityTarget
import dev.gvart.genesara.player.ActivePerk
import dev.gvart.genesara.player.ActivePerkLookup
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
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.SkillSlotError
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
import dev.gvart.genesara.world.internal.combat.reduceAttack
import dev.gvart.genesara.world.internal.death.DeathProcessor
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import dev.gvart.genesara.world.internal.testsupport.InMemoryPendingAttackScaleStore
import dev.gvart.genesara.world.internal.testsupport.InMemoryPerkCooldownStore
import dev.gvart.genesara.world.internal.testsupport.NoOpTriggeredPassiveDispatcher
import dev.gvart.genesara.world.internal.worldstate.WorldState
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class PowerStrikeCanaryIntegrationTest {

    private val attacker = AgentId(UUID.randomUUID())
    private val target = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeId = NodeId(1L)
    private val rustySword = ItemId("RUSTY_SWORD")
    private val swordSkill = SkillId("SWORD")
    private val abilityId = AbilityId("SWORD_POWER_STRIKE")
    private val perkId = PerkId("SWORD_POWER_STRIKE")
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

    @Test
    fun `Power Strike stages SCALE_NEXT_ATTACK and the next attack scales damage by 1_5x`() {
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
        val publisher = RecordingPublisher()
        val skills = StubSkillsRegistry()
        val progression = SkillProgression(skills, publisher)
        val deathProcessor = DeathProcessor(balance, agents, equipment, StubGroundItemStore())
        val cooldowns = InMemoryPerkCooldownStore()
        val pendingScales = InMemoryPendingAttackScaleStore()
        val activePerks = SinglePerkLookup(attacker, abilityId, powerStrikeEffect())

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

        val useCmd = CombatCommand.UseAbility(attacker, abilityId, target = target)
        val (afterUse, useEvents) = assertNotNull(
            reduceUseAbility(
                state = initial,
                command = useCmd,
                activePerks = activePerks,
                cooldowns = cooldowns,
                pendingScales = pendingScales,
                progression = progression,
                balance = balance,
                behaviorTracker = tracker,
                tickIntervalSeconds = TICK_INTERVAL_SECONDS,
                tick = 100L,
            ).getOrNull(),
        )
        assertIs<CombatEvent.AbilityUsed>(useEvents.single())
        assertEquals(150, pendingScales.staged[attacker], "Power Strike stages the scale via the store, not WorldState")
        assertEquals(30, afterUse.bodyOf(attacker)?.stamina, "Power Strike pays 20 stamina at cast")
        assertEquals(105L, cooldowns.armedUntil[attacker to perkId])

        val baselineScales = InMemoryPendingAttackScaleStore()
        val (_, baselineEvents) = assertNotNull(
            reduceAttack(
                afterUse, CombatCommand.AttackTarget(attacker, target),
                balance, items, agents, equipment, progression, NoScaling, NoAura,
                dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses,
                deathProcessor, NoOpTriggeredPassiveDispatcher, baselineScales,
                tracker, rng = Random(seed = 7L), tick = 101L,
            ).getOrNull(),
        )
        val baseline = assertIs<CombatEvent.AgentAttacked>(baselineEvents.single())

        val (afterAttack, attackEvents) = assertNotNull(
            reduceAttack(
                afterUse, CombatCommand.AttackTarget(attacker, target),
                balance, items, agents, equipment, progression, NoScaling, NoAura,
                dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses,
                deathProcessor, NoOpTriggeredPassiveDispatcher, pendingScales,
                tracker, rng = Random(seed = 7L), tick = 101L,
            ).getOrNull(),
        )
        val scaled = assertIs<CombatEvent.AgentAttacked>(attackEvents.single())
        assertEquals(baseline.baseDamage * 150 / 100, scaled.baseDamage)
        assertNull(pendingScales.staged[attacker], "Single-shot buff is consumed by the first attack")

        val (_, secondAttackEvents) = assertNotNull(
            reduceAttack(
                afterAttack, CombatCommand.AttackTarget(attacker, target),
                balance, items, agents, equipment, progression, NoScaling, NoAura,
                dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses,
                deathProcessor, NoOpTriggeredPassiveDispatcher, pendingScales,
                tracker, rng = Random(seed = 7L), tick = 102L,
            ).getOrNull(),
        )
        val secondAttack = assertIs<CombatEvent.AgentAttacked>(secondAttackEvents.single())
        assertEquals(baseline.baseDamage, secondAttack.baseDamage, "Second attack lands at baseline")
    }

    @Test
    fun `cooldown rejects a back-to-back Power Strike cast`() {
        val cooldowns = InMemoryPerkCooldownStore()
        val pendingScales = InMemoryPendingAttackScaleStore()
        val activePerks = SinglePerkLookup(attacker, abilityId, powerStrikeEffect())
        val publisher = RecordingPublisher()
        val skills = StubSkillsRegistry()
        val progression = SkillProgression(skills, publisher)
        val balance = combatBalance()

        val state = WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeId to node),
            positions = mapOf(attacker to nodeId, target to nodeId),
            bodies = mapOf(
                attacker to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
                target to AgentBody(hp = 200, maxHp = 200, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
            ),
            inventories = emptyMap(),
        )

        val first = reduceUseAbility(
            state, CombatCommand.UseAbility(attacker, abilityId, target),
            activePerks, cooldowns, pendingScales, progression, balance, tracker, TICK_INTERVAL_SECONDS, tick = 50L,
        ).getOrNull()
        assertNotNull(first)

        val (afterFirst, _) = first
        val rejection = reduceUseAbility(
            afterFirst, CombatCommand.UseAbility(attacker, abilityId, target),
            activePerks, cooldowns, pendingScales, progression, balance, tracker, TICK_INTERVAL_SECONDS, tick = 51L,
        ).leftOrNull()
        assertIs<dev.gvart.genesara.world.WorldRejection.AbilityOnCooldown>(rejection)

        val later = reduceUseAbility(
            afterFirst.copy(
                bodies = afterFirst.bodies + (attacker to afterFirst.bodyOf(attacker)!!.copy(stamina = 50)),
            ),
            CombatCommand.UseAbility(attacker, abilityId, target),
            activePerks, cooldowns, pendingScales, progression, balance, tracker, TICK_INTERVAL_SECONDS, tick = 55L,
        ).getOrNull()
        assertTrue(later != null, "After the cooldown elapses the cast succeeds again")
    }

    private companion object {
        const val TICK_INTERVAL_SECONDS: Long = 5L
    }

    private fun powerStrikeEffect(): PerkEffect.ActiveAbility = PerkEffect.ActiveAbility(
        abilityId = abilityId,
        costResource = AbilityCostResource.STAMINA,
        costAmount = 20,
        target = AbilityTarget.SINGLE_AGENT,
        cooldownTicks = 5,
        effectKind = AbilityEffectKind.SCALE_NEXT_ATTACK,
        effectParams = mapOf("multiplierPct" to "150"),
    )

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

    private inner class SinglePerkLookup(
        private val owner: AgentId,
        private val ability: AbilityId,
        private val effect: PerkEffect.ActiveAbility,
    ) : ActivePerkLookup {
        override fun byAbility(agent: AgentId, ability: AbilityId): ActivePerk? {
            if (agent != owner || ability != this.ability) return null
            return ActivePerk(
                perk = Perk(
                    id = perkId,
                    skill = swordSkill,
                    milestoneLevel = 100,
                    displayName = "Power Strike",
                    description = "",
                    effect = effect,
                ),
                effect = effect,
            )
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
