package dev.gvart.genesara.player

enum class AgentClass {
    WARRIOR,
    SCOUT,
    ENGINEER,
    MERCHANT,
    MAGE,
    CLERIC,
    FARMER,
    COMMANDER,
    RANGER,

    /** Forward-declared for SCANNING's class-lock; full class roster surgery lives in #32. */
    RESEARCHER,
}
