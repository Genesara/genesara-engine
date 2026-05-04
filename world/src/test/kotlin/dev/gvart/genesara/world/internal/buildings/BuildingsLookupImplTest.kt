package dev.gvart.genesara.world.internal.buildings

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingCategoryHint
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.BuildingsStore
import dev.gvart.genesara.world.NodeId
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class BuildingsLookupImplTest {

    private val node = NodeId(1L)
    private val agent = AgentId(UUID.randomUUID())

    @Test
    fun `activeStationsAt returns ACTIVE buildings whose categoryHint matches`() {
        val workbench = building(BuildingType.WORKBENCH, BuildingStatus.ACTIVE)
        val campfire = building(BuildingType.CAMPFIRE, BuildingStatus.ACTIVE)
        val store = FakeBuildingsStore(listOf(workbench, campfire))
        val lookup = BuildingsLookupImpl(store, catalogWithDefaults())

        val cooking = lookup.activeStationsAt(node, BuildingCategoryHint.COOKING)
        assertEquals(listOf(campfire), cooking)
    }

    @Test
    fun `activeStationsAt excludes UNDER_CONSTRUCTION matches`() {
        val halfBuilt = building(BuildingType.CAMPFIRE, BuildingStatus.UNDER_CONSTRUCTION, progress = 2)
        val store = FakeBuildingsStore(listOf(halfBuilt))
        val lookup = BuildingsLookupImpl(store, catalogWithDefaults())

        assertEquals(emptyList(), lookup.activeStationsAt(node, BuildingCategoryHint.COOKING))
    }

    @Test
    fun `activeStationsAt returns empty when no def carries the hint`() {
        val store = FakeBuildingsStore(emptyList())
        val lookup = BuildingsLookupImpl(store, catalogWithDefaults())

        assertEquals(emptyList(), lookup.activeStationsAt(node, BuildingCategoryHint.DEFENSIVE))
    }

    private fun catalogWithDefaults() = BuildingsCatalog(
        BuildingDefinitionProperties(
            catalog = mapOf(
                "CAMPFIRE" to defProps(BuildingCategoryHint.COOKING),
                "WORKBENCH" to defProps(BuildingCategoryHint.CRAFTING_STATION_WOOD),
            ),
        ),
    )

    private fun defProps(hint: BuildingCategoryHint) = BuildingProperties(
        requiredSkill = "SURVIVAL",
        totalSteps = 5,
        staminaPerStep = 8,
        hp = 30,
        categoryHint = hint,
        totalMaterials = mapOf("WOOD" to 5),
    )

    private fun building(
        type: BuildingType,
        status: BuildingStatus,
        progress: Int = 5,
    ): Building = Building(
        instanceId = UUID.randomUUID(),
        nodeId = node,
        type = type,
        status = status,
        builtByAgentId = agent,
        builtAtTick = 1L,
        lastProgressTick = 1L,
        progressSteps = if (status == BuildingStatus.ACTIVE) 5 else progress,
        totalSteps = 5,
        hpCurrent = 30,
        hpMax = 30,
    )

    private class FakeBuildingsStore(private val rows: List<Building>) : BuildingsStore {
        override fun insert(building: Building) = error("unused")
        override fun findById(id: UUID): Building? = rows.firstOrNull { it.instanceId == id }
        override fun findInProgress(node: NodeId, agent: AgentId, type: BuildingType): Building? = error("unused")
        override fun listAtNode(node: NodeId): List<Building> = rows.filter { it.nodeId == node }
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<Building>> =
            rows.filter { it.nodeId in nodes }.groupBy { it.nodeId }
        override fun advanceProgress(id: UUID, newProgress: Int, asOfTick: Long): Building? = error("unused")
        override fun complete(id: UUID, asOfTick: Long): Building? = error("unused")
    }
}
