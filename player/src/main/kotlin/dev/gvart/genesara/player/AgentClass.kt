package dev.gvart.genesara.player

/**
 * The eight v1 base classes pinned by the skill-feature design (see
 * `docs/skill-feature-sequence.md` decisions §18 + §19). Class evolutions at
 * level 50 (24 branches, 3 per base class) land with #34 and append further
 * entries here; PSIONICS / MAGE / CLERIC stayed dropped because v1 is low-magic.
 *
 * The string form is the storage key in `agents.class_id` (VARCHAR(32)); names
 * are stable identifiers, not display strings.
 */
enum class AgentClass {
    SOLDIER,
    SCOUT,
    HUNTER,
    ARTISAN,
    ENGINEER,
    MEDIC,
    MERCHANT,
    RESEARCHER,
}
