package dev.gvart.genesara.world.internal.perks

import dev.gvart.genesara.player.AgentId
import dev.gvart.genesara.player.Perk
import dev.gvart.genesara.player.PerkCooldownStore
import dev.gvart.genesara.player.PerkEffect
import dev.gvart.genesara.player.PerkId
import dev.gvart.genesara.player.SkillId
import dev.gvart.genesara.player.TriggeredPassiveEffectKind
import dev.gvart.genesara.player.TriggeredPassiveLookup
import dev.gvart.genesara.player.TriggeredPassiveTrigger
import dev.gvart.genesara.player.TriggeredPerk
import dev.gvart.genesara.world.events.WorldEvent
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriggeredPassiveDispatcherImplTest {

    private val agent = AgentId(UUID.randomUUID())
    private val target = AgentId(UUID.randomUUID())
    private val cause = UUID.randomUUID()

    private val bleeder = triggeredPerk(
        id = "SWORD_BLEEDER",
        trigger = TriggeredPassiveTrigger.ON_HIT_DEALT,
        effectKind = TriggeredPassiveEffectKind.APPLY_STATUS_TO_TARGET,
        cd = 8,
        params = mapOf("status" to "BLEED", "duration-ticks" to "10"),
    )

    @Test
    fun `emits PerkTriggered when perk matches and cooldown ready`() {
        val cd = StubCooldownStore(initialReady = true)
        val dispatcher = TriggeredPassiveDispatcherImpl(StubLookup(listOf(bleeder)), cd)

        val events = dispatcher.dispatch(
            firer = agent,
            trigger = TriggeredPassiveTrigger.ON_HIT_DEALT,
            ctx = TriggerContext.Combat(target),
            tick = 100L,
            causedBy = cause,
        )

        assertEquals(1, events.size)
        val ev = events.single() as WorldEvent.PerkTriggered
        assertEquals(agent, ev.agent)
        assertEquals(bleeder.perk.id, ev.perkId)
        assertEquals(TriggeredPassiveTrigger.ON_HIT_DEALT, ev.trigger)
        assertEquals(TriggeredPassiveEffectKind.APPLY_STATUS_TO_TARGET, ev.effectKind)
        assertEquals(target, ev.target)
        assertEquals(100L, ev.tick)
        assertEquals(cause, ev.causedBy)
        assertEquals(108L, cd.armedUntil[agent to bleeder.perk.id], "cd armed at fire-tick + internalCooldownTicks")
    }

    @Test
    fun `skips perk when cooldown is not ready`() {
        val cd = StubCooldownStore(initialReady = false)
        val dispatcher = TriggeredPassiveDispatcherImpl(StubLookup(listOf(bleeder)), cd)

        val events = dispatcher.dispatch(
            firer = agent,
            trigger = TriggeredPassiveTrigger.ON_HIT_DEALT,
            ctx = TriggerContext.Combat(target),
            tick = 100L,
            causedBy = cause,
        )

        assertTrue(events.isEmpty())
        assertNull(cd.armedUntil[agent to bleeder.perk.id], "skipped fires must not arm cooldown")
    }

    @Test
    fun `all matching perks fire on the same trigger (no first-match-wins)`() {
        val rage = triggeredPerk(
            id = "SWORD_RAGE",
            trigger = TriggeredPassiveTrigger.ON_HIT_DEALT,
            effectKind = TriggeredPassiveEffectKind.GRANT_SELF_BUFF,
            cd = 5,
            params = mapOf("buff" to "RAGE"),
        )
        val dispatcher = TriggeredPassiveDispatcherImpl(
            StubLookup(listOf(bleeder, rage)),
            StubCooldownStore(initialReady = true),
        )

        val events = dispatcher.dispatch(
            firer = agent,
            trigger = TriggeredPassiveTrigger.ON_HIT_DEALT,
            ctx = TriggerContext.Combat(target),
            tick = 0L,
            causedBy = cause,
        )

        val perks = events.map { (it as WorldEvent.PerkTriggered).perkId }.toSet()
        assertEquals(setOf(bleeder.perk.id, rage.perk.id), perks)
    }

    @Test
    fun `target field is null for non-combat triggers`() {
        val onHarvest = triggeredPerk(
            id = "FORAGING_LUCKY",
            trigger = TriggeredPassiveTrigger.ON_HARVEST_COMPLETE,
            effectKind = TriggeredPassiveEffectKind.GRANT_BONUS_ITEM,
            cd = 0,
            params = mapOf("pool" to "BERRIES"),
        )
        val dispatcher = TriggeredPassiveDispatcherImpl(
            StubLookup(listOf(onHarvest)),
            StubCooldownStore(initialReady = true),
        )

        val ev = dispatcher.dispatch(
            firer = agent,
            trigger = TriggeredPassiveTrigger.ON_HARVEST_COMPLETE,
            ctx = TriggerContext.None,
            tick = 0L,
            causedBy = cause,
        ).single() as WorldEvent.PerkTriggered
        assertNull(ev.target)
    }

    @Test
    fun `OnLowHp fires only on the descending edge of the threshold band`() {
        val perk = triggeredPerk(
            id = "SOLDIER_LAST_STAND",
            trigger = TriggeredPassiveTrigger.ON_LOW_HP,
            effectKind = TriggeredPassiveEffectKind.HEAL_SELF,
            cd = 30,
            params = mapOf("thresholdPct" to "25", "amount" to "40"),
        )
        val dispatcher = TriggeredPassiveDispatcherImpl(
            StubLookup(listOf(perk)),
            StubCooldownStore(initialReady = true),
        )

        val crossingDown = dispatcher.dispatch(
            firer = agent,
            trigger = TriggeredPassiveTrigger.ON_LOW_HP,
            ctx = TriggerContext.HpChange(maxHp = 100, prevHp = 30, newHp = 20),
            tick = 0L,
            causedBy = cause,
        )
        assertEquals(1, crossingDown.size)

        val stillBelow = dispatcher.dispatch(
            firer = agent,
            trigger = TriggeredPassiveTrigger.ON_LOW_HP,
            ctx = TriggerContext.HpChange(maxHp = 100, prevHp = 18, newHp = 10),
            tick = 1L,
            causedBy = cause,
        )
        assertTrue(stillBelow.isEmpty(), "subsequent damage while still below threshold must not re-fire")

        val notCrossed = dispatcher.dispatch(
            firer = agent,
            trigger = TriggeredPassiveTrigger.ON_LOW_HP,
            ctx = TriggerContext.HpChange(maxHp = 100, prevHp = 80, newHp = 50),
            tick = 2L,
            causedBy = cause,
        )
        assertTrue(notCrossed.isEmpty())
    }

    @Test
    fun `OnLowHp inside the band but on cooldown does not fire`() {
        val perk = triggeredPerk(
            id = "SOLDIER_LAST_STAND",
            trigger = TriggeredPassiveTrigger.ON_LOW_HP,
            effectKind = TriggeredPassiveEffectKind.HEAL_SELF,
            cd = 30,
            params = mapOf("thresholdPct" to "25"),
        )
        val cd = StubCooldownStore(initialReady = false)
        val dispatcher = TriggeredPassiveDispatcherImpl(StubLookup(listOf(perk)), cd)

        val events = dispatcher.dispatch(
            firer = agent,
            trigger = TriggeredPassiveTrigger.ON_LOW_HP,
            ctx = TriggerContext.HpChange(maxHp = 100, prevHp = 30, newHp = 20),
            tick = 0L,
            causedBy = cause,
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `OnLowHp without threshold param skips silently`() {
        val malformed = triggeredPerk(
            id = "BROKEN",
            trigger = TriggeredPassiveTrigger.ON_LOW_HP,
            effectKind = TriggeredPassiveEffectKind.HEAL_SELF,
            cd = 0,
            params = emptyMap(),
        )
        val dispatcher = TriggeredPassiveDispatcherImpl(
            StubLookup(listOf(malformed)),
            StubCooldownStore(initialReady = true),
        )

        val events = dispatcher.dispatch(
            firer = agent,
            trigger = TriggeredPassiveTrigger.ON_LOW_HP,
            ctx = TriggerContext.HpChange(maxHp = 100, prevHp = 50, newHp = 5),
            tick = 0L,
            causedBy = cause,
        )
        assertTrue(events.isEmpty())
    }

    private fun triggeredPerk(
        id: String,
        trigger: TriggeredPassiveTrigger,
        effectKind: TriggeredPassiveEffectKind,
        cd: Int,
        params: Map<String, String>,
    ): TriggeredPerk {
        val effect = PerkEffect.TriggeredPassive(trigger, effectKind, params, cd)
        val perk = Perk(
            id = PerkId(id),
            skill = SkillId("SWORD"),
            milestoneLevel = 50,
            displayName = id,
            description = id,
            effect = effect,
        )
        return TriggeredPerk(perk, effect)
    }

    // Returns whatever it was constructed with — the lookup contract guarantees
    // pre-filtering by trigger; double-filtering here would mask dispatcher bugs.
    private class StubLookup(private val perks: List<TriggeredPerk>) : TriggeredPassiveLookup {
        override fun matching(agent: AgentId, trigger: TriggeredPassiveTrigger): List<TriggeredPerk> =
            perks
    }

    private class StubCooldownStore(private val initialReady: Boolean) : PerkCooldownStore {
        val armedUntil = mutableMapOf<Pair<AgentId, PerkId>, Long>()
        override fun isReady(agent: AgentId, perk: PerkId, tick: Long): Boolean {
            val until = armedUntil[agent to perk] ?: return initialReady
            return tick >= until
        }
        override fun arm(agent: AgentId, perk: PerkId, untilTick: Long) {
            armedUntil[agent to perk] = untilTick
        }
    }
}
