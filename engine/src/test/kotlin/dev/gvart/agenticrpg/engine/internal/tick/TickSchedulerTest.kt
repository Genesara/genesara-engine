package dev.gvart.agenticrpg.engine.internal.tick

import dev.gvart.agenticrpg.engine.Tick
import dev.gvart.agenticrpg.engine.internal.TickEngine
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TickSchedulerTest {

    @Test
    fun `each advance publishes a Tick with a strictly increasing number`() {
        val engine = TickEngine()
        val publisher = RecordingPublisher()
        val scheduler = TickScheduler(engine, publisher)

        repeat(3) { scheduler.advanceTick() }

        val ticks = publisher.events.filterIsInstance<Tick>()
        assertEquals(listOf(1L, 2L, 3L), ticks.map { it.number })
    }

    @Test
    fun `advanceTick stamps a non-null occurredAt instant`() {
        val publisher = RecordingPublisher()
        val scheduler = TickScheduler(TickEngine(), publisher)

        scheduler.advanceTick()

        val tick = publisher.events.filterIsInstance<Tick>().single()
        // Sanity: the timestamp should be after a known epoch reference.
        assertTrue(tick.occurredAt.epochSecond > 0)
    }

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            events += event
        }
    }
}
