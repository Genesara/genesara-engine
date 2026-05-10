-- Skill-feature step 8 (issue #34): persists the L50 evolution offer in the
-- same shape V207 used for the L10 class offer. Both columns are NULL when
-- there is no pending evolution offer (pre-L50, mid-L50-pending, or already
-- evolved); set together when the L50 emitter fires; cleared together when
-- the agent commits via `select_evolution`.
--
-- Two scalar columns rather than a JSONB list: top-2 cardinality is fixed by
-- the design (mirrors L10), and two columns keep the read on the hot status
-- projection cheap. No second `evolution_class_id` column — `select_evolution`
-- overwrites `class_id` with the evolution class, and the catalog
-- (`ClassDefinition.parentClass`) carries the link back to the base class.
ALTER TABLE agents
    ADD COLUMN offered_evolution_a VARCHAR(32),
    ADD COLUMN offered_evolution_b VARCHAR(32),
    ADD CONSTRAINT agents_evolution_offer_paired_check CHECK (
        (offered_evolution_a IS NULL AND offered_evolution_b IS NULL)
        OR (offered_evolution_a IS NOT NULL
            AND offered_evolution_b IS NOT NULL
            AND offered_evolution_a <> offered_evolution_b)
    );
