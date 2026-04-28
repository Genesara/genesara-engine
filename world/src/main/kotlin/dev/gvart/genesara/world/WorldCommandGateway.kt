package dev.gvart.genesara.world

import dev.gvart.genesara.world.commands.WorldCommand

interface WorldCommandGateway {
    fun submit(command: WorldCommand, appliesAtTick: Long)
}