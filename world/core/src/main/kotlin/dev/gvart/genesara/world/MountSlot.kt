package dev.gvart.genesara.world

/**
 * Equipment slots on a tamed mount. Disjoint from [EquipSlot] — mount gear lives
 * in `agent_item_instances` (owned by an agent, equipped onto a mount) but the
 * slot space is mount-shaped, not agent-shaped.
 */
enum class MountSlot {
    /** Riding rig — improves mounted-move efficiency (lower fatigue per move). */
    SADDLE,

    /** Mount armor — flat defense added to mount mitigation in the AttackMount reducer (Stage F). */
    BARDING,

    /** Pack rigging — extends mount cargo capacity. */
    HARNESS,
}
