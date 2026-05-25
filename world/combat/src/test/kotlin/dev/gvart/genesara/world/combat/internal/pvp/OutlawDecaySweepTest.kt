package dev.gvart.genesara.world.combat.internal.pvp

import dev.gvart.genesara.account.PlayerId
import dev.gvart.genesara.player.Agent
import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.AgentRegistry
import dev.gvart.genesara.player.MisconductOutcome
import dev.gvart.genesara.player.OutlawState
import dev.gvart.genesara.world.Biome
import dev.gvart.genesara.world.Climate
import dev.gvart.genesara.world.DamageType
import dev.gvart.genesara.world.Gauge
import dev.gvart.genesara.world.ItemId
import dev.gvart.genesara.world.ResourceSpawnRule
import dev.gvart.genesara.world.Terrain
import dev.gvart.genesara.world.events.SocialEvent
import dev.gvart.genesara.world.internal.balance.BalanceLookup
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutlawDecaySweepTest {

    private val balance = TunableBalance()

    @Test
    fun `does nothing on a non-period tick`() {
        val registry = RecordingRegistry()
        val sweep = OutlawDecaySweep(registry, balance)

        val events = sweep.sweep(tick = 7L)

        assertTrue(events.isEmpty())
        assertEquals(0, registry.calls, "off-period tick must not touch the registry")
    }

    @Test
    fun `period tick decays misconduct and emits only the transitions`() {
        val transitioner = AgentId(UUID.randomUUID())
        val downshifter = AgentId(UUID.randomUUID())
        val sameBucket = AgentId(UUID.randomUUID())
        val registry = RecordingRegistry(
            scriptedOutcomes = listOf(
                MisconductOutcome(transitioner, oldScore = 25, newScore = 24, oldState = OutlawState.WATCHED, newState = OutlawState.CLEAN),
                MisconductOutcome(sameBucket, oldScore = 50, newScore = 49, oldState = OutlawState.WATCHED, newState = OutlawState.WATCHED),
                MisconductOutcome(downshifter, oldScore = 100, newScore = 99, oldState = OutlawState.OUTLAW, newState = OutlawState.WATCHED),
            ),
        )
        val sweep = OutlawDecaySweep(registry, balance)

        val events = sweep.sweep(tick = 60L)

        assertEquals(2, events.size, "only transitioning outcomes become events")
        val byAgent = events.filterIsInstance<SocialEvent.OutlawStateChanged>().associateBy { it.agent }
        assertEquals(OutlawState.CLEAN, byAgent[transitioner]?.newState)
        assertEquals(OutlawState.WATCHED, byAgent[downshifter]?.newState)
        assertTrue(events.all { (it as SocialEvent.OutlawStateChanged).causedBy == null }, "decay sweep is not caused by any command")
        assertTrue(events.all { (it as SocialEvent.OutlawStateChanged).listeners.size == 1 }, "events are private to the affected agent")
    }

    @Test
    fun `zero decay amount short-circuits — no registry call`() {
        val registry = RecordingRegistry()
        val zeroDecay = object : BalanceLookup by balance {
            override fun outlawDecayPerPeriod(): Int = 0
        }
        val sweep = OutlawDecaySweep(registry, zeroDecay)

        sweep.sweep(tick = 60L)

        assertEquals(0, registry.calls)
    }

    private class RecordingRegistry(
        private val scriptedOutcomes: List<MisconductOutcome> = emptyList(),
    ) : AgentRegistry {
        var calls: Int = 0
            private set

        override fun find(id: AgentId): Agent? = null
        override fun listForOwner(owner: PlayerId): List<Agent> = emptyList()
        override fun decayMisconductScores(
            amount: Int,
            watchedAt: Int,
            outlawAt: Int,
        ): List<MisconductOutcome> {
            calls++
            return scriptedOutcomes
        }
    }

    private class TunableBalance : BalanceLookup {
        override fun moveStaminaCost(biome: Biome, climate: Climate, terrain: Terrain): Int = 1
        override fun staminaRegenPerTick(climate: Climate): Int = 0
        override fun resourceSpawnsFor(terrain: Terrain): List<ResourceSpawnRule> = emptyList()
        override fun harvestStaminaCost(item: ItemId): Int = 1
        override fun harvestYield(item: ItemId): Int = 1
        override fun gaugeDrainPerTick(gauge: Gauge): Int = 0
        override fun gaugeLowThreshold(gauge: Gauge): Int = 0
        override fun starvationDamagePerTick(): Int = 0
        override fun isWaterSource(terrain: Terrain): Boolean = false
        override fun drinkStaminaCost(): Int = 1
        override fun drinkThirstRefill(): Int = 1
        override fun sleepRegenPerOfflineTick(): Int = 0
        override fun isTraversable(terrain: Terrain): Boolean = true
        override fun outlawDecayPeriodTicks(): Int = 60
        override fun outlawDecayPerPeriod(): Int = 1
    }
}
