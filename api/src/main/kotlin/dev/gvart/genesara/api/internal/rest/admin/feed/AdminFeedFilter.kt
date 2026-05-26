package dev.gvart.genesara.api.internal.rest.admin.feed

import java.util.UUID

internal data class AdminFeedFilter(
    val types: Set<String>? = null,
    val agent: UUID? = null,
    val node: Long? = null,
) {
    fun matches(event: AdminFeedEvent): Boolean {
        if (types != null && event.type !in types) return false
        if (agent != null && event.agent != agent) return false
        if (node != null && event.node != node) return false
        return true
    }

    companion object {
        val NONE = AdminFeedFilter()
    }
}
