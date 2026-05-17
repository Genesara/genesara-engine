package dev.gvart.genesara.world

/**
 * The three survival gauges every agent tracks. High value = healthy; depletes per tick.
 * Below the low-threshold halts HP/Stamina/Mana regen; zero means the body takes
 * starvation damage. See `Passives` for the tick-side logic.
 */
enum class Gauge {
    HUNGER, THIRST, SLEEP,
}
