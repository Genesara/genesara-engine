-- ADR-0004 §1: bootstrap a deterministic owner agent for admin-placed entities.
-- Idempotent via WHERE NOT EXISTS so a re-run (or multi-instance boot) is safe.
-- The owner_id is the zero UUID — a soft cross-module convention for
-- system-owned agents with no real account.player row behind them.
--
-- The NOT EXISTS form (rather than `ON CONFLICT (id) DO NOTHING`) keeps this
-- migration parseable by the jOOQ + H2 DDL interpreter we use at codegen
-- time; H2 mis-translates the conflict-target clause as an ambiguous MERGE.
INSERT INTO agents (id, owner_id, name)
SELECT
    '00000000-0000-0000-0000-000000000abc',
    '00000000-0000-0000-0000-000000000000',
    'admin-sentinel'
WHERE NOT EXISTS (
    SELECT 1 FROM agents WHERE id = '00000000-0000-0000-0000-000000000abc'
);
