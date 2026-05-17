package dev.gvart.genesara.world.internal.cultivation

import com.zaxxer.hikari.HikariDataSource
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.AgentPlot
import dev.gvart.genesara.world.Building
import dev.gvart.genesara.world.BuildingStatus
import dev.gvart.genesara.world.BuildingType
import dev.gvart.genesara.world.CropId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.PlantedCrop
import dev.gvart.genesara.world.economy.internal.cultivation.JooqAgentPlotsStore
import dev.gvart.genesara.world.environment.internal.buildings.JooqBuildingsStore
import dev.gvart.genesara.world.internal.jooq.tables.references.AGENT_PLOTS
import dev.gvart.genesara.world.internal.jooq.tables.references.NODE_BUILDINGS
import dev.gvart.genesara.world.internal.testsupport.WorldFlyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class JooqAgentPlotsStoreIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("plots_it")
            .withUsername("test")
            .withPassword("test")

        private lateinit var dataSource: HikariDataSource
        private lateinit var dsl: DSLContext

        @BeforeAll
        @JvmStatic
        fun migrateOnce() {
            dataSource = WorldFlyway.pooledDataSource(postgres)
            WorldFlyway.migrate(dataSource)
            dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @AfterAll
        @JvmStatic
        fun closePool() {
            dataSource.close()
        }
    }

    private lateinit var store: JooqAgentPlotsStore
    private lateinit var buildings: JooqBuildingsStore
    private val node1 = NodeId(101L)
    private val node2 = NodeId(202L)
    private val node3 = NodeId(303L)
    private val agent = AgentId(UUID.randomUUID())
    private val otherAgent = AgentId(UUID.randomUUID())
    private val wheat = CropId("WHEAT")

    @BeforeEach
    fun reset() {
        // CASCADE wipes agent_plots through the FK on building_instance_id.
        dsl.truncate(NODE_BUILDINGS).cascade().execute()
        store = JooqAgentPlotsStore(dsl)
        buildings = JooqBuildingsStore(dsl)
    }

    @Test
    fun `insertEmpty round-trips an empty plot`() {
        val plot = sampleEmptyPlot()
        store.insertEmpty(plot)

        assertEquals(plot, store.findById(plot.plotId))
    }

    @Test
    fun `insertEmpty rejects a planted plot at the Kotlin precondition`() {
        val plot = sampleEmptyPlot().copy(
            plant = PlantedCrop(wheat, plantedAtTick = 1L, lastTendedAtTick = 1L, plantedByAgentId = agent),
        )

        assertFailsWith<IllegalArgumentException> { store.insertEmpty(plot) }
    }

    @Test
    fun `findByBuilding returns the matching plot`() {
        val plot = sampleEmptyPlot()
        store.insertEmpty(plot)

        assertEquals(plot, store.findByBuilding(plot.buildingInstanceId))
    }

    @Test
    fun `plant transitions empty to planted and returns the new state`() {
        val plot = sampleEmptyPlot()
        store.insertEmpty(plot)
        val crop = PlantedCrop(wheat, plantedAtTick = 5L, lastTendedAtTick = 5L, plantedByAgentId = agent)

        val planted = assertNotNull(store.plant(plot.plotId, crop))
        assertEquals(crop, planted.plant)
        assertEquals(plot.plotId, planted.plotId)
    }

    @Test
    fun `plant on an already-planted row returns null without overwriting`() {
        val plot = sampleEmptyPlot()
        store.insertEmpty(plot)
        val first = PlantedCrop(wheat, plantedAtTick = 5L, lastTendedAtTick = 5L, plantedByAgentId = agent)
        store.plant(plot.plotId, first)

        val secondAttempt = store.plant(
            plot.plotId,
            PlantedCrop(CropId("HERB"), plantedAtTick = 10L, lastTendedAtTick = 10L, plantedByAgentId = agent),
        )

        assertNull(secondAttempt)
        assertEquals(first, store.findById(plot.plotId)?.plant)
    }

    @Test
    fun `tend bumps last_tended_at_tick on a planted row`() {
        val plot = sampleEmptyPlot()
        store.insertEmpty(plot)
        store.plant(plot.plotId, PlantedCrop(wheat, plantedAtTick = 5L, lastTendedAtTick = 5L, plantedByAgentId = agent))

        val tended = assertNotNull(store.tend(plot.plotId, tick = 12L))
        assertEquals(12L, tended.plant?.lastTendedAtTick)
        assertEquals(5L, tended.plant?.plantedAtTick)
    }

    @Test
    fun `tend on an empty row returns null`() {
        val plot = sampleEmptyPlot()
        store.insertEmpty(plot)

        assertNull(store.tend(plot.plotId, tick = 12L))
    }

    @Test
    fun `clearPlanting nulls the crop fields and returns the cleared row`() {
        val plot = sampleEmptyPlot()
        store.insertEmpty(plot)
        store.plant(plot.plotId, PlantedCrop(wheat, plantedAtTick = 5L, lastTendedAtTick = 5L, plantedByAgentId = agent))

        val cleared = assertNotNull(store.clearPlanting(plot.plotId))
        assertNull(cleared.plant)
        assertNull(store.findById(plot.plotId)?.plant)
    }

    @Test
    fun `clearPlanting on an empty row returns null`() {
        val plot = sampleEmptyPlot()
        store.insertEmpty(plot)

        assertNull(store.clearPlanting(plot.plotId))
    }

    @Test
    fun `listByNodes batches across nodes and omits nodes with no plots`() {
        val plotA = newPlotAt(node1, agent)
        val plotB = newPlotAt(node1, otherAgent)
        val plotC = newPlotAt(node2, agent)

        val byNode = store.listByNodes(setOf(node1, node2, node3))

        assertEquals(setOf(plotA.plotId, plotB.plotId), byNode[node1]?.map { it.plotId }?.toSet())
        assertEquals(listOf(plotC.plotId), byNode[node2]?.map { it.plotId })
        assertEquals(null, byNode[node3])
    }

    @Test
    fun `listByNodes with empty input short-circuits without DB`() {
        assertEquals(emptyMap(), store.listByNodes(emptySet()))
    }

    @Test
    fun `listPlantedSnapshot returns only planted rows`() {
        val empty = newPlotAt(node1, agent)
        val planted = newPlotAt(node2, agent)
        store.plant(planted.plotId, PlantedCrop(wheat, plantedAtTick = 5L, lastTendedAtTick = 5L, plantedByAgentId = agent))

        val snapshot = store.listPlantedSnapshot()

        assertEquals(listOf(planted.plotId), snapshot.map { it.plotId })
        assertTrue(snapshot.all { it.plant != null })
        assertNull(store.findById(empty.plotId)?.plant)
    }

    @Test
    fun `dropping the building cascades the plot away`() {
        val plot = newPlotAt(node1, agent)

        dsl.deleteFrom(NODE_BUILDINGS)
            .where(NODE_BUILDINGS.INSTANCE_ID.eq(plot.buildingInstanceId))
            .execute()

        assertNull(store.findById(plot.plotId))
    }

    @Test
    fun `biconditional CHECK rejects partial planting via raw SQL`() {
        val plot = newPlotAt(node1, agent)

        assertFailsWith<DataAccessException> {
            dsl.update(AGENT_PLOTS)
                .set(AGENT_PLOTS.PLANTED_CROP, "WHEAT")
                .where(AGENT_PLOTS.PLOT_ID.eq(plot.plotId))
                .execute()
        }
    }

    private fun newPlotAt(nodeId: NodeId, builder: AgentId): AgentPlot {
        val building = Building(
            instanceId = UUID.randomUUID(),
            nodeId = nodeId,
            type = BuildingType.FARM_PLOT,
            status = BuildingStatus.ACTIVE,
            builtByAgentId = builder,
            builtAtTick = 1L,
            lastProgressTick = 1L,
            progressSteps = 6,
            totalSteps = 6,
            hpCurrent = 25,
            hpMax = 25,
        )
        buildings.insert(building)
        val plot = AgentPlot(
            plotId = UUID.randomUUID(),
            buildingInstanceId = building.instanceId,
            nodeId = nodeId,
            plant = null,
        )
        store.insertEmpty(plot)
        return plot
    }

    private fun sampleEmptyPlot(): AgentPlot {
        val building = Building(
            instanceId = UUID.randomUUID(),
            nodeId = node1,
            type = BuildingType.FARM_PLOT,
            status = BuildingStatus.ACTIVE,
            builtByAgentId = agent,
            builtAtTick = 1L,
            lastProgressTick = 1L,
            progressSteps = 6,
            totalSteps = 6,
            hpCurrent = 25,
            hpMax = 25,
        )
        buildings.insert(building)
        return AgentPlot(
            plotId = UUID.randomUUID(),
            buildingInstanceId = building.instanceId,
            nodeId = node1,
            plant = null,
        )
    }
}
