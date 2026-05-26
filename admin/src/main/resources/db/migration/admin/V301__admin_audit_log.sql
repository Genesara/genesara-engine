-- Append-only forensic log for admin writes. Every mutating endpoint under
-- /admin/** records one row before returning. seq is the dashboard's cursor.
--
-- admin_id is a soft reference to admins(id); we keep ON DELETE actions off so
-- a purged admin still leaves their audit trail intact.
CREATE TABLE admin_audit_log
(
    seq         BIGSERIAL    PRIMARY KEY,
    admin_id    UUID         NOT NULL,
    action      VARCHAR(64)  NOT NULL,
    target      VARCHAR(64)  NOT NULL,
    target_id   VARCHAR(64),
    payload     JSONB        NOT NULL,
    tick        BIGINT       NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_admin_audit_log_admin ON admin_audit_log (admin_id);
