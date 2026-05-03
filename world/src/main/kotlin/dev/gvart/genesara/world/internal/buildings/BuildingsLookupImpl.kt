package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsLookup
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.NodeId
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class BuildingsLookupImpl(
    private val store: BuildingsStore,
    catalog: BuildingsCatalog,
) : BuildingsLookup {

    private val typesByHint: Map<BuildingCategoryHint, Set<BuildingType>> =
        catalog.allDefs().groupBy { it.categoryHint }.mapValues { (_, defs) -> defs.map { it.type }.toSet() }

    override fun byId(id: UUID): Building? = store.findById(id)

    override fun byNode(node: NodeId): List<Building> = store.listAtNode(node)

    override fun byNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> = store.listByNodes(nodes)

    override fun activeStationsAt(node: NodeId, hint: BuildingCategoryHint): List<Building> {
        val matchingTypes = typesByHint[hint] ?: return emptyList()
        return store.listAtNode(node)
            .filter { it.status == BuildingStatus.ACTIVE && it.type in matchingTypes }
    }
}
