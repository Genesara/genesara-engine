package dev.gvart.genesara.world.internal.tick.lease

import dev.gvart.genesara.world.WorldId
import dev.gvart.genesara.world.internal.jooq.tables.references.WORLDS
import org.jooq.DSLContext
import org.springframework.stereotype.Component

/**
 * Full enumeration of every world registered in Postgres. The lease
 * manager uses this for discovery (acquire candidates) and the startup
 * seeder uses it to find the highest persisted tick. Distinct from
 * [LeasedWorlds] which is a per-pod runtime view.
 */
interface KnownWorlds {
    fun all(): List<WorldId>
}

@Component
class JooqKnownWorlds(
    private val dsl: DSLContext,
) : KnownWorlds {

    override fun all(): List<WorldId> =
        dsl.select(WORLDS.ID)
            .from(WORLDS)
            .orderBy(WORLDS.ID.asc())
            .fetch { WorldId(it[WORLDS.ID]!!) }
}
