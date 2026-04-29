package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.Terrain
import org.springframework.stereotype.Component
import kotlin.math.roundToInt

internal interface BalanceLookup {
    fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int
    fun staminaRegenPerTick(climate: Climate): Int
    /** Item ids the basic `gather` verb can produce on this terrain. Empty if nothing. */
    fun gatherablesIn(terrain: Terrain): List<ItemId>
    /**
     * Stamina cost of a single `gather` invocation. Flat in this slice; tuning per
     * (item × terrain × skill) lands when skills do.
     */
    fun gatherStaminaCost(item: ItemId): Int
    /**
     * Quantity yielded by a single `gather` invocation. Flat (1) in this slice; the
     * call shape exists so skill scaling can route through here when the skill slice
     * lands without touching the gather reducer.
     */
    fun gatherYield(item: ItemId): Int

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

    override fun gatherablesIn(terrain: Terrain): List<ItemId> =
        props.terrains[terrain]?.gatherables.orEmpty().map(::ItemId)

    override fun gatherStaminaCost(item: ItemId): Int = BASE_GATHER_COST

    override fun gatherYield(item: ItemId): Int = BASE_GATHER_YIELD

    override fun gaugeDrainPerTick(gauge: Gauge): Int = GAUGE_DRAIN_PER_TICK

    override fun gaugeLowThreshold(gauge: Gauge): Int = GAUGE_LOW_THRESHOLD

    override fun starvationDamagePerTick(): Int = STARVATION_DAMAGE_PER_TICK

    override fun isWaterSource(terrain: Terrain): Boolean =
        props.terrains[terrain]?.waterSource == true

    override fun drinkStaminaCost(): Int = DRINK_STAMINA_COST

    override fun drinkThirstRefill(): Int = DRINK_THIRST_REFILL

    override fun sleepRegenPerOfflineTick(): Int = SLEEP_REGEN_PER_OFFLINE_TICK

    private companion object {
        const val BASE_MOVE_COST = 1
        const val BASE_REGEN_PER_TICK = 1.0
        const val BASE_GATHER_COST = 5
        const val BASE_GATHER_YIELD = 1
        // Survival tuning constants (tagged TBD in mechanics-reference Appendix B):
        const val GAUGE_DRAIN_PER_TICK = 1
        const val GAUGE_LOW_THRESHOLD = 25
        const val STARVATION_DAMAGE_PER_TICK = 1
        const val DRINK_STAMINA_COST = 1
        const val DRINK_THIRST_REFILL = 25
        const val SLEEP_REGEN_PER_OFFLINE_TICK = 2
    }
}
