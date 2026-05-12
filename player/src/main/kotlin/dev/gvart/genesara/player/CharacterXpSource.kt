package dev.gvart.genesara.player

/**
 * Which agent action caused a [dev.gvart.genesara.player.events.AgentEvent.CharacterXpGained]
 * event. Mirrors `docs/lore/mechanics-reference.md` §3 — a single action grants both
 * character XP and skill XP, and the source disambiguates which verb fired the grant
 * so an agent can correlate without parsing the paired `WorldEvent`.
 */
enum class CharacterXpSource {
    HARVEST,
    CONSUME,
}
