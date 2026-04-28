package dev.gvart.genesara.admin

interface AdminLookup {
    fun find(id: AdminId): Admin?
    fun findByUsername(username: String): Admin?
    fun count(): Long
}
