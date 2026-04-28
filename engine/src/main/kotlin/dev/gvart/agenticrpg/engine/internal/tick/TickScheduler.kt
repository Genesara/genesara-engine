package dev.gvart.agenticrpg.engine.internal.tick

import dev.gvart.agenticrpg.engine.Tick
import dev.gvart.agenticrpg.engine.internal.TickEngine
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@Component
internal class TickScheduler(
    private val engine: TickEngine,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Scheduled(fixedRateString = "\${application.tick.interval}")
    fun advanceTick() {
        eventPublisher.publishEvent(Tick(engine.incrementAndGet(), Instant.now()))
    }
}