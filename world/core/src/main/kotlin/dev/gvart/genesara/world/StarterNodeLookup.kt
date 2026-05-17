package dev.gvart.genesara.world

import dev.gvart.genesara.player.RaceId

/**
 * Maps a race to its designated starter node — the place a freshly-registered
 * agent of that race spawns into for the first time.
 *
 * Backed by the `world.starter_nodes` table. While the table is empty (early
 * dev / before world seeding), [byRace] returns `null` and the spawn flow
 * falls back to [WorldQueryGateway.randomSpawnableNode].
 */
interface StarterNodeLookup {
    fun byRace(race: RaceId): NodeId?
}
