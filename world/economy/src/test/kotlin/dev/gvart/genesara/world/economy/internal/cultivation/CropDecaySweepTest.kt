package dev.gvart.genesara.world.economy.internal.cultivation

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.world.AgentPlot
import dev.gvart.genesara.world.AgentPlotsStore
import dev.gvart.genesara.world.Crop
import dev.gvart.genesara.world.CropId
import dev.gvart.genesara.world.CropLookup
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.PlantedCrop
import dev.gvart.genesara.world.Terrain
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CropDecaySweepTest {

    private val agent = AgentId(UUID.randomUUID())
    private val nodeId = NodeId(1L)
    private val wheat = CropId("WHEAT")

    private val crop = Crop(
        id = wheat,
        seedItem = ItemId("WHEAT_SEED"),
        ticksToRipe = 60,
        outputItem = ItemId("WHEAT"),
        baseYield = 4,
        neglectWindowTicks = 30,
        requiredTerrain = setOf(Terrain.PLAINS),
        requiredFarmingLevel = 0,
        gainPerLevel = 0.0,
        maxLuckBonus = 0,
        staminaCostPlant = 6,
        staminaCostTend = 4,
        staminaCostHarvest = 8,
        farmingSkill = SkillId("FARMING"),
    )

    @Test
    fun `kills a plot whose last_tended is past the neglect window AND not yet ripe`() {
        val plotId = UUID.randomUUID()
        val plots = InMemoryPlotsStore().apply {
            insertEmpty(emptyPlot(plotId))
            plant(plotId, PlantedCrop(wheat, plantedAtTick = 0, lastTendedAtTick = 0, plantedByAgentId = agent))
        }
        val sweep = CropDecaySweep(plots, StubCropLookup(crop))

        // tick 31: tended at 0, neglect window 30 → 0+30 < 31 → neglected.
        // plantedAt 0 + 60 = 60, tick 31 < 60 → not ripe. Should die.
        val events = sweep.sweep(tick = 31)

        val event = events.single()
        assertEquals(plotId, event.plotId)
        assertEquals(wheat, event.crop)
        assertEquals(0L, event.neglectedSinceTick)
        assertEquals(31L, event.tick)
        assertNull(plots.findById(plotId)?.plant)
    }

    @Test
    fun `survives when last_tended is within the neglect window`() {
        val plotId = UUID.randomUUID()
        val plots = InMemoryPlotsStore().apply {
            insertEmpty(emptyPlot(plotId))
            plant(plotId, PlantedCrop(wheat, plantedAtTick = 0, lastTendedAtTick = 25, plantedByAgentId = agent))
        }
        val sweep = CropDecaySweep(plots, StubCropLookup(crop))

        // tick 30: tended at 25, neglect window 30 → 25+30=55 >= 30 → not neglected.
        val events = sweep.sweep(tick = 30)

        assertTrue(events.isEmpty())
        assertEquals(wheat, plots.findById(plotId)?.plant?.cropId)
    }

    @Test
    fun `ripe + neglected does NOT die — the agent can leave a ripe crop unharvested`() {
        val plotId = UUID.randomUUID()
        val plots = InMemoryPlotsStore().apply {
            insertEmpty(emptyPlot(plotId))
            plant(plotId, PlantedCrop(wheat, plantedAtTick = 0, lastTendedAtTick = 0, plantedByAgentId = agent))
        }
        val sweep = CropDecaySweep(plots, StubCropLookup(crop))

        // tick 100: planted at 0 + 60 ticks-to-ripe = 60, ripe by tick 60.
        // tended at 0, neglect window 30 → also long-neglected. But ripe wins → survives.
        val events = sweep.sweep(tick = 100)

        assertTrue(events.isEmpty())
        assertEquals(wheat, plots.findById(plotId)?.plant?.cropId)
    }

    @Test
    fun `empty plots are ignored`() {
        val plotId = UUID.randomUUID()
        val plots = InMemoryPlotsStore().apply { insertEmpty(emptyPlot(plotId)) }
        val sweep = CropDecaySweep(plots, StubCropLookup(crop))

        assertTrue(sweep.sweep(tick = 10_000).isEmpty())
    }

    @Test
    fun `dropped crop catalog id clears the plot and emits CropDied — no orphaned slot`() {
        val plotId = UUID.randomUUID()
        val plots = InMemoryPlotsStore().apply {
            insertEmpty(emptyPlot(plotId))
            plant(plotId, PlantedCrop(CropId("PHANTOM"), plantedAtTick = 0, lastTendedAtTick = 0, plantedByAgentId = agent))
        }
        val sweep = CropDecaySweep(plots, StubCropLookup(crop))

        val event = sweep.sweep(tick = 100).single()
        assertEquals(plotId, event.plotId)
        assertEquals(CropId("PHANTOM"), event.crop)
        assertNull(plots.findById(plotId)?.plant)
    }

    private fun emptyPlot(plotId: UUID): AgentPlot = AgentPlot(
        plotId = plotId,
        buildingInstanceId = UUID.randomUUID(),
        nodeId = nodeId,
        plant = null,
    )

    private class StubCropLookup(vararg crops: Crop) : CropLookup {
        private val byId = crops.associateBy { it.id }
        override fun byId(id: CropId): Crop? = byId[id]
        override fun all(): List<Crop> = byId.values.toList()
    }

    private class InMemoryPlotsStore : AgentPlotsStore {
        private val plots = mutableMapOf<UUID, AgentPlot>()
        override fun insertEmpty(plot: AgentPlot) {
            require(plot.plant == null)
            plots[plot.plotId] = plot
        }
        override fun findById(plotId: UUID): AgentPlot? = plots[plotId]
        override fun findByBuilding(buildingInstanceId: UUID): AgentPlot? =
            plots.values.firstOrNull { it.buildingInstanceId == buildingInstanceId }
        override fun listByNodes(nodes: Set<NodeId>): Map<NodeId, List<AgentPlot>> =
            plots.values.filter { it.nodeId in nodes }.groupBy { it.nodeId }
        override fun plant(plotId: UUID, crop: PlantedCrop): AgentPlot? {
            val current = plots[plotId] ?: return null
            if (current.plant != null) return null
            val updated = current.copy(plant = crop)
            plots[plotId] = updated
            return updated
        }
        override fun tend(plotId: UUID, tick: Long): AgentPlot? {
            val current = plots[plotId] ?: return null
            val planted = current.plant ?: return null
            val updated = current.copy(plant = planted.copy(lastTendedAtTick = tick))
            plots[plotId] = updated
            return updated
        }
        override fun clearPlanting(plotId: UUID): AgentPlot? {
            val current = plots[plotId] ?: return null
            if (current.plant == null) return null
            val updated = current.copy(plant = null)
            plots[plotId] = updated
            return updated
        }
        override fun listPlantedSnapshot(): List<AgentPlot> =
            plots.values.filter { it.plant != null }
    }
}
