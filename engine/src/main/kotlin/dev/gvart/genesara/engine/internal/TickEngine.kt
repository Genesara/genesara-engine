package dev.gvart.genesara.engine.internal

import dev.gvart.genesara.engine.TickAdvancer
import dev.gvart.genesara.engine.TickClock
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
internal class TickEngine : TickClock, TickAdvancer {
    private val tick = AtomicLong()

    override fun currentTick(): Long = tick.get()

    override fun incrementAndGet(): Long = tick.incrementAndGet()
}
