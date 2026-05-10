package dev.gvart.genesara.player

/**
 * The eight v1 base classes pinned by the skill-feature design (see
 * `docs/skill-feature-sequence.md` decisions §18 + §19) plus the 24 L50
 * evolution branches added by step 8 / #34 (see
 * `docs/lore/mechanics-reference.md` §4.2 for the table). PSIONICS / MAGE /
 * CLERIC stayed dropped because v1 is low-magic.
 *
 * Declaration order matters: the L10 / L50 fingerprint scorers break ties by
 * [ordinal] (see `ClassFingerprintScorer`). Evolutions are grouped under their
 * parent so the 24 branch ids land in catalog order.
 *
 * The string form is the storage key in `agents.class_id` (VARCHAR(32)); names
 * are stable identifiers, not display strings. Evolutions overwrite the base
 * class in `class_id` — the catalog (`ClassDefinition.parentClass`) is the
 * single source of the parent link, no second column.
 */
enum class AgentClass {
    SOLDIER,
    HEAVY_SOLDIER,
    STEALTH_SOLDIER,
    COMMANDER,

    SCOUT,
    RANGER,
    SNIPER,
    PATHFINDER,

    HUNTER,
    BEASTMASTER,
    TRAPPER,
    POACHER,

    ARTISAN,
    SMITH,
    CHEF,
    JEWELER,

    ENGINEER,
    TECHNICIAN,
    ARTILLERIST,
    ARCHITECT,

    MEDIC,
    SURGEON,
    APOTHECARY,
    FIELD_MEDIC,

    MERCHANT,
    NEGOTIATOR,
    SMUGGLER,
    CARAVAN_MASTER,

    RESEARCHER,
    SCHOLAR,
    ALCHEMIST,
    NATURALIST,
}
