package dev.gvart.agenticrpg.world

import dev.gvart.agenticrpg.world.commands.WorldCommand

interface WorldCommandGateway {
    fun submit(command: WorldCommand, appliesAtTick: Long)
}