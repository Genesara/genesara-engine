package dev.gvart.agenticrpg.world.internal.balance

import dev.gvart.agenticrpg.world.Biome
import dev.gvart.agenticrpg.world.Climate
import dev.gvart.agenticrpg.world.Terrain
import org.springframework.stereotype.Component
import kotlin.math.roundToInt

internal interface BalanceLookup {
    fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int
    fun staminaRegenPerTick(climate: Climate): Int
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

    private companion object {
        const val BASE_MOVE_COST = 1
        const val BASE_REGEN_PER_TICK = 1.0
    }
}