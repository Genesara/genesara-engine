package dev.gvart.genesara.world.internal.vision

import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentSkillsRegistry
import dev.gvart.genesara.player.ClassLookup
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.Node
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.VisibleNodes
import dev.gvart.genesara.world.WorldQueryGateway
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import dev.gvart.genesara.world.internal.buildings.BuildingsCatalog
import org.springframework.stereotype.Component

@Component
internal class VisibleNodesImpl(
    private val classes: ClassLookup,
    private val skills: AgentSkillsRegistry,
    private val world: WorldQueryGateway,
    private val balance: BalanceLookup,
    private val catalog: BuildingsCatalog,
    private val buildings: BuildingsLookup,
    private val blockerCache: VisionBlockerCache,
) : VisibleNodes {

    override fun visibleNodesFor(
        agent: Agent,
        currentNode: NodeId,
        activeBuildingsAtCurrentNode: List<Building>,
    ): Set<NodeId> {
        val origin = world.node(currentNode) ?: return setOf(currentNode)
        val originBuildings = activeBuildingsAtCurrentNode.ifEmpty { buildings.byNode(currentNode) }

        val radius = computeRadius(agent, origin.terrain, originBuildings)
        if (radius <= 0) return setOf(currentNode)

        val observerHeight = effectiveObserverHeight(origin.terrain, originBuildings)
        val geometricCandidates = geometricBfs(currentNode, radius)
        val blockerHeights = blockerCache.blockerHeights(geometricCandidates)
        return losBfs(currentNode, radius, observerHeight, blockerHeights)
    }

    private fun computeRadius(agent: Agent, terrain: Terrain, originBuildings: List<Building>): Int {
        val base = classes.sightRange(agent.classId)
        val survivalBonus = skills.slottedSkillLevel(agent.id, SURVIVAL) / SURVIVAL_LEVELS_PER_RING
        val terrainBonus = if (terrain == Terrain.MOUNTAIN) 1 else 0
        val watchtowerBonus = if (hasActiveWatchtower(originBuildings)) WATCHTOWER_RADIUS_AURA else 0
        return base + survivalBonus + terrainBonus + watchtowerBonus
    }

    private fun effectiveObserverHeight(terrain: Terrain, originBuildings: List<Building>): Int {
        val terrainHeight = balance.elevationOf(terrain)
        val buildingBonus = originBuildings
            .filter { it.status == BuildingStatus.ACTIVE }
            .sumOf { catalog.def(it.type).observerHeightBonus }
        return terrainHeight + buildingBonus
    }

    private fun hasActiveWatchtower(buildings: List<Building>): Boolean =
        buildings.any {
            it.status == BuildingStatus.ACTIVE && catalog.def(it.type).observerHeightBonus > 0
        }

    private fun geometricBfs(origin: NodeId, radius: Int): Set<NodeId> {
        val seen = HashSet<NodeId>().apply { add(origin) }
        var frontier: Set<NodeId> = setOf(origin)
        for (step in 1..radius) {
            val next = HashSet<NodeId>()
            for (from in frontier) {
                val node = world.node(from) ?: continue
                for (neighbour in node.adjacency) {
                    if (seen.add(neighbour)) next += neighbour
                }
            }
            if (next.isEmpty()) break
            frontier = next
        }
        return seen
    }

    private fun losBfs(
        origin: NodeId,
        radius: Int,
        observerHeight: Int,
        blockerHeights: Map<NodeId, Int>,
    ): Set<NodeId> {
        val visible = HashSet<NodeId>().apply { add(origin) }
        var frontier: Set<NodeId> = setOf(origin)
        for (step in 1..radius) {
            val nextFrontier = HashSet<NodeId>()
            for (from in frontier) {
                val node = world.node(from) ?: continue
                for (neighbour in node.adjacency) {
                    // Silhouette rule: the wall tile itself is always visible — it
                    // only stops propagation past itself.
                    if (!visible.add(neighbour)) continue
                    val neighbourNode = world.node(neighbour) ?: continue
                    val blocking = balance.elevationOf(neighbourNode.terrain) + (blockerHeights[neighbour] ?: 0)
                    if (blocking <= observerHeight) nextFrontier += neighbour
                }
            }
            if (nextFrontier.isEmpty()) break
            frontier = nextFrontier
        }
        return visible
    }

    private companion object {
        val SURVIVAL = SkillId("SURVIVAL")
        const val SURVIVAL_LEVELS_PER_RING = 50
        const val WATCHTOWER_RADIUS_AURA = 2
    }
}
