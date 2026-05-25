-- Phase 2 PvP infrastructure (#17): per-agent outlaw status state machine.
-- Spec: mechanics-reference §11 (PvP). Score is the source of truth, decayed
-- by the world-tick OutlawDecaySweep; state is the denormalized cache the
-- registry keeps in sync (CLEAN/WATCHED/OUTLAW) so inspect reads cheap.
ALTER TABLE agents
    ADD COLUMN outlaw_state            TEXT NOT NULL DEFAULT 'CLEAN',
    ADD COLUMN outlaw_misconduct_score INT  NOT NULL DEFAULT 0;

ALTER TABLE agents
    ADD CONSTRAINT agents_outlaw_misconduct_score_nonneg_check
        CHECK (outlaw_misconduct_score >= 0);

-- Partial index for the per-tick decay sweep: most agents are CLEAN with
-- score 0, so a partial index over the flagged subset keeps the scan
-- proportional to active offenders rather than the full population.
CREATE INDEX agents_outlaw_misconduct_active_idx
    ON agents (outlaw_misconduct_score)
    WHERE outlaw_misconduct_score > 0;
