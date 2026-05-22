package dev.gvart.genesara.world

interface MountCatalog {
    fun byType(type: MountType): MountDef?

    /** Mount def whose [MountDef.tamedFrom] matches [npcType], if any. */
    fun byTamedFromNpc(npcType: NpcType): MountDef?

    fun all(): Collection<MountDef>
}
