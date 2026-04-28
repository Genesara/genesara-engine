package dev.gvart.agenticrpg.engine

import java.time.Instant

data class Tick(val number: Long, val occurredAt: Instant)