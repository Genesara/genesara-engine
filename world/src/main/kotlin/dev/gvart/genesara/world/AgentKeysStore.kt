package dev.gvart.genesara.world

import dev.gvart.genesara.player.AgentId
import java.util.UUID

/**
 * Persistent store for [AgentKeyInstance]s. Mirrors [EquipmentInstanceStore]:
 * per-instance UUID PK, transactional reads/writes, no ORM caching. Side-channel
 * writes during the build-complete / craft / toggle paths; the surrounding
 * reducer transaction keeps the row insert and the world-state save atomic.
 */
interface AgentKeysStore {

    /** Insert a freshly minted key. PK uniqueness on `instance_id` rejects double-insert. */
    fun insert(key: AgentKeyInstance)

    /** Single-instance lookup. Returns `null` when the row is gone. */
    fun findById(instanceId: UUID): AgentKeyInstance?

    /** Does [agent] hold any key bound to [gateInstanceId]? Hot-path predicate for toggle / passage. */
    fun agentHoldsKeyFor(agent: AgentId, gateInstanceId: UUID): Boolean

    /** Every key held by [agent]. Used by projections and admin tooling. */
    fun listByAgent(agent: AgentId): List<AgentKeyInstance>
}
