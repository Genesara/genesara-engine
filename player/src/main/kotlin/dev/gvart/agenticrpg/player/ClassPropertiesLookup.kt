package dev.gvart.agenticrpg.player

interface ClassPropertiesLookup {
    /**
     * Sight radius (in nodes) for the given class.
     * Returns the default profile's sight when [classId] is null (pre-level-10 agents).
     */
    fun sightRange(classId: AgentClass?): Int
}
