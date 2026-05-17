package dev.gvart.genesara.world

/**
 * Effect applied when an agent consumes one unit of an [Item]: refill [amount] of
 * the named [gauge].
 *
 * `amount` must be strictly positive in v1 — `ConsumablesValidator` enforces this at
 * startup. Negative-amount poison / debuff items are a future feature; revisit the
 * validator when they ship.
 */
data class ConsumableEffect(
    val gauge: Gauge,
    val amount: Int,
)
