package dev.gvart.genesara.world.environment.internal.mount

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.world.MaintenanceType
import dev.gvart.genesara.world.Mount
import dev.gvart.genesara.world.MountCatalog
import dev.gvart.genesara.world.MountDef
import dev.gvart.genesara.world.MountId
import dev.gvart.genesara.world.MountSlot
import dev.gvart.genesara.world.MountType
import dev.gvart.genesara.world.NodeId
import dev.gvart.genesara.world.NpcType
import dev.gvart.genesara.world.events.EnvironmentEvent
import dev.gvart.genesara.world.events.MountDeathCause
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MountMaintenanceSweepTest {

    private val rider = AgentId(UUID.randomUUID())
    private val node = NodeId(1L)
    private val type = MountType("RIDING_HORSE")

    @Test
    fun `period gate — sweep is a no-op on ticks that are not multiples of the period`() {
        val store = InMemoryMountStore().apply { insert(mount(hunger = 100, fatigue = 100)) }
        val sweep = sweep(store)

        // Default mountMaintenancePeriodTicks is 30.
        assertTrue(sweep.sweep(tick = 1L).isEmpty(), "non-multiple tick is a no-op")
        assertTrue(sweep.sweep(tick = 29L).isEmpty(), "still no-op")
        assertEquals(100, store.all().first().hunger, "hunger untouched between periods")

        sweep.sweep(tick = 30L)
        assertTrue(store.all().first().hunger < 100, "period tick drains hunger")
    }

    @Test
    fun `idle and fed mount regenerates fatigue and HP`() {
        val m = mount(hunger = 90, fatigue = 50, hpCurrent = 30)
        val store = InMemoryMountStore().apply { insert(m) }
        val sweep = sweep(store)

        sweep.sweep(tick = 30L)

        val after = assertNotNull(store.findById(m.id))
        assertTrue(after.fatigue > 50, "fatigue regenerates while idle + fed")
        assertTrue(after.hpCurrent > 30, "HP regenerates while well-fed (hunger ≥ buff) + idle")
    }

    @Test
    fun `ridden mount does NOT regenerate fatigue`() {
        val m = mount(hunger = 90, fatigue = 50, hpCurrent = 50, mountedBy = rider)
        val store = InMemoryMountStore().apply { insert(m) }
        val sweep = sweep(store)

        sweep.sweep(tick = 30L)

        val after = assertNotNull(store.findById(m.id))
        assertEquals(50, after.fatigue, "ridden mount fatigue unchanged")
    }

    @Test
    fun `hunger-zero ridden mount dies — emits MountDied AND TransportDismounted for rider (B2)`() {
        val m = mount(hunger = 0, fatigue = 50, hpCurrent = 2, mountedBy = rider)
        val store = InMemoryMountStore().apply { insert(m) }
        val sweep = sweep(store)

        val events = sweep.sweep(tick = 30L)

        assertNull(store.findById(m.id), "mount row deleted on permadeath")
        val died = events.filterIsInstance<EnvironmentEvent.MountDied>().single()
        assertEquals(MountDeathCause.STARVATION, died.cause)
        assertEquals(m.id, died.mount)
        val dismounted = events.filterIsInstance<EnvironmentEvent.TransportDismounted>().single()
        assertEquals(rider, dismounted.agent)
        assertEquals(m.id, dismounted.mount)
        assertNull(dismounted.causedBy, "starvation dismount has no command id")
    }

    @Test
    fun `hunger-zero unridden mount dies — no TransportDismounted`() {
        val m = mount(hunger = 0, fatigue = 50, hpCurrent = 2, mountedBy = null)
        val store = InMemoryMountStore().apply { insert(m) }
        val sweep = sweep(store)

        val events = sweep.sweep(tick = 30L)

        assertTrue(events.any { it is EnvironmentEvent.MountDied })
        assertTrue(events.none { it is EnvironmentEvent.TransportDismounted })
    }

    private fun mount(
        hunger: Int = 100,
        fatigue: Int = 100,
        hpCurrent: Int = 50,
        mountedBy: AgentId? = null,
    ): Mount = Mount(
        id = MountId(UUID.randomUUID()),
        type = type,
        nodeId = node,
        hpCurrent = hpCurrent,
        hpMax = 50,
        hunger = hunger,
        hungerMax = 100,
        fatigue = fatigue,
        fatigueMax = 100,
        mountedByAgentId = mountedBy,
        tamedAtTick = 0L,
    )

    private fun sweep(store: InMemoryMountStore): MountMaintenanceSweep =
        MountMaintenanceSweep(
            mounts = store,
            catalog = catalog(),
            balance = stubBalance(),
            deathCleanup = noOpCleanup,
        )

    private val noOpCleanup = MountDeathCleanup(
        instances = dev.gvart.genesara.world.internal.testsupport.InMemoryAgentItemInstancesStore(),
        mountInventory = dev.gvart.genesara.world.MountInventoryStore.NoOp,
        groundItems = dev.gvart.genesara.world.internal.testsupport.NoOpGroundItemStore,
    )

    private fun catalog(): MountCatalog = object : MountCatalog {
        private val def = MountDef(
            type = type,
            displayName = "Horse",
            tamedFrom = NpcType("WILD_HORSE"),
            tameability = 50,
            hpMax = 50,
            hungerMax = 100,
            fatigueMax = 100,
            speedFactor = 0.6,
            carryCapacityGrams = 50_000,
            defense = 0,
            gearSlots = setOf(MountSlot.SADDLE),
            maintenanceType = MaintenanceType.ANIMAL,
        )
        override fun byType(type: MountType): MountDef? = if (type == this@MountMaintenanceSweepTest.type) def else null
        override fun byTamedFromNpc(npcType: NpcType): MountDef? = null
        override fun all(): Collection<MountDef> = listOf(def)
    }
}
