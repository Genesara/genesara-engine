package dev.gvart.genesara.world.internal.tick

import dev.gvart.genesara.engine.TickAdvancer
import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Drives the simulation. Each cycle: advance the global counter (so
 * [dev.gvart.genesara.engine.TickClock] keeps moving for MCP tools), list
 * worlds, INCR each per-world counter, and publish a [WorldTick] for each.
 *
 * `seeder` is constructor-injected only to force its `@PostConstruct` to
 * complete before this bean is wired — see [TickEngineSeeder].
 */
@Component
internal class WorldTickScheduler(
    private val tickAdvancer: TickAdvancer,
    private val worlds: WorldList,
    private val counter: WorldTickCounter,
    private val publisher: ApplicationEventPublisher,
    @Suppress("unused") private val seeder: TickEngineSeeder,
) {

    @Scheduled(fixedRateString = "\${application.tick.interval}")
    fun advanceTick() {
        tickAdvancer.incrementAndGet()
        val now = Instant.now()
        for (worldId in worlds.list()) {
            val number = counter.incrementAndGet(worldId)
            publisher.publishEvent(WorldTick(worldId, number, now))
        }
    }
}

/** Worlds the pod ticks this cycle. #80 narrows this to the leased subset. */
internal interface WorldList {
    fun list(): List<WorldId>
}

@Component
internal class JooqWorldList(
    private val dsl: DSLContext,
) : WorldList {

    override fun list(): List<WorldId> =
        dsl.select(WORLDS.ID)
            .from(WORLDS)
            .orderBy(WORLDS.ID.asc())
            .fetch { WorldId(it[WORLDS.ID]!!) }
}
