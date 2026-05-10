-- Persists the level-10 class-choice offer so `select_class` can validate the
-- pick across pod / restart boundaries. Both columns are NULL when there is
-- no pending offer (pre-level-10 or already classed); set together when the
-- level-10 emitter fires; cleared together when the agent commits via
-- `select_class`. We deliberately do not promote these to a JSONB list — the
-- top-2 cardinality is a fixed design rule (#33), and two scalar columns are
-- the cheapest read path on the hot status projection.
ALTER TABLE agents
    ADD COLUMN offered_class_a VARCHAR(32),
    ADD COLUMN offered_class_b VARCHAR(32),
    -- Both columns share the same lifecycle and the pair must be distinct.
    -- The CHECK is the DB-side guard that lets the Kotlin side trust the
    -- read path: `JooqAgentRegistry.toClassOfferOrNull` can return non-null
    -- iff both columns are non-null and different.
    ADD CONSTRAINT agents_class_offer_paired_check CHECK (
        (offered_class_a IS NULL AND offered_class_b IS NULL)
        OR (offered_class_a IS NOT NULL
            AND offered_class_b IS NOT NULL
            AND offered_class_a <> offered_class_b)
    );
