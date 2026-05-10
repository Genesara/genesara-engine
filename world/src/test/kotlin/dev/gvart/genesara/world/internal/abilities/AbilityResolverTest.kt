package dev.gvart.genesara.world.internal.abilities

import dev.gvart.genesara.player.AbilityCostResource
import dev.gvart.genesara.player.AbilityEffectKind
import dev.gvart.genesara.player.AbilityId
import dev.gvart.genesara.player.AbilityTarget
import dev.gvart.genesara.player.ActivePerk
import dev.gvart.genesara.player.ActivePerkLookup
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.Perk
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
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
import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import dev.gvart.genesara.world.internal.testsupport.InMemoryPendingAttackScaleStore
import dev.gvart.genesara.world.internal.testsupport.InMemoryPerkCooldownStore
import dev.gvart.genesara.world.internal.worldstate.WorldState
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbilityResolverTest {

    private val agent = AgentId(UUID.randomUUID())
    private val target = AgentId(UUID.randomUUID())
    private val regionId = RegionId(1L)
    private val nodeA = NodeId(1L)
    private val nodeB = NodeId(2L)
    private val ability = AbilityId("SWORD_POWER_STRIKE")
    private val perkId = PerkId("SWORD_POWER_STRIKE")
    private val swordSkill = SkillId("SWORD")
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
    private val nodeAObj = Node(nodeA, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())
    private val nodeBObj = Node(nodeB, regionId, q = 1, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())
    private val pendingScales = InMemoryPendingAttackScaleStore()

    @Test
    fun `success path stages SCALE_NEXT_ATTACK buff, deducts stamina, arms cooldown, accrues XP`() {
        val cd = InMemoryPerkCooldownStore()
        val lookups = StubActivePerkLookup(active = powerStrike())
        val skills = SnapshotSkills()
        val publisher = RecordingPublisher()
        val state = baseState(stamina = 50)

        val command = WorldCommand.UseAbility(agent, ability, target = target)
        val (next, events) = assertNotNull(
            reduceUseAbility(
                state = state,
                command = command,
                activePerks = lookups,
                cooldowns = cd,
                progression = SkillProgression(skills, publisher),
                balance = combatBalance(),
                pendingScales = pendingScales,
                behaviorTracker = tracker,
                tickIntervalSeconds = 5L,
                tick = 100L,
            ).getOrNull(),
        )

        assertEquals(150, pendingScales.staged[agent])
        assertEquals(30, next.bodyOf(agent)?.stamina, "20 stamina deducted from 50")
        assertEquals(105L, cd.armedUntil[agent to perkId])

        val event = assertIs<WorldEvent.AbilityUsed>(events.single())
        assertEquals(ability, event.abilityId)
        assertEquals(target, event.target)
        assertEquals(AbilityEffectKind.SCALE_NEXT_ATTACK, event.effectKind)
        assertEquals(20, event.costAmount)
        assertEquals(AbilityCostResource.STAMINA, event.costResource)
        assertEquals(105L, event.readyAtTick)
        assertEquals(command.commandId, event.causedBy)

        assertTrue(skills.xpDeltas.any { it.first == swordSkill && it.second == 1 })
    }

    @Test
    fun `rejects unknown ability when agent has no chosen perk granting it`() {
        val cd = InMemoryPerkCooldownStore()
        val lookups = StubActivePerkLookup(active = null)

        val rejection = reduceUseAbility(
            state = baseState(stamina = 50),
            command = WorldCommand.UseAbility(agent, ability, target = target),
            activePerks = lookups,
            cooldowns = cd,
            progression = SkillProgression(SnapshotSkills(), RecordingPublisher()),
            balance = combatBalance(),
            pendingScales = pendingScales,
            behaviorTracker = tracker,
            tickIntervalSeconds = 5L,
            tick = 1L,
        ).leftOrNull()
        val unknown = assertIs<WorldRejection.UnknownAbility>(rejection)
        assertEquals(ability, unknown.ability)
    }

    @Test
    fun `rejects when ability is on cooldown — readyAtTick surfaces in the rejection`() {
        val cd = InMemoryPerkCooldownStore().apply { arm(agent, perkId, untilTick = 200L, currentTick = 0L) }

        val rejection = reduceUseAbility(
            state = baseState(stamina = 50),
            command = WorldCommand.UseAbility(agent, ability, target = target),
            activePerks = StubActivePerkLookup(active = powerStrike()),
            cooldowns = cd,
            progression = SkillProgression(SnapshotSkills(), RecordingPublisher()),
            balance = combatBalance(),
            pendingScales = pendingScales,
            behaviorTracker = tracker,
            tickIntervalSeconds = 5L,
            tick = 100L,
        ).leftOrNull()
        val onCd = assertIs<WorldRejection.AbilityOnCooldown>(rejection)
        assertEquals(200L, onCd.readyAtTick)
    }

    @Test
    fun `rejects when stamina is below the cost — does not arm cooldown or deduct anything`() {
        val cd = InMemoryPerkCooldownStore()
        val skills = SnapshotSkills()
        val state = baseState(stamina = 5)

        val rejection = reduceUseAbility(
            state = state,
            command = WorldCommand.UseAbility(agent, ability, target = target),
            activePerks = StubActivePerkLookup(active = powerStrike()),
            cooldowns = cd,
            progression = SkillProgression(skills, RecordingPublisher()),
            balance = combatBalance(),
            pendingScales = pendingScales,
            behaviorTracker = tracker,
            tickIntervalSeconds = 5L,
            tick = 1L,
        ).leftOrNull()
        val short = assertIs<WorldRejection.InsufficientAbilityResource>(rejection)
        assertEquals(AbilityCostResource.STAMINA, short.resource)
        assertEquals(20, short.required)
        assertEquals(5, short.available)
        assertNull(cd.armedUntil[agent to perkId], "rejection must not arm the cooldown")
        assertTrue(skills.xpDeltas.isEmpty(), "rejection must not accrue XP")
    }

    @Test
    fun `rejects SINGLE_AGENT ability when target is in a different node`() {
        val cd = InMemoryPerkCooldownStore()
        val state = baseState(stamina = 50).copy(
            positions = mapOf(agent to nodeA, target to nodeB),
            nodes = mapOf(nodeA to nodeAObj, nodeB to nodeBObj),
        )

        val rejection = reduceUseAbility(
            state = state,
            command = WorldCommand.UseAbility(agent, ability, target = target),
            activePerks = StubActivePerkLookup(active = powerStrike()),
            cooldowns = cd,
            progression = SkillProgression(SnapshotSkills(), RecordingPublisher()),
            balance = combatBalance(),
            pendingScales = pendingScales,
            behaviorTracker = tracker,
            tickIntervalSeconds = 5L,
            tick = 1L,
        ).leftOrNull()
        val mismatch = assertIs<WorldRejection.AbilityTargetNotInSameNode>(rejection)
        assertEquals(target, mismatch.target)
    }

    @Test
    fun `rejects SINGLE_AGENT ability when target is omitted`() {
        val rejection = reduceUseAbility(
            state = baseState(stamina = 50),
            command = WorldCommand.UseAbility(agent, ability, target = null),
            activePerks = StubActivePerkLookup(active = powerStrike()),
            cooldowns = InMemoryPerkCooldownStore(),
            progression = SkillProgression(SnapshotSkills(), RecordingPublisher()),
            balance = combatBalance(),
            pendingScales = pendingScales,
            behaviorTracker = tracker,
            tickIntervalSeconds = 5L,
            tick = 1L,
        ).leftOrNull()
        val mismatch = assertIs<WorldRejection.AbilityTargetMismatch>(rejection)
        assertEquals(AbilityTarget.SINGLE_AGENT, mismatch.expected)
    }

    @Test
    fun `rejects SELF ability when target is supplied`() {
        val selfBuff = ActivePerk(
            perk = Perk(
                id = perkId,
                skill = swordSkill,
                milestoneLevel = 100,
                displayName = "Self Buff",
                description = "",
                effect = PerkEffect.ActiveAbility(
                    abilityId = ability,
                    costResource = AbilityCostResource.STAMINA,
                    costAmount = 5,
                    target = AbilityTarget.SELF,
                    cooldownTicks = 5,
                    effectKind = AbilityEffectKind.HEAL_SELF,
                    effectParams = mapOf("amount" to "10"),
                ),
            ),
            effect = PerkEffect.ActiveAbility(
                abilityId = ability,
                costResource = AbilityCostResource.STAMINA,
                costAmount = 5,
                target = AbilityTarget.SELF,
                cooldownTicks = 5,
                effectKind = AbilityEffectKind.HEAL_SELF,
                effectParams = mapOf("amount" to "10"),
            ),
        )
        val rejection = reduceUseAbility(
            state = baseState(stamina = 50),
            command = WorldCommand.UseAbility(agent, ability, target = target),
            activePerks = StubActivePerkLookup(active = selfBuff),
            cooldowns = InMemoryPerkCooldownStore(),
            progression = SkillProgression(SnapshotSkills(), RecordingPublisher()),
            balance = combatBalance(),
            pendingScales = pendingScales,
            behaviorTracker = tracker,
            tickIntervalSeconds = 5L,
            tick = 1L,
        ).leftOrNull()
        val mismatch = assertIs<WorldRejection.AbilityTargetMismatch>(rejection)
        assertEquals(AbilityTarget.SELF, mismatch.expected)
    }

    private fun powerStrike(): ActivePerk {
        val effect = PerkEffect.ActiveAbility(
            abilityId = ability,
            costResource = AbilityCostResource.STAMINA,
            costAmount = 20,
            target = AbilityTarget.SINGLE_AGENT,
            cooldownTicks = 5,
            effectKind = AbilityEffectKind.SCALE_NEXT_ATTACK,
            effectParams = mapOf("multiplierPct" to "150"),
        )
        val perk = Perk(
            id = perkId,
            skill = swordSkill,
            milestoneLevel = 100,
            displayName = "Power Strike",
            description = "",
            effect = effect,
        )
        return ActivePerk(perk, effect)
    }

    private fun baseState(stamina: Int) = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeA to nodeAObj),
        positions = mapOf(agent to nodeA, target to nodeA),
        bodies = mapOf(
            agent to AgentBody(hp = 100, maxHp = 100, stamina = stamina, maxStamina = 100, mana = 0, maxMana = 0),
            target to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
        ),
        inventories = emptyMap(),
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
    }

    private class StubActivePerkLookup(private val active: ActivePerk?) : ActivePerkLookup {
        override fun byAbility(agent: AgentId, ability: AbilityId): ActivePerk? = active
    }

    private class SnapshotSkills : AgentSkillsRegistry {
        val xpDeltas = mutableListOf<Pair<SkillId, Int>>()
        override fun snapshot(agent: AgentId): AgentSkillsSnapshot =
            AgentSkillsSnapshot(perSkill = emptyMap(), slotCount = 8, slotsFilled = 0)
        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult {
            xpDeltas += skill to delta
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
