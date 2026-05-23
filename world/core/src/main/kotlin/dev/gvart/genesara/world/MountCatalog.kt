package dev.gvart.genesara.world

interface MountCatalog {
    fun byType(type: MountType): MountDef?

    /** Mount def whose [MountDef.tamedFrom] matches [npcType], if any. */
    fun byTamedFromNpc(npcType: NpcType): MountDef?

    fun all(): Collection<MountDef>

    companion object {
        /**
         * Empty catalog. Default for tests and reducers that don't exercise
         * the mounts code path; production wiring overrides with
         * `InMemoryMountCatalog` loaded from `mounts.yaml`.
         */
        val NoOp: MountCatalog = object : MountCatalog {
            override fun byType(type: MountType): MountDef? = null
            override fun byTamedFromNpc(npcType: NpcType): MountDef? = null
            override fun all(): Collection<MountDef> = emptyList()
        }
    }
}
