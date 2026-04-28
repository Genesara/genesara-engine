package dev.gvart.genesara.world.internal.balance

import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
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

    private companion object {
        const val BASE_MOVE_COST = 1
        const val BASE_REGEN_PER_TICK = 1.0
        const val BASE_GATHER_COST = 5
        const val BASE_GATHER_YIELD = 1
    }
}
