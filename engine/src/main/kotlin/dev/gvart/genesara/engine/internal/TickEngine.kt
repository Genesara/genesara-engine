package dev.gvart.genesara.engine.internal

import dev.gvart.genesara.engine.TickClock
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
internal class TickEngine : TickClock {
    private val tick = AtomicLong()

    override fun currentTick(): Long = tick.get()

    fun incrementAndGet(): Long = tick.incrementAndGet()
}
