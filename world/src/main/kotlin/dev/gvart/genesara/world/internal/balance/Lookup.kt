package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.player.Attribute
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import org.springframework.stereotype.Component
import kotlin.math.roundToInt

internal interface BalanceLookup {
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

    /** Stamina cost of one [WorldCommand.AttackTarget] invocation. Flat in Slice 1. */
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

    /** XP delta granted to the weapon's combat-skill per successful attack. Mirrors craft/build at 1. */
    fun attackXpDelta(): Int = 1

    /**
     * XP delta granted to the parent skill of an ability per successful
     * `use_ability` cast. Mirrors [attackXpDelta] at 1; lets the ability path
     * train its own skill the same way [WorldCommand.AttackTarget] trains the
     * weapon's combat skill.
     */
    fun useAbilityXpDelta(): Int = 1
}

@Component
internal class WorldDefinitionBalanceLookup(
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
