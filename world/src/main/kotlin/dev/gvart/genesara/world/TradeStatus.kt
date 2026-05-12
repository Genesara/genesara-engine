package dev.gvart.genesara.world

/**
 * Lifecycle of a trade offer. Rows are created [PENDING] by the offer reducer and
 * flip terminally to [ACCEPTED] or [REJECTED] by the respond reducer. There is no
 * back transition — a resolved trade is immutable history.
 */
enum class TradeStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
}
