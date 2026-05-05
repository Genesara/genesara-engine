package dev.gvart.genesara.world.internal.vision

import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.ClassPropertiesLookup
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.VisionRadius
import dev.gvart.genesara.world.WorldQueryGateway
import org.springframework.stereotype.Component

@Component
internal class VisionRadiusImpl(
    private val classes: ClassPropertiesLookup,
    private val skills: AgentSkillsRegistry,
    private val world: WorldQueryGateway,
) : VisionRadius {

    override fun radiusFor(agent: Agent, currentNode: NodeId): Int {
        val base = classes.sightRange(agent.classId)
        val survivalBonus = skills.slottedSkillLevel(agent.id, SURVIVAL) / SURVIVAL_LEVELS_PER_RING
        val terrainBonus = if (world.node(currentNode)?.terrain == Terrain.MOUNTAIN) 1 else 0
        return base + survivalBonus + terrainBonus
    }

    private companion object {
        val SURVIVAL = SkillId("SURVIVAL")
        const val SURVIVAL_LEVELS_PER_RING = 50
    }
}
