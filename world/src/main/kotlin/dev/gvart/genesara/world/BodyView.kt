package dev.gvart.genesara.world

/**
 * Public, read-only projection of an agent's body state. Returned by
 * [WorldQueryGateway.bodyOf] for sync-read tools like `get_status` so the
 * `:api` module never needs to touch the internal `AgentBody` type.
 *
 * Mana stays as a non-null `Int` for now — the design's "null for non-psionic
 * agents" rule lands together with the class system in Phase 4.
 */
data class BodyView(
    val hp: Int,
    val maxHp: Int,
    val stamina: Int,
    val maxStamina: Int,
    val mana: Int,
    val maxMana: Int,
)
