package dev.gvart.genesara.world

/**
 * The `(action → minimum clan rank)` permission table (#22), the single source of truth
 * reused across the clan-internal reducers. The minimum rank is the floor to *attempt* the
 * action; relational rules the table can't express (kick/promote/demote require the target
 * to rank strictly below the actor; promote may not mint an Archon — that is
 * [TRANSFER_LEADERSHIP]) are enforced in the reducers on top of this floor.
 */
enum class ClanAction(val minRank: ClanRank) {
    INVITE(ClanRank.BOUND),
    KICK(ClanRank.VANGUARD),
    PROMOTE(ClanRank.VANGUARD),
    DEMOTE(ClanRank.VANGUARD),
    DISSOLVE(ClanRank.ARCHON),
    TRANSFER_LEADERSHIP(ClanRank.ARCHON),
}
