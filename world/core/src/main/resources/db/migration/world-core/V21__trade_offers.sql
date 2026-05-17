-- Phase 2 trade slice: persisted two-step barter offers between agents.
--
-- Each row is a single offer issued by `offerer_id` to `recipient_id`. Status
-- starts PENDING; the respond reducer flips it to ACCEPTED or REJECTED
-- (one-way, terminal). The same-node co-location requirement is enforced by
-- the reducer at both offer and respond time — this table does not pin
-- locations, agents move freely while an offer is pending.
--
-- offered / requested are JSONB maps shaped `{"<itemId>": <qty>, ...}`. They
-- describe the proposed swap as a value object: read+write atomically at
-- exactly two points (insert at offer, read at respond). Two side-tables
-- would be ceremony — neither inventory rows nor catalog rows reference
-- these payloads.
--
-- agent_id values have no cross-module FK to player.agents (matches the
-- convention used by agent_safe_nodes / agent_node_memory / agent_known_recipes).
CREATE TABLE trade_offers
(
    trade_id          UUID         PRIMARY KEY,
    offerer_id        UUID         NOT NULL,
    recipient_id      UUID         NOT NULL,
    offered           JSONB        NOT NULL,
    requested         JSONB        NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    opened_at_tick    BIGINT       NOT NULL,
    resolved_at_tick  BIGINT       NULL
);

-- Partial indexes serve the hot lookups: "what offers are waiting on me?"
-- and "what offers have I sent that haven't been answered?". Resolved
-- rows are immutable history and don't need to be in these indexes.
CREATE INDEX idx_trade_offers_recipient_pending
    ON trade_offers (recipient_id) WHERE status = 'PENDING';
CREATE INDEX idx_trade_offers_offerer_pending
    ON trade_offers (offerer_id)   WHERE status = 'PENDING';
