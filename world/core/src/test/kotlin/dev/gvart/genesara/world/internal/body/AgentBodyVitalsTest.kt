package dev.gvart.genesara.world.internal.body

import dev.gvart.genesara.world.Gauge
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentBodyVitalsTest {

    private val full = AgentBody(
        hp = 50, maxHp = 100,
        stamina = 40, maxStamina = 50,
        mana = 0, maxMana = 0,
        hunger = 80, maxHunger = 100,
        thirst = 60, maxThirst = 100,
        sleep = 50, maxSleep = 100,
    )

    @Test
    fun `refill increments the named gauge clamped to max`() {
        val out = full.refill(Gauge.HUNGER, 50)
        assertEquals(100, out.hunger) // clamped
        assertEquals(60, out.thirst)  // unchanged
        assertEquals(50, out.sleep)
    }

    @Test
    fun `refill on zero amount is a no-op`() {
        assertEquals(full, full.refill(Gauge.THIRST, 0))
    }

    @Test
    fun `valueOf returns the right gauge`() {
        assertEquals(80, full.valueOf(Gauge.HUNGER))
        assertEquals(60, full.valueOf(Gauge.THIRST))
        assertEquals(50, full.valueOf(Gauge.SLEEP))
    }

    @Test
    fun `isVitalsLow flips the moment any gauge crosses its per-gauge threshold`() {
        // hunger 80, thirst 60, sleep 50.
        // Uniform 60 threshold: sleep (50) and thirst (60) are both at-or-below.
        val uniform: (Gauge) -> Int = { 60 }
        assertTrue(full.isVitalsLow(uniform))
        val healthy = full.copy(thirst = 70, sleep = 70)
        assertFalse(healthy.isVitalsLow(uniform))
    }

    @Test
    fun `isVitalsLow respects different thresholds per gauge`() {
        // Per-gauge thresholds let one gauge be tight while others are slack.
        // hunger 80, thirst 60, sleep 50.
        val perGauge: (Gauge) -> Int = { gauge ->
            when (gauge) {
                Gauge.HUNGER -> 90  // hunger=80 ≤ 90 → low
                Gauge.THIRST -> 30  // thirst=60 > 30 → fine
                Gauge.SLEEP -> 30   // sleep=50 > 30 → fine
            }
        }
        // Hunger trips it.
        assertTrue(full.isVitalsLow(perGauge))

        val fed = full.copy(hunger = 95)
        assertFalse(fed.isVitalsLow(perGauge))
    }

    @Test
    fun `isStarving is true when any gauge has hit zero`() {
        assertFalse(full.isStarving())
        assertTrue(full.copy(hunger = 0).isStarving())
        assertTrue(full.copy(thirst = 0).isStarving())
        assertTrue(full.copy(sleep = 0).isStarving())
    }

    @Test
    fun `fromProfile starts vitals at the documented defaults`() {
        val profile = dev.gvart.genesara.player.AgentProfile(
            id = dev.gvart.genesara.player.AgentId(java.util.UUID.randomUUID()),
            maxHp = 60, maxStamina = 40, maxMana = 5,
        )
        val body = AgentBody.fromProfile(profile)
        assertEquals(AgentBody.DEFAULT_MAX_HUNGER, body.hunger)
        assertEquals(AgentBody.DEFAULT_MAX_HUNGER, body.maxHunger)
        assertEquals(AgentBody.DEFAULT_MAX_THIRST, body.thirst)
        assertEquals(AgentBody.DEFAULT_MAX_THIRST, body.maxThirst)
        assertEquals(AgentBody.DEFAULT_MAX_SLEEP, body.sleep)
        assertEquals(AgentBody.DEFAULT_MAX_SLEEP, body.maxSleep)
    }
}
