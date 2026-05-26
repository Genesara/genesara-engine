package dev.gvart.genesara.player

import java.util.UUID

/**
 * Deterministic owner agent used by admin tooling to author entities (buildings,
 * NPCs, chests) that need an `AgentId` foreign key but no real player behind
 * them. Bootstrapped by the player module's Flyway migration `V211`.
 *
 * See ADR-0004 §1.
 */
object AdminSentinel {
    val agentId: AgentId = AgentId(UUID.fromString("00000000-0000-0000-0000-000000000abc"))
}
