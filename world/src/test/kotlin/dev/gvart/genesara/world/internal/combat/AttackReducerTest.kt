package dev.gvart.genesara.world.internal.combat

import dev.gvart.genesara.world.internal.testsupport.InMemoryBehaviorTracker
import dev.gvart.genesara.world.internal.testsupport.InMemoryPendingAttackScaleStore
import dev.gvart.genesara.world.internal.testsupport.NoOpTriggeredPassiveDispatcher
import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.AddXpResult
import dev.gvart.genesara.player.LevelScalingAggregator.Companion.NoScaling
import dev.gvart.genesara.player.PassiveAuraAggregator.Companion.NoAura
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentAttributes
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.AgentSkillState
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.AgentSkillsSnapshot
import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.AttributePointLoss
import dev.gvart.genesara.player.DeathPenaltyOutcome
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.SkillProgression
import dev.gvart.genesara.player.SkillSlotError
import dev.gvart.genesara.player.events.AgentEvent
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
import dev.gvart.genesara.world.WorldRejection
import dev.gvart.genesara.world.commands.WorldCommand
import dev.gvart.genesara.world.events.WorldEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.behavior.ActionCategory
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

class AttackReducerTest {

    private val attacker = AgentId(UUID.randomUUID())
    private val target = AgentId(UUID.randomUUID())
    private val tracker = InMemoryBehaviorTracker()
    private val regionId = RegionId(1L)
    private val nodeAId = NodeId(1L)
    private val nodeBId = NodeId(2L)
    private val nodeCId = NodeId(3L)

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
    private val nodeA = Node(nodeAId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())
    private val nodeB = Node(nodeBId, regionId, q = 1, r = 0, terrain = Terrain.PLAINS, adjacency = emptySet())

    private val rustySword = ItemId("RUSTY_SWORD")
    private val woodenBow = ItemId("WOODEN_BOW")
    private val swordSkill = SkillId("SWORD")
    private val bowSkill = SkillId("BOW")
    private val unarmedSkill = SkillId("UNARMED")

    @Test
    fun `class damage modifier composes after level scaling and aura — applies to the resolved base`() {
        val state = battleState(targetHp = 1000)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)
        val classes = object : dev.gvart.genesara.player.ClassLookup {
            override fun byId(classId: dev.gvart.genesara.player.AgentClass): dev.gvart.genesara.player.ClassDefinition? = null
            override fun all(): List<dev.gvart.genesara.player.ClassDefinition> = emptyList()
            override fun baseClasses(): List<dev.gvart.genesara.player.ClassDefinition> = emptyList()
            override fun evolutionsOf(parent: dev.gvart.genesara.player.AgentClass): List<dev.gvart.genesara.player.ClassDefinition> = emptyList()
            override fun sightRange(classId: dev.gvart.genesara.player.AgentClass?): Int = 3
            override fun skillXpMultiplier(classId: dev.gvart.genesara.player.AgentClass?, skill: SkillId): Double = 1.0
            override fun damageMultiplier(classId: dev.gvart.genesara.player.AgentClass?, damageType: String): Double = 2.0
            override fun forbidsCombatSkill(classId: dev.gvart.genesara.player.AgentClass?, combatSkill: SkillId): Boolean = false
        }
        val plus50PctScaling = object : dev.gvart.genesara.player.LevelScalingAggregator {
            override fun bonusFor(agent: AgentId, effect: dev.gvart.genesara.player.ScalingEffect): Double = 0.5
        }
        val plus10Aura = object : dev.gvart.genesara.player.PassiveAuraAggregator {
            override fun bonusFor(agent: AgentId, effect: dev.gvart.genesara.player.ScalingEffect): Int = 10
        }

        val (_, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(),
                agentsWithAttackerClass(strength = 10, classId = dev.gvart.genesara.player.AgentClass.SOLDIER),
                swordEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor, rng = Random(seed = 1L),
                scaling = plus50PctScaling, passiveAura = plus10Aura, triggeredPassives = NoOpTriggeredPassiveDispatcher,
                pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 5, classes = classes,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        // typedDamage = 10*8 = 80; preClassScaled = (80 * 1.5).toInt() + 10 = 130;
        // baseScaled = (130 * 2.0).toInt() = 260. Class composes AFTER aura.
        assertEquals(260, attacked.baseDamage)
    }

    @Test
    fun `class damage modifier scales the typed damage`() {
        val state = battleState(targetHp = 200)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)
        val classes = object : dev.gvart.genesara.player.ClassLookup {
            override fun byId(classId: dev.gvart.genesara.player.AgentClass): dev.gvart.genesara.player.ClassDefinition? = null
            override fun all(): List<dev.gvart.genesara.player.ClassDefinition> = emptyList()
            override fun baseClasses(): List<dev.gvart.genesara.player.ClassDefinition> = emptyList()
            override fun evolutionsOf(parent: dev.gvart.genesara.player.AgentClass): List<dev.gvart.genesara.player.ClassDefinition> = emptyList()
            override fun sightRange(classId: dev.gvart.genesara.player.AgentClass?): Int = 3
            override fun skillXpMultiplier(classId: dev.gvart.genesara.player.AgentClass?, skill: SkillId): Double = 1.0
            override fun damageMultiplier(classId: dev.gvart.genesara.player.AgentClass?, damageType: String): Double =
                if (classId == dev.gvart.genesara.player.AgentClass.SOLDIER && damageType == "SLASH") 1.5 else 1.0
            override fun forbidsCombatSkill(classId: dev.gvart.genesara.player.AgentClass?, combatSkill: SkillId): Boolean = false
        }

        val (_, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(),
                agentsWithAttackerClass(strength = 10, classId = dev.gvart.genesara.player.AgentClass.SOLDIER),
                swordEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor, rng = Random(seed = 1L), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 5, classes = classes,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(120, attacked.baseDamage, "10 STR * 8 weaponPower * 1.5 class SLASH mod = 120")
    }

    @Test
    fun `happy path — sword strike with no crit and no dodge, deterministic damage`() {
        val state = battleState(targetHp = 100)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)

        val (next, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0), swordEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor, rng = Random(seed = 1L), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 5,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(80, attacked.baseDamage)
        assertEquals(80, attacked.hpLost)
        assertEquals(false, attacked.isCrit)
        assertEquals(false, attacked.isDodged)
        assertEquals(DamageType.SLASH, attacked.damageType)
        assertEquals(20, attacked.targetHpAfter)
        assertEquals(false, attacked.targetKilled)
        assertEquals(20, next.bodyOf(target)!!.hp)
        assertEquals(45, next.bodyOf(attacker)!!.stamina, "stamina cost 5")
        assertEquals(listOf(swordSkill to 1), skills.xpAddCalls)
        assertEquals(mapOf(ActionCategory.COMBAT to 1), tracker.snapshotFor(attacker))
    }

    @Test
    fun `unarmed fallback — uses balance defaults and grants UNARMED XP`() {
        val state = battleState(targetHp = 100)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)

        val (_, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(), agents(strength = 5, luck = 0, dex = 0),
                StubEquipmentStore(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor, rng = Random(seed = 1L), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(DamageType.BLUNT, attacked.damageType)
        assertEquals(10, attacked.hpLost)
        assertEquals(listOf(unarmedSkill to 1), skills.xpAddCalls)
    }

    @Test
    fun `dodge fires when seeded RNG lands in the dodge window`() {
        val seed = seedThatRolls(target = 49, callIndex = 0)  // first nextInt(100) < 50
        val state = battleState(targetHp = 100)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)

        val (next, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(),
                agents(strength = 10, luck = 0, dex = 0, targetDex = 99),
                swordEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor, rng = Random(seed), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(true, attacked.isDodged)
        assertEquals(false, attacked.isCrit)
        assertEquals(0, attacked.hpLost)
        assertEquals(100, next.bodyOf(target)!!.hp, "dodged hits leave the target body untouched")
        assertEquals(45, next.bodyOf(attacker)!!.stamina, "attacker still pays stamina cost on a miss")
        assertEquals(listOf(swordSkill to 1), skills.xpAddCalls, "weapon-skill XP accrues even when dodged")
    }

    @Test
    fun `crit fires when dodge passes and crit roll lands in the LUCK window`() {
        val seed = seedThatPassesThenRolls(threshold = 49)  // first call ≥ 50, second call < 50
        val state = battleState(targetHp = 200)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)

        val (_, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(),
                agents(strength = 10, luck = 99, dex = 0, targetDex = 0),
                swordEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor, rng = Random(seed), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(false, attacked.isDodged)
        assertEquals(true, attacked.isCrit)
        assertEquals(160, attacked.hpLost)
    }

    @Test
    fun `weapon with null combat-skill in catalog — falls back to UNARMED skill XP`() {
        val state = battleState(targetHp = 100)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)

        val (_, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithUnmappedWeapon(),
                agents(strength = 10, luck = 0, dex = 0),
                unmappedWeaponEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor, rng = Random(seed = 1L), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(DamageType.BLUNT, attacked.damageType, "no damage-type on weapon → unarmed fallback")
        assertEquals(listOf(unarmedSkill to 1), skills.xpAddCalls)
    }

    @Test
    fun `killing blow — emits AgentAttacked and AgentDied with shared causedBy, increments attacker streak`() {
        val state = battleState(targetHp = 1)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)

        val command = WorldCommand.AttackTarget(attacker, target)
        val (next, events) = assertNotNull(
            reduceAttack(
                state, command,
                balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0),
                swordEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor, rng = Random(seed = 1L), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 7,
            ).getOrNull(),
        )

        assertEquals(2, events.size, "AgentAttacked + AgentDied")
        val attacked = assertIs<WorldEvent.AgentAttacked>(events[0])
        val died = assertIs<WorldEvent.AgentDied>(events[1])
        assertEquals(true, attacked.targetKilled)
        assertEquals(0, attacked.targetHpAfter)
        assertEquals(command.commandId, attacked.causedBy)
        assertEquals(command.commandId, died.causedBy)
        assertTrue(target !in next.positions, "killed target removed from positions by DeathProcessor")
        assertEquals(1, next.killStreakOf(attacker).killCount, "attacker streak ticked on the kill")
    }

    @Test
    fun `zero-base-damage attack still emits AgentAttacked with hpLost=0 — a hit, not a miss`() {
        val state = battleState(targetHp = 100)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)

        val (next, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(),
                agents(strength = 0, luck = 0, dex = 0),
                swordEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor, rng = Random(seed = 1L), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(0, attacked.baseDamage)
        assertEquals(0, attacked.hpLost)
        assertEquals(false, attacked.isDodged, "STR=0 produces a real hit for 0 damage, not a dodge")
        assertEquals(false, attacked.isCrit)
        assertEquals(100, next.bodyOf(target)!!.hp)
    }

    @Test
    fun `killing blow with active streak — drop fires and ItemDroppedOnGround inherits the killing commandId`() {
        val seed = 1L  // any seed — drop chance is 1.0 here so it always clears
        val state = battleState(targetHp = 1).copy(
            killStreaks = mapOf(target to dev.gvart.genesara.world.AgentKillStreak(killCount = 5, windowStartTick = 0L)),
            inventories = mapOf(
                target to dev.gvart.genesara.world.internal.inventory.AgentInventory(
                    mapOf(ItemId("WOOD") to 3),
                ),
            ),
        )
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = DeathProcessor(
            balance = balanceWithGuaranteedDrop(),
            agents = StubAgentRegistry(
                byId = mapOf(target to AgentAttributes()),
                scriptedDeath = mapOf(
                    target to DeathPenaltyOutcome(xpLost = 0, deleveled = false, attributePointLost = null),
                ),
            ),
            equipment = StubEquipmentStore(),
            groundItems = StubGroundItemStore(),
        )

        val command = WorldCommand.AttackTarget(attacker, target)
        val (_, events) = assertNotNull(
            reduceAttack(
                state, command,
                balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0),
                swordEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor, rng = Random(seed), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 5,
            ).getOrNull(),
        )

        assertEquals(3, events.size, "AgentAttacked + AgentDied + ItemDroppedOnGround")
        val died = assertIs<WorldEvent.AgentDied>(events[1])
        val dropped = assertIs<WorldEvent.ItemDroppedOnGround>(events[2])
        assertEquals(command.commandId, died.causedBy)
        assertEquals(command.commandId, dropped.causedBy, "drop event inherits the killing attack's commandId")
        assertEquals(target, dropped.byAgent)
    }

    @Test
    fun `cannot attack self`() {
        val state = battleState(targetHp = 100).copy(positions = mapOf(attacker to nodeAId))
        val result = reduceAttack(
            state, WorldCommand.AttackTarget(attacker, attacker),
            balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0), swordEquipped(),
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses,
            deathProcessor = stubDeathProcessor(StubSkillsRegistry(), RecordingPublisher()),
            rng = Random(seed = 1L), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
        )
        assertEquals(WorldRejection.CannotAttackSelf(attacker), result.leftOrNull())
    }

    @Test
    fun `attacker not in world`() {
        val state = battleState(targetHp = 100).copy(positions = mapOf(target to nodeAId))
        val result = reduceAttack(
            state, WorldCommand.AttackTarget(attacker, target),
            balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0), swordEquipped(),
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses,
            deathProcessor = stubDeathProcessor(StubSkillsRegistry(), RecordingPublisher()),
            rng = Random(seed = 1L), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
        )
        assertEquals(WorldRejection.NotInWorld(attacker), result.leftOrNull())
    }

    @Test
    fun `target not in world`() {
        val state = battleState(targetHp = 100).copy(positions = mapOf(attacker to nodeAId))
        val result = reduceAttack(
            state, WorldCommand.AttackTarget(attacker, target),
            balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0), swordEquipped(),
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses,
            deathProcessor = stubDeathProcessor(StubSkillsRegistry(), RecordingPublisher()),
            rng = Random(seed = 1L), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
        )
        assertEquals(WorldRejection.TargetNotInWorld(attacker, target), result.leftOrNull())
    }

    @Test
    fun `melee weapon (range=1) rejects target on a different node`() {
        val state = battleState(targetHp = 100).copy(positions = mapOf(attacker to nodeAId, target to nodeBId))
        val result = reduceAttack(
            state, WorldCommand.AttackTarget(attacker, target),
            balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0), swordEquipped(),
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses,
            deathProcessor = stubDeathProcessor(StubSkillsRegistry(), RecordingPublisher()),
            rng = Random(seed = 1L), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
        )
        assertEquals(
            WorldRejection.TargetOutOfRange(attacker, target, nodeAId, nodeBId, weaponRange = 1),
            result.leftOrNull(),
        )
    }

    @Test
    fun `ranged weapon (range=2) hits target on an adjacent node`() {
        val state = battleStateAdjacentNodes(targetHp = 100)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)

        val (next, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithBow(), agents(strength = 0, luck = 0, dex = 10), bowEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor, rng = Random(seed = 1L), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(DamageType.PIERCE, attacked.damageType)
        assertEquals(false, attacked.isDodged)
        assertEquals(70, attacked.hpLost)
        assertEquals(SkillId("BOW") to 1, skills.xpAddCalls.single())
        assertEquals(30, next.bodyOf(target)!!.hp)
    }

    @Test
    fun `ranged weapon (range=2) still rejects a target two hops away`() {
        val state = battleStateAdjacentNodes(targetHp = 100)
            .copy(positions = mapOf(attacker to nodeAId, target to nodeCId))
        val result = reduceAttack(
            state, WorldCommand.AttackTarget(attacker, target),
            balance(), itemsWithBow(), agents(strength = 10, luck = 0, dex = 0), bowEquipped(),
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses,
            deathProcessor = stubDeathProcessor(StubSkillsRegistry(), RecordingPublisher()),
            rng = Random(seed = 1L), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
        )
        assertEquals(
            WorldRejection.TargetOutOfRange(attacker, target, nodeAId, nodeCId, weaponRange = 2),
            result.leftOrNull(),
        )
    }

    @Test
    fun `target already at HP=0`() {
        val state = battleState(targetHp = 0)
        val result = reduceAttack(
            state, WorldCommand.AttackTarget(attacker, target),
            balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0), swordEquipped(),
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses,
            deathProcessor = stubDeathProcessor(StubSkillsRegistry(), RecordingPublisher()),
            rng = Random(seed = 1L), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
        )
        assertEquals(WorldRejection.TargetAlreadyDead(attacker, target), result.leftOrNull())
    }

    @Test
    fun `slash damage scales with the attacker's SLASH_DAMAGE_BONUS — base 80 plus 50pct = 120`() {
        val state = battleState(targetHp = 200)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)
        val scaling = StubScaling(mapOf(dev.gvart.genesara.player.ScalingEffect.SLASH_DAMAGE_BONUS to 0.50))

        val (next, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0), swordEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor,
                rng = Random(seed = 1L), scaling = scaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(120, attacked.baseDamage)
        assertEquals(120, attacked.hpLost)
        assertEquals(80, next.bodyOf(target)!!.hp)
    }

    @Test
    fun `Modifier perk doubles the slash scaling rate — Doubled Edge canary at L150`() {
        val state = battleState(targetHp = 300)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)
        // L150 unmodified would be 0.75 (150 × 0.005); the Doubled-Edge Modifier doubles it to 1.50.
        val scaling = StubScaling(mapOf(dev.gvart.genesara.player.ScalingEffect.SLASH_DAMAGE_BONUS to 1.50))

        val (_, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0), swordEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor,
                rng = Random(seed = 1L), scaling = scaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(200, attacked.baseDamage, "80 × (1 + 1.50) = 200")
    }

    @Test
    fun `MAGICAL damage type has no scaling effect mapped — base damage unchanged`() {
        // Ensures the `when` exhaustiveness on DamageType handles MAGICAL with a no-op.
        val state = battleState(targetHp = 100)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)
        val scaling = StubScaling(mapOf(dev.gvart.genesara.player.ScalingEffect.SLASH_DAMAGE_BONUS to 5.0))

        val (_, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithUnmappedWeapon(), agents(strength = 10, luck = 0, dex = 0),
                unmappedWeaponEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor,
                rng = Random(seed = 1L), scaling = scaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(DamageType.BLUNT, attacked.damageType)
        // 5.0 SLASH bonus must not bleed into BLUNT damage.
        assertEquals(20, attacked.baseDamage, "STR 10 × unarmed power 2 = 20, no SLASH bonus mapped to BLUNT")
    }

    private class StubScaling(
        private val byEffect: Map<dev.gvart.genesara.player.ScalingEffect, Double>,
    ) : dev.gvart.genesara.player.LevelScalingAggregator {
        override fun bonusFor(
            agent: dev.gvart.genesara.player.AgentId,
            effect: dev.gvart.genesara.player.ScalingEffect,
        ): Double = byEffect[effect] ?: 0.0
    }

    private class StubAura(
        private val byEffect: Map<dev.gvart.genesara.player.ScalingEffect, Int>,
    ) : dev.gvart.genesara.player.PassiveAuraAggregator {
        override fun bonusFor(
            agent: dev.gvart.genesara.player.AgentId,
            effect: dev.gvart.genesara.player.ScalingEffect,
        ): Int = byEffect[effect] ?: 0
    }

    @Test
    fun `PassiveAura applies as flat post-scaling bonus — Sharpen Edge canary at +5 SLASH`() {
        val state = battleState(targetHp = 100)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)
        val aura = StubAura(mapOf(dev.gvart.genesara.player.ScalingEffect.SLASH_DAMAGE_BONUS to 5))

        val (_, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0), swordEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor,
                rng = Random(seed = 1L), scaling = NoScaling, passiveAura = aura,
                triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(85, attacked.baseDamage, "STR 10 × power 8 = 80, then +5 flat aura")
        assertEquals(85, attacked.hpLost)
    }

    @Test
    fun `PassiveAura adds AFTER level scaling — flat term layered on the multiplied base`() {
        val state = battleState(targetHp = 300)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)
        val scaling = StubScaling(mapOf(dev.gvart.genesara.player.ScalingEffect.SLASH_DAMAGE_BONUS to 0.50))
        val aura = StubAura(mapOf(dev.gvart.genesara.player.ScalingEffect.SLASH_DAMAGE_BONUS to 5))

        val (_, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0), swordEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor,
                rng = Random(seed = 1L), scaling = scaling, passiveAura = aura,
                triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        // 80 × (1 + 0.50) = 120; flat aura adds AFTER → 125. (If applied before scaling, the
        // result would be (80+5) × 1.5 = 127, so this asserts the documented order.)
        assertEquals(125, attacked.baseDamage)
    }

    @Test
    fun `PassiveAura keyed on a different damage type does not apply`() {
        // Weapon is BLUNT (unarmed fallback), so a SLASH-keyed aura must not bleed in.
        val state = battleState(targetHp = 100)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val deathProcessor = stubDeathProcessor(skills, publisher)
        val aura = StubAura(mapOf(dev.gvart.genesara.player.ScalingEffect.SLASH_DAMAGE_BONUS to 99))

        val (_, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithUnmappedWeapon(), agents(strength = 10, luck = 0, dex = 0),
                unmappedWeaponEquipped(),
                SkillProgression(skills, publisher), equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses, deathProcessor = deathProcessor,
                rng = Random(seed = 1L), scaling = NoScaling, passiveAura = aura,
                triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(DamageType.BLUNT, attacked.damageType)
        assertEquals(20, attacked.baseDamage, "BLUNT attack reads BLUNT_DAMAGE_BONUS, not SLASH_DAMAGE_BONUS")
    }

    @Test
    fun `attacker has insufficient stamina`() {
        val state = battleState(targetHp = 100, attackerStamina = 3)
        val result = reduceAttack(
            state, WorldCommand.AttackTarget(attacker, target),
            balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0), swordEquipped(),
            SkillProgression(StubSkillsRegistry(), RecordingPublisher()),
            equipmentBonuses = dev.gvart.genesara.world.EquipmentBonusAggregator.NoBonuses,
            deathProcessor = stubDeathProcessor(StubSkillsRegistry(), RecordingPublisher()),
            rng = Random(seed = 1L), scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher, pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
        )
        assertEquals(
            WorldRejection.NotEnoughStamina(attacker, required = 5, available = 3),
            result.leftOrNull(),
        )
    }

    @Test
    fun `defender's armor-def is multiplied by defender CON and subtracted from raw damage`() {
        val state = battleState(targetHp = 100)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val armoredDefender = object : dev.gvart.genesara.world.EquipmentBonusAggregator {
            override fun armorDef(agent: AgentId, damageType: dev.gvart.genesara.world.DamageType): Int =
                if (agent == target && damageType == dev.gvart.genesara.world.DamageType.SLASH) 6 else 0
            override fun attributeBonus(agent: AgentId, attribute: dev.gvart.genesara.player.Attribute) = 0
            override fun passiveBuff(agent: AgentId, effect: dev.gvart.genesara.player.ScalingEffect) = 0
        }

        val (_, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0, targetCon = 5), swordEquipped(),
                SkillProgression(skills, publisher),
                equipmentBonuses = armoredDefender,
                deathProcessor = stubDeathProcessor(skills, publisher),
                rng = Random(seed = 1L),
                scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher,
                pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        // attackerStat × weaponPower = 10 × 8 = 80; mitigation = CON(5) × armorDef(6) = 30.
        // mitigatedRaw = 50; typeModifier = 1.0; scaling/aura/class neutral → baseDamage = 50.
        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(50, attacked.baseDamage)
    }

    @Test
    fun `armor mitigation scales with defender CON — same armorDef, higher CON, more mitigation`() {
        val skills = StubSkillsRegistry()
        val fixedArmor = object : dev.gvart.genesara.world.EquipmentBonusAggregator {
            override fun armorDef(agent: AgentId, damageType: dev.gvart.genesara.world.DamageType): Int =
                if (damageType == dev.gvart.genesara.world.DamageType.SLASH) 4 else 0
            override fun attributeBonus(agent: AgentId, attribute: dev.gvart.genesara.player.Attribute) = 0
            override fun passiveBuff(agent: AgentId, effect: dev.gvart.genesara.player.ScalingEffect) = 0
        }

        fun damageAtCon(con: Int): Int {
            val (_, events) = assertNotNull(
                reduceAttack(
                    battleState(targetHp = 100), WorldCommand.AttackTarget(attacker, target),
                    balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0, targetCon = con), swordEquipped(),
                    SkillProgression(skills, RecordingPublisher()),
                    equipmentBonuses = fixedArmor,
                    deathProcessor = stubDeathProcessor(skills, RecordingPublisher()),
                    rng = Random(seed = 1L),
                    scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher,
                    pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
                ).getOrNull(),
            )
            return assertIs<WorldEvent.AgentAttacked>(events.single()).baseDamage
        }

        // raw = 80. armor=4. CON=1 → mitigation 4 → 76. CON=10 → mitigation 40 → 40.
        assertEquals(76, damageAtCon(1))
        assertEquals(40, damageAtCon(10))
    }

    @Test
    fun `armor-def sums across multiple equipped pieces matching the damage type`() {
        // End-to-end: chest + helmet both grant SLASH armor — total armorDef enters the
        // formula once after the aggregator sums them.
        val state = battleState(targetHp = 100)
        val skills = StubSkillsRegistry()
        val publisher = RecordingPublisher()
        val chestPlusHelmet = object : dev.gvart.genesara.world.EquipmentBonusAggregator {
            override fun armorDef(agent: AgentId, damageType: dev.gvart.genesara.world.DamageType): Int =
                if (agent == target && damageType == dev.gvart.genesara.world.DamageType.SLASH) 3 + 2 else 0
            override fun attributeBonus(agent: AgentId, attribute: dev.gvart.genesara.player.Attribute) = 0
            override fun passiveBuff(agent: AgentId, effect: dev.gvart.genesara.player.ScalingEffect) = 0
        }

        val (_, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0, targetCon = 4), swordEquipped(),
                SkillProgression(skills, publisher),
                equipmentBonuses = chestPlusHelmet,
                deathProcessor = stubDeathProcessor(skills, publisher),
                rng = Random(seed = 1L),
                scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher,
                pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        // raw = 80; mitigation = CON(4) × (3+2) = 20; baseDamage = 60.
        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(60, attacked.baseDamage)
    }

    @Test
    fun `armor-def for a different damage type does not reduce damage`() {
        val state = battleState(targetHp = 100)
        val skills = StubSkillsRegistry()
        val pierceArmor = object : dev.gvart.genesara.world.EquipmentBonusAggregator {
            override fun armorDef(agent: AgentId, damageType: dev.gvart.genesara.world.DamageType): Int =
                if (damageType == dev.gvart.genesara.world.DamageType.PIERCE) 999 else 0
            override fun attributeBonus(agent: AgentId, attribute: dev.gvart.genesara.player.Attribute) = 0
            override fun passiveBuff(agent: AgentId, effect: dev.gvart.genesara.player.ScalingEffect) = 0
        }

        val (_, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0), swordEquipped(),
                SkillProgression(skills, RecordingPublisher()),
                equipmentBonuses = pierceArmor,
                deathProcessor = stubDeathProcessor(skills, RecordingPublisher()),
                rng = Random(seed = 1L),
                scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher,
                pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        // Sword deals SLASH; PIERCE armor stack is irrelevant.
        assertEquals(80, attacked.baseDamage)
    }

    @Test
    fun `armor-def cannot reduce raw damage below zero`() {
        val state = battleState(targetHp = 100)
        val skills = StubSkillsRegistry()
        val overArmor = object : dev.gvart.genesara.world.EquipmentBonusAggregator {
            override fun armorDef(agent: AgentId, damageType: dev.gvart.genesara.world.DamageType): Int = 999
            override fun attributeBonus(agent: AgentId, attribute: dev.gvart.genesara.player.Attribute) = 0
            override fun passiveBuff(agent: AgentId, effect: dev.gvart.genesara.player.ScalingEffect) = 0
        }

        val (_, events) = assertNotNull(
            reduceAttack(
                state, WorldCommand.AttackTarget(attacker, target),
                balance(), itemsWithSword(), agents(strength = 10, luck = 0, dex = 0), swordEquipped(),
                SkillProgression(skills, RecordingPublisher()),
                equipmentBonuses = overArmor,
                deathProcessor = stubDeathProcessor(skills, RecordingPublisher()),
                rng = Random(seed = 1L),
                scaling = NoScaling, passiveAura = NoAura, triggeredPassives = NoOpTriggeredPassiveDispatcher,
                pendingScales = InMemoryPendingAttackScaleStore(), behaviorTracker = tracker, tick = 1,
            ).getOrNull(),
        )

        val attacked = assertIs<WorldEvent.AgentAttacked>(events.single())
        assertEquals(0, attacked.baseDamage)
        assertEquals(0, attacked.hpLost)
    }

    private fun battleState(targetHp: Int, attackerStamina: Int = 50): WorldState = WorldState(
        regions = mapOf(regionId to region),
        nodes = mapOf(nodeAId to nodeA, nodeBId to nodeB),
        positions = mapOf(attacker to nodeAId, target to nodeAId),
        bodies = mapOf(
            attacker to AgentBody(hp = 100, maxHp = 100, stamina = attackerStamina, maxStamina = 50, mana = 0, maxMana = 0),
            target to AgentBody(hp = targetHp, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
        ),
        inventories = emptyMap(),
    )

    private fun battleStateAdjacentNodes(targetHp: Int): WorldState {
        // A — B — C: attacker on A, target on B (adjacent → range 2 reaches), C is two hops out.
        val a = Node(nodeAId, regionId, q = 0, r = 0, terrain = Terrain.PLAINS, adjacency = setOf(nodeBId))
        val b = Node(nodeBId, regionId, q = 1, r = 0, terrain = Terrain.PLAINS, adjacency = setOf(nodeAId, nodeCId))
        val c = Node(nodeCId, regionId, q = 2, r = 0, terrain = Terrain.PLAINS, adjacency = setOf(nodeBId))
        return WorldState(
            regions = mapOf(regionId to region),
            nodes = mapOf(nodeAId to a, nodeBId to b, nodeCId to c),
            positions = mapOf(attacker to nodeAId, target to nodeBId),
            bodies = mapOf(
                attacker to AgentBody(hp = 100, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
                target to AgentBody(hp = targetHp, maxHp = 100, stamina = 50, maxStamina = 50, mana = 0, maxMana = 0),
            ),
            inventories = emptyMap(),
        )
    }

    private fun balanceWithGuaranteedDrop(): BalanceLookup = object : BalanceLookup {
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
        override fun dropChanceForKillCount(killCount: Int): Double = 1.0
    }

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
        override fun killStreakWindowTicks(): Long = 1000L
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
                weaponPower = 8,
                combatSkill = swordSkill,
                validSlots = setOf(EquipSlot.MAIN_HAND),
            ),
        ),
    )

    private fun itemsWithBow(): ItemLookup = StubItemLookup(
        mapOf(
            woodenBow to Item(
                id = woodenBow,
                displayName = "Wooden Bow",
                description = "",
                category = ItemCategory.EQUIPMENT,
                weightPerUnit = 1500,
                maxStack = 1,
                damageType = DamageType.PIERCE,
                weaponPower = 7,
                combatSkill = bowSkill,
                range = 2,
                validSlots = setOf(EquipSlot.MAIN_HAND),
                twoHanded = true,
            ),
        ),
    )

    private fun bowEquipped(): EquipmentInstanceStore = StubEquipmentStore(
        equippedByAgent = mapOf(
            attacker to mapOf(
                EquipSlot.MAIN_HAND to EquipmentInstance(
                    instanceId = UUID.randomUUID(),
                    agentId = attacker,
                    itemId = woodenBow,
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

    private fun itemsWithUnmappedWeapon(): ItemLookup = StubItemLookup(
        mapOf(
            ItemId("CRUDE_STICK") to Item(
                id = ItemId("CRUDE_STICK"),
                displayName = "Crude Stick",
                description = "",
                category = ItemCategory.EQUIPMENT,
                weightPerUnit = 100,
                maxStack = 1,
                validSlots = setOf(EquipSlot.MAIN_HAND),
            ),
        ),
    )

    private fun swordEquipped(): EquipmentInstanceStore = StubEquipmentStore(
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

    private fun unmappedWeaponEquipped(): EquipmentInstanceStore = StubEquipmentStore(
        equippedByAgent = mapOf(
            attacker to mapOf(
                EquipSlot.MAIN_HAND to EquipmentInstance(
                    instanceId = UUID.randomUUID(),
                    agentId = attacker,
                    itemId = ItemId("CRUDE_STICK"),
                    rarity = Rarity.COMMON,
                    durabilityCurrent = 10,
                    durabilityMax = 10,
                    creatorAgentId = null,
                    createdAtTick = 0L,
                    equippedInSlot = EquipSlot.MAIN_HAND,
                ),
            ),
        ),
    )

    private fun agents(strength: Int, luck: Int, dex: Int, targetDex: Int = 0, targetCon: Int = 1): AgentRegistry =
        StubAgentRegistry(
            byId = mapOf(
                attacker to AgentAttributes(strength = strength, luck = luck, dexterity = dex),
                target to AgentAttributes(dexterity = targetDex, constitution = targetCon),
            ),
        )

    private fun agentsWithAttackerClass(strength: Int, classId: dev.gvart.genesara.player.AgentClass): AgentRegistry =
        StubAgentRegistry(
            byId = mapOf(
                attacker to AgentAttributes(strength = strength, luck = 0, dexterity = 0),
                target to AgentAttributes(dexterity = 0),
            ),
            classByAgent = mapOf(attacker to classId),
        )

    private fun stubDeathProcessor(
        skills: AgentSkillsRegistry,
        publisher: ApplicationEventPublisher,
    ): DeathProcessor = DeathProcessor(
        balance = balance(),
        agents = StubAgentRegistry(
            byId = mapOf(target to AgentAttributes(strength = 1)),
            scriptedDeath = mapOf(target to DeathPenaltyOutcome(xpLost = 0, deleveled = false, attributePointLost = null)),
        ),
        equipment = StubEquipmentStore(),
        groundItems = StubGroundItemStore(),
    )

    private fun seedThatRolls(target: Int, callIndex: Int): Long {
        for (s in 0L..10_000L) {
            val rng = Random(s)
            repeat(callIndex) { rng.nextInt(100) }
            if (rng.nextInt(100) < target) return s
        }
        error("could not find a seed where call #$callIndex rolls < $target")
    }

    private fun seedThatPassesThenRolls(threshold: Int): Long {
        for (s in 0L..10_000L) {
            val rng = Random(s)
            val first = rng.nextInt(100)
            val second = rng.nextInt(100)
            if (first >= 50 && second < threshold) return s
        }
        error("could not find a seed that skips dodge and lands a crit")
    }

    private class StubItemLookup(private val byId: Map<ItemId, Item>) : ItemLookup {
        override fun byId(id: ItemId): Item? = byId[id]
        override fun all(): List<Item> = byId.values.toList()
    }

    private class StubAgentRegistry(
        private val byId: Map<AgentId, AgentAttributes>,
        private val scriptedDeath: Map<AgentId, DeathPenaltyOutcome> = emptyMap(),
        private val classByAgent: Map<AgentId, dev.gvart.genesara.player.AgentClass> = emptyMap(),
    ) : AgentRegistry {
        override fun find(id: AgentId): Agent? = byId[id]?.let {
            Agent(
                id = id,
                owner = PlayerId(UUID.randomUUID()),
                name = "test",
                attributes = it,
                classId = classByAgent[id],
            )
        }
        override fun listForOwner(owner: PlayerId): List<Agent> = error("not used")
        override fun applyDeathPenalty(agentId: AgentId, xpLossOnDeath: Int): DeathPenaltyOutcome? =
            scriptedDeath[agentId]
    }

    private class StubEquipmentStore(
        private val equippedByAgent: Map<AgentId, Map<EquipSlot, EquipmentInstance>> = emptyMap(),
    ) : EquipmentInstanceStore {
        val deletedInstanceIds: MutableList<UUID> = mutableListOf()
        override fun equippedFor(agentId: AgentId): Map<EquipSlot, EquipmentInstance> =
            equippedByAgent[agentId] ?: emptyMap()
        override fun insert(instance: EquipmentInstance) = error("not used")
        override fun findById(instanceId: UUID): EquipmentInstance? = error("not used")
        override fun listByAgent(agentId: AgentId): List<EquipmentInstance> = error("not used")
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
        override fun deposit(node: NodeId, drop: DroppedItemView, droppedAtTick: Long) = Unit
        override fun atNode(node: NodeId): List<GroundItemView> = error("not used")
        override fun take(node: NodeId, dropId: UUID): GroundItemView? = error("not used")
    }

    private class StubSkillsRegistry : AgentSkillsRegistry {
        val xpAddCalls = mutableListOf<Pair<SkillId, Int>>()
        val recommendCalls = mutableListOf<Pair<SkillId, Long>>()

        override fun snapshot(agent: AgentId): AgentSkillsSnapshot =
            AgentSkillsSnapshot(perSkill = emptyMap(), slotCount = 8, slotsFilled = 0)

        override fun addXpIfSlotted(agent: AgentId, skill: SkillId, delta: Int): AddXpResult {
            xpAddCalls += skill to delta
            return AddXpResult.Unslotted
        }

        override fun maybeRecommend(agent: AgentId, skill: SkillId, tick: Long): Int? {
            recommendCalls += skill to tick
            return null
        }

        override fun setSlot(agent: AgentId, skill: SkillId, slotIndex: Int): SkillSlotError? = null
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }
}
