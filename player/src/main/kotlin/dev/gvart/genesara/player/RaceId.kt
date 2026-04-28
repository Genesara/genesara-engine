package dev.gvart.genesara.player

/**
 * Stable string identifier for a race; resolved against the YAML race catalog
 * (`player-definition/races.yaml`) at runtime. Stored as `agents.race_id` and
 * `starter_nodes.race_id` (both soft references — no DB foreign keys).
 */
@JvmInline
value class RaceId(val value: String)
