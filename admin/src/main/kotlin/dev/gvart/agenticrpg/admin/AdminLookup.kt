package dev.gvart.agenticrpg.admin

interface AdminLookup {
    fun find(id: AdminId): Admin?
    fun findByUsername(username: String): Admin?
    fun count(): Long
}
