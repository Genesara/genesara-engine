package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Rarity
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.SpeechMode
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.commands.CombatCommand
import dev.gvart.genesara.world.commands.CoreCommand
import kotlin.math.roundToInt
import org.springframework.stereotype.Component

interface BalanceLookup {
    fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int
    fun staminaRegenPerTick(climate: Climate): Int
    /**
     * Per-item spawn rules for nodes of this terrain. Empty if the terrain produces
     * nothing (DIRT_PATH, BLIGHTED, etc.). Live per-node availability is in the
     * [dev.gvart.genesara.world.internal.resources.NodeResourceStore], not here —
     * this lookup only describes what *can* spawn, with what probability.
     */
    fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule>
    /**
     * Stamina cost of a single `harvest` invocation. Flat in this slice; tuning per
     * (item × terrain × skill) lands when skills do.
     */
    fun harvestStaminaCost(item: ItemId): Int
    /**
     * Quantity yielded by a single `harvest` invocation. Flat (1) in this slice; the
     * call shape exists so skill scaling can route through here when the skill slice
     * lands without touching the harvest reducer.
     */
    fun harvestYield(item: ItemId): Int

    /**
     * Per-tick depletion of the named survival gauge. Always positive; the passive
     * applies it as a negative delta. Flat per gauge in this slice; biome / climate /
     * activity scaling lands later.
     */
    fun gaugeDrainPerTick(gauge: Gauge): Int

    /**
     * At-or-below this value the gauge is "low" — the body is too hungry / thirsty /
     * fatigued to recover normally. Suppresses positive HP/Stamina/Mana regen.
     */
    fun gaugeLowThreshold(gauge: Gauge): Int

    /** At-or-above this value the gauge is "very high" — earning the buff tier. */
    fun gaugeBuffThreshold(gauge: Gauge): Int = 0

    /** Stamina regen multiplier when both hunger and thirst meet [gaugeBuffThreshold]. */
    fun staminaRegenBuffMultiplier(): Double = 1.0

    /**
     * HP damage per tick when any survival gauge has hit zero. Single value applied
     * once per tick (not per starving gauge) — agents starve out, but linearly.
     */
    fun starvationDamagePerTick(): Int

    /**
     * True if [terrain] supports the `drink` verb (i.e. has surface water meaningful
     * enough for an agent to drink directly). Inventory water items work anywhere via
     * `consume` — this flag only governs the in-the-wild drink path.
     */
    fun isWaterSource(terrain: Terrain): Boolean

    /** Stamina cost of one `drink` invocation. Tiny — drinking is trivial when at water. */
    fun drinkStaminaCost(): Int

    /** THIRST refilled by one `drink` invocation. Clamped to the body's max. */
    fun drinkThirstRefill(): Int

    /**
     * Sleep gauge regen per tick while an agent is offline (no active position). Online
     * agents drain sleep at [gaugeDrainPerTick] for [Gauge.SLEEP]; offline agents instead
     * regen at this rate.
     */
    fun sleepRegenPerOfflineTick(): Int

    /**
     * Cadence (in ticks) at which the survival drains — `gaugeDrainPassive` and
     * `sleepPassive` — are applied. The drain amount per execution stays as
     * [gaugeDrainPerTick] / [sleepRegenPerOfflineTick]; only the frequency changes.
     * Default `1` means every tick (back-compat for tests). Production overrides this
     * to stretch a 0–100 gauge across roughly two real-time hours.
     */
    fun survivalDrainPeriodTicks(): Int = 1

    /**
     * True if [terrain] can be entered via the `move` verb. Defaults to true (a missing
     * entry behaves as traversable so partial test fixtures don't accidentally block all
     * movement); the reducer reads this to reject moves into impassable tiles such as
     * [Terrain.OCEAN] (boats land in Phase 3) or [Terrain.CLIFFSIDE].
     */
    fun isTraversable(terrain: Terrain): Boolean

    /**
     * Line-of-sight elevation tier for the terrain. 0 = baseline, 1 = mid (hills /
     * foothills), 2 = peak (mountains, alpine, cliffs, canyons). The vision helper
     * uses this both for the observer's intrinsic effective height and as the
     * intermediate-tile blocking-height base in LOS BFS. Default 0 keeps test
     * stubs that don't care about vision compiling without per-stub overrides;
     * the production [WorldDefinitionBalanceLookup] reads the yaml-driven value.
     */
    fun elevationOf(terrain: Terrain): Int = 0

    /**
     * Character XP subtracted on a partial-XP-bar death. Capped at the agent's
     * `xpCurrent` by the registry's penalty path so we never go negative. The
     * empty-bar branch ignores this and de-levels instead.
     *
     * Default of zero means death tests that don't care about the XP penalty
     * (e.g. movement/passive integration tests with their own stubs) inherit
     * a no-op without having to override; production [WorldDefinitionBalanceLookup]
     * supplies the real value.
     */
    fun xpLossOnDeath(): Int = 0

    /** Grams of carry capacity granted per point of Strength. */
    fun carryGramsPerStrengthPoint(): Int = 0

    /**
     * Multiplier applied to base move stamina cost when the agent is leaving a node
     * that has an ACTIVE road building. `1.0` (default) = no discount; `0.5` halves
     * the cost. The reducer floors the result at 1 so movement always costs something.
     */
    fun roadStaminaMultiplier(): Double = 1.0

    /**
     * Length of the kill-streak rolling window in ticks. A kill outside the window
     * (i.e. `currentTick - windowStartTick >= windowTicks`) starts a fresh streak
     * via `WorldState.incrementKillStreak`, and the death sweep treats an expired
     * window as zero kills via `AgentKillStreak.effectiveKillCount`.
     */
    fun killStreakWindowTicks(): Long = 1000L

    /**
     * Probability in `[0.0, 1.0]` that the death sweep drops an item from the
     * dying agent's pool, given their effective kill count over the streak
     * window. Default ramps linearly: `0.1` per kill, plateauing at `1.0`
     * after 10 kills. Production may override via `WorldDefinitionBalanceLookup`.
     */
    fun dropChanceForKillCount(killCount: Int): Double =
        (killCount * 0.1).coerceIn(0.0, 1.0)

    /** Stamina cost of one [CombatCommand.AttackTarget] invocation. Flat in Slice 1. */
    fun attackStaminaCost(): Int = 5

    /**
     * Multiplier applied against the attacker's combat stat when no MAIN_HAND
     * weapon is equipped. Pairs with [unarmedDamageType] and [unarmedCombatSkill]
     * to define the bare-hand attack profile.
     */
    fun unarmedWeaponPower(): Int = 2

    fun unarmedDamageType(): DamageType = DamageType.BLUNT

    fun unarmedCombatSkill(): SkillId = SkillId("UNARMED")

    /** Reach of an unarmed strike: 1 = same node only. Mirrors the catalog's [Item.range] shape. */
    fun unarmedRange(): Int = 1

    /**
     * Crit chance percentage for an attacker with [luck] points of LUCK. Slice 1
     * uses `clamp(luck, 0, 50)` — roughly 1% per LUCK point, capped at 50% so
     * even a maxed-out lucky attacker can't auto-crit. Future tuning hook for
     * skill-bonus interactions.
     */
    fun critChancePercent(luck: Int): Int = luck.coerceIn(0, 50)

    /**
     * Dodge chance percentage for a defender with [dexterity] points of DEX.
     * Same shape as [critChancePercent]: linear in DEX, capped at 50%. Future
     * tuning hook for SHIELD-skill bonus when block lands.
     */
    fun dodgeChancePercent(dexterity: Int): Int = dexterity.coerceIn(0, 50)

    /** Multiplier on base damage when the crit roll fires. */
    fun critMultiplier(): Int = 2

    /**
     * Per-damage-type multiplier on final damage. Flat 1.0 in Slice 1 — armor
     * resistances and elemental matchups land in later combat slices.
     */
    fun damageTypeModifier(type: DamageType): Double = 1.0

    /**
     * Which attribute scales an attack with [skill]. Slice 1: melee
     * (SWORD/CLUB/SPEAR/UNARMED) scales with STRENGTH; ranged (BOW) scales with
     * DEXTERITY. The melee branch is the deliberate default — most future combat
     * skills will be melee variants — so the `else` is a designed fallthrough,
     * not a typo guard. Unknown stringly-typed skill ids on item rows are caught
     * by [dev.gvart.genesara.world.internal.balance.ResourceSpawnsValidator]
     * before runtime sees them.
     */
    fun combatStatFor(skill: SkillId): Attribute = when (skill.value) {
        "BOW" -> Attribute.DEXTERITY
        else -> Attribute.STRENGTH
    }

    /**
     * Magnitude scaler for equipment-set bonuses, keyed by the average rarity
     * ordinal of the equipped set pieces (rounded to the nearest tier). Curve
     * is `{COMMON: 1.0, UNCOMMON: 1.25, RARE: 1.5, EPIC: 1.75, LEGENDARY: 2.0}`
     * per ADR-0002. Tunable from one place.
     */
    fun rarityMultiplier(avgRarity: Rarity): Double = when (avgRarity) {
        Rarity.COMMON -> 1.0
        Rarity.UNCOMMON -> 1.25
        Rarity.RARE -> 1.5
        Rarity.EPIC -> 1.75
        Rarity.LEGENDARY -> 2.0
    }

    /**
     * Maximum character count of a [dev.gvart.genesara.world.commands.CoreCommand.Say]
     * message. Longer messages are rejected with
     * [dev.gvart.genesara.world.WorldRejection.MessageTooLong]. Cap is per-message;
     * an agent can still spam by calling `say` repeatedly — back-pressure for that
     * lands as a tuning slice (stamina cost, cooldown) later.
     */
    fun maxSayMessageLength(): Int = 500

    /**
     * Node-hop radius reached by a [dev.gvart.genesara.world.commands.CoreCommand.Say]
     * at the given [mode]. The reducer BFS-walks node adjacency out to this depth and
     * routes the event to every agent positioned within. Defaults: WHISPER=1, NORMAL=3,
     * SCREAM=5. Tunable later for psionic / perk-driven amplifiers.
     */
    fun sayRangeFor(mode: SpeechMode): Int = when (mode) {
        SpeechMode.WHISPER -> 1
        SpeechMode.NORMAL -> 3
        SpeechMode.SCREAM -> 5
    }

    /**
     * Sum of item quantities (offered + requested) above which a trade offer must clear
     * the relationship gate. Below this, strangers can trade freely — the gate only
     * exists to keep a one-shot drain from being trivial between unfamiliar parties.
     * `value` is the sum-of-quantities; no item is currently flagged as "high value"
     * in the catalog, so the threshold is on volume.
     *
     * Default sits comfortably above day-to-day swaps (small barters, gifts) so the
     * gate does not block ordinary play before issue #14 lands the real per-pair
     * relationship lookup. Final tuning belongs with the balance pass that follows
     * #14, where designers can decide what counts as "high-value" in light of the
     * mature economy.
     */
    fun trustGateValueThreshold(): Int = 100

    /**
     * Minimum per-pair relationship score required to clear a high-value trade. Score
     * runs −100..+100 (per [`docs/lore/mechanics-reference.md` §19](../../../../../../../../docs/lore/mechanics-reference.md#19-authority--fame));
     * a neutral score (0) does not pass. Tuning lands with the relationship slice.
     */
    fun trustGateRelationshipThreshold(): Int = 25

    /** XP delta granted to the weapon's combat-skill per successful attack. Mirrors craft/build at 1. */
    fun attackXpDelta(): Int = 1

    /**
     * XP delta granted to the parent skill of an ability per successful
     * `use_ability` cast. Mirrors [attackXpDelta] at 1; lets the ability path
     * train its own skill the same way [CombatCommand.AttackTarget] trains the
     * weapon's combat skill.
     */
    fun useAbilityXpDelta(): Int = 1

    /**
     * Active-set radius for NPC simulation. Per-tick load and AI sweep are
     * confined to NPCs within this many adjacency hops of any online agent.
     * Tuning lever for cost vs. simulation breadth.
     */
    fun npcSimulationRadius(): Int = 8

    /** Ticks a node must sit empty before the lazy-on-entry spawner reseeds it. */
    fun npcRespawnTicks(): Long = 500L

    /** Bonus XP to the killer's combat skill on the killing blow against an NPC. */
    fun npcKillXpBonus(): Int = 5

    /** Bonus XP to the killer's HUNTING skill on the killing blow against an NPC. */
    fun huntingKillXp(): Int = 5

    /** Bonus XP to the killer's combat skill on the killing blow against another agent. */
    fun agentKillXpBonus(): Int = 10

    /** TTL (seconds) for ground-loot drops via Redis HEXPIRE. */
    fun groundLootTtlSeconds(): Long = 600L

    /**
     * Mechanics-reference §11 + §19: agents whose Fame is strictly below this
     * threshold lose witness-cascade protection — they can be attacked or
     * killed without bystanders' relationships with the attacker shifting.
     * Default high enough that day-0 agents are unprotected; raisers in §19
     * Authority/Fame let an agent earn protection by building a reputation.
     */
    fun fameWitnessProtectionThreshold(): Int = 10

    /** Per-pair relationship delta applied to every witness on a non-lethal PvP hit. */
    fun relationshipDeltaOnAttackWitnessed(): Int = -2

    /** Per-pair relationship delta applied to every witness when an attack lands the killing blow. */
    fun relationshipDeltaOnKillWitnessed(): Int = -10

    /** Direct attacker↔victim delta on a non-lethal PvP hit — always recorded regardless of fame. */
    fun relationshipDeltaOnAttackDirect(): Int = -5

    /** Direct attacker↔victim delta when the attack is lethal — always recorded regardless of fame. */
    fun relationshipDeltaOnKillDirect(): Int = -20

    /** Per-pair relationship delta on a successfully completed trade. */
    fun relationshipDeltaOnTradeCompleted(): Int = 3

    /**
     * Mechanics-reference §11 outlaw thresholds. Score-bucket boundaries shared
     * by both write paths (`adjustMisconduct`) and the decay sweep so the
     * derived state stays consistent. Lifting either threshold without a
     * migration just reshuffles the buckets on the next write/sweep.
     */
    fun outlawWatchedScore(): Int = 25
    fun outlawOutlawScore(): Int = 100

    /**
     * Misconduct accrual on a non-lethal PvP hit against a Fame-protected
     * victim (i.e. victim.fame meets [fameWitnessProtectionThreshold]).
     * Pairs with the witness cascade, which is gated on the same predicate
     * — kill a "nobody" and no consequence either way (§19).
     */
    fun outlawMisconductOnAttackProtected(): Int = 5

    /** Misconduct accrual on a killing blow against a Fame-protected victim. */
    fun outlawMisconductOnKillProtected(): Int = 50

    /**
     * Cadence (in ticks) of the decay sweep. At each cycle every agent with
     * `outlaw_misconduct_score > 0` gets [outlawDecayPerPeriod] subtracted
     * from their score; transitions downshift state. Mirrors the
     * [mountMaintenancePeriodTicks] shape — a coarse beat is fine here, the
     * score doesn't need per-tick resolution.
     */
    fun outlawDecayPeriodTicks(): Int = 60
    fun outlawDecayPerPeriod(): Int = 1

    /** Stamina spent per `tame` attempt, regardless of success. */
    fun tameStaminaCost(): Int = 15

    /** % chance a failed `tame` spooks the target NPC into fleeing. */
    fun mountSpookChancePercent(): Int = 35

    /** Hop radius a spooked NPC flees through (uses the shared adjacency BFS). */
    fun tameSpookFleeDistance(): Int = 2

    /** XP toward ANIMAL_HANDLING granted on every tame attempt (success or failure). */
    fun tameAttemptXp(): Int = 1

    /** Bonus XP toward ANIMAL_HANDLING granted on a successful tame. */
    fun tameSuccessXp(): Int = 4

    /** Min/max clamp on the tame success-chance percent. Never auto-fail, never guarantee. */
    fun tameMinChancePercent(): Int = 5
    fun tameMaxChancePercent(): Int = 95

    /** Ticks between mount-maintenance sweep cycles. Coarser than agent survival drain; mounts don't need tick precision. */
    fun mountMaintenancePeriodTicks(): Int = 30

    /** Hunger lost per maintenance period (drain). */
    fun mountHungerDrainPerPeriod(): Int = 1

    /** Fatigue regenerated per maintenance period when the mount is idle and fed. */
    fun mountFatigueRegenPerPeriod(): Int = 4

    /** HP regenerated per maintenance period when the mount is well-fed (hunger ≥ buff) AND idle. */
    fun mountHpRegenPerPeriod(): Int = 1

    /** HP lost per maintenance period when hunger has hit zero. */
    fun mountStarvationDamagePerPeriod(): Int = 2

    /** At-or-below this hunger value, fatigue regen halts (mount "slows down"). */
    fun mountHungerLowThreshold(): Int = 25

    /** At-or-above this hunger value, HP regen unlocks (mount is well-fed). */
    fun mountHungerBuffThreshold(): Int = 75

    /** Maximum party size (members + pending invites). v1 = 6. */
    fun partyMaxSize(): Int = 6

    /**
     * Member cap for a clan that owns no base (#22). Until territory (#23) lands per-base
     * capacity (T1=20…T5=320, summing across owned nodes), every clan is base-less, so this
     * baseline is the live cap. v1 = 6.
     */
    fun baselineClanCapacity(): Int = 6

    /** Lifetime of a pending clan invite. Backed by Redis EXPIRE on the invite key. Longer than a
     *  party invite — clan recruitment is remote and less time-pressured. v1 = 600s. */
    fun clanInviteTtlSeconds(): Long = 600L

    /** Lifetime of a pending faction invite (clan→faction join handshake). v1 = 600s. */
    fun factionInviteTtlSeconds(): Long = 600L

    /** Lifetime of a pending party invite. Backed by Redis EXPIRE on the invite key. */
    fun partyInviteTtlSeconds(): Long = 120L

    /**
     * Hop radius from the killer's node within which party members share the kill
     * bonus XP. Members further than this hop count get no share.
     */
    fun partyXpSplitRadius(): Int = 5

    /**
     * Flat damage multiplier (percent) added when two or more spawned party members
     * are co-located in the same node. Applied to outgoing damage after class mod.
     */
    fun partyFormationDamageBonusPercent(): Int = 5
}

@Component
class WorldDefinitionBalanceLookup(
    private val props: WorldDefinitionProperties,
) : BalanceLookup {

    override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int {
        val biomeMul = props.biomes[biome]?.staminaCostMultiplier ?: 0.0
        val climateMul = props.climates[climate]?.staminaDrainPerTick ?: 0.0
        val terrainMul = props.terrains[terrain]?.movementCostMultiplier ?: 1.0

        return (BASE_MOVE_COST * (1.0 + biomeMul) * (1.0 + climateMul) * terrainMul)
            .roundToInt()
            .coerceAtLeast(1)
    }

    override fun staminaRegenPerTick(climate: Climate): Int {
        val drain = props.climates[climate]?.staminaDrainPerTick ?: 0.0
        return (BASE_REGEN_PER_TICK - drain).coerceAtLeast(0.0).roundToInt()
    }

    override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> =
        props.terrains[terrain]?.resourceSpawns.orEmpty().mapNotNull { rule ->
            val item = rule.item ?: return@mapNotNull null
            // Tests build properties by hand and may skip the validator; guard against
            // a malformed quantity-range here rather than throwing on read.
            val (lo, hi) = rule.quantityRange.firstOrNull()?.let { lo ->
                val hi = rule.quantityRange.getOrNull(1) ?: lo
                lo to hi
            } ?: return@mapNotNull null
            ResourceSpawnRule(
                item = item.toItemId(),
                spawnChance = rule.spawnChance,
                quantityRange = lo..hi,
            )
        }

    override fun harvestStaminaCost(item: ItemId): Int = BASE_HARVEST_COST

    override fun harvestYield(item: ItemId): Int = BASE_HARVEST_YIELD

    override fun gaugeDrainPerTick(gauge: Gauge): Int = GAUGE_DRAIN_PER_TICK

    override fun gaugeLowThreshold(gauge: Gauge): Int = GAUGE_LOW_THRESHOLD

    override fun gaugeBuffThreshold(gauge: Gauge): Int = GAUGE_BUFF_THRESHOLD

    override fun staminaRegenBuffMultiplier(): Double = STAMINA_REGEN_BUFF_MULTIPLIER

    override fun starvationDamagePerTick(): Int = STARVATION_DAMAGE_PER_TICK

    override fun isWaterSource(terrain: Terrain): Boolean =
        props.terrains[terrain]?.waterSource == true

    override fun drinkStaminaCost(): Int = DRINK_STAMINA_COST

    override fun drinkThirstRefill(): Int = DRINK_THIRST_REFILL

    override fun sleepRegenPerOfflineTick(): Int = SLEEP_REGEN_PER_OFFLINE_TICK

    override fun survivalDrainPeriodTicks(): Int = SURVIVAL_DRAIN_PERIOD_TICKS

    override fun isTraversable(terrain: Terrain): Boolean =
        props.terrains[terrain]?.traversable ?: true

    override fun elevationOf(terrain: Terrain): Int =
        props.terrains[terrain]?.height ?: 0

    override fun xpLossOnDeath(): Int = XP_LOSS_ON_DEATH

    override fun carryGramsPerStrengthPoint(): Int = CARRY_GRAMS_PER_STRENGTH_POINT

    override fun roadStaminaMultiplier(): Double = ROAD_STAMINA_MULTIPLIER

    private companion object {
        // Must exceed BASE_REGEN_PER_TICK so the spend is observable across the
        // agent's move-then-get-status loop; at parity the regen tick refills the
        // spend before the agent can read it, masking the stamina-driven travel loop.
        const val BASE_MOVE_COST = 3
        const val BASE_REGEN_PER_TICK = 1.0
        const val BASE_HARVEST_COST = 5
        const val BASE_HARVEST_YIELD = 1
        const val GAUGE_DRAIN_PER_TICK = 1
        const val GAUGE_LOW_THRESHOLD = 25
        const val GAUGE_BUFF_THRESHOLD = 75
        const val STAMINA_REGEN_BUFF_MULTIPLIER = 1.5
        const val STARVATION_DAMAGE_PER_TICK = 1
        const val DRINK_STAMINA_COST = 1
        const val DRINK_THIRST_REFILL = 25
        const val SLEEP_REGEN_PER_OFFLINE_TICK = 2
        // 5 s tick × 15 = 75 s per drain unit → ~125 min for a 0–100 gauge to empty.
        const val SURVIVAL_DRAIN_PERIOD_TICKS = 15
        const val XP_LOSS_ON_DEATH = 25
        const val CARRY_GRAMS_PER_STRENGTH_POINT = 5_000
        const val ROAD_STAMINA_MULTIPLIER = 0.5
    }
}
