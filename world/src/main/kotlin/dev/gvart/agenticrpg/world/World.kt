package dev.gvart.agenticrpg.world

import java.time.Instant

data class World(
    val id: WorldId,
    val name: String,
    val nodeCount: Int,
    val nodeSize: Int,
    val frequency: Int,
    val createdAt: Instant,
)
