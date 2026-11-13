-- Breakdown reports and the work order lifecycle:
-- REPORTED -> TRIAGED -> ASSIGNED -> IN_PROGRESS -> RESOLVED -> VERIFIED -> CLOSED
CREATE TABLE work_orders (
  id                BIGSERIAL PRIMARY KEY,
  asset_id          BIGINT NOT NULL REFERENCES assets(id),
  title             TEXT NOT NULL,
  description       TEXT NOT NULL,
  priority          TEXT NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  severity          TEXT NOT NULL DEFAULT 'MINOR'  CHECK (severity IN ('MINOR','MAJOR','CRITICAL')),
  unsafe            BOOLEAN NOT NULL DEFAULT FALSE,
  status            TEXT NOT NULL DEFAULT 'REPORTED'
                    CHECK (status IN ('REPORTED','TRIAGED','ASSIGNED','IN_PROGRESS','RESOLVED','VERIFIED','CLOSED')),
  reported_by       TEXT NOT NULL,
  reported_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  triaged_by        TEXT,
  triaged_at        TIMESTAMPTZ,
  assigned_engineer TEXT,
  started_at        TIMESTAMPTZ,
  resolved_at       TIMESTAMPTZ,
  verified_by       TEXT,
  verified_at       TIMESTAMPTZ,
  closed_by         TEXT,
  closed_at         TIMESTAMPTZ,
  repair_details    TEXT,
  parts_replaced    TEXT,
  resolution_remarks TEXT,
  downtime_hours    NUMERIC(10,2)
);
CREATE INDEX idx_wo_asset  ON work_orders(asset_id, reported_at);
CREATE INDEX idx_wo_status ON work_orders(status);
CREATE INDEX idx_wo_engineer ON work_orders(assigned_engineer) WHERE status NOT IN ('CLOSED','VERIFIED');
-- fast lookup of assets blocked by an unresolved breakdown
CREATE INDEX idx_wo_blocking ON work_orders(asset_id) WHERE status IN ('REPORTED','TRIAGED','ASSIGNED','IN_PROGRESS');

CREATE TABLE work_order_history (
  id            BIGSERIAL PRIMARY KEY,
  work_order_id BIGINT NOT NULL REFERENCES work_orders(id) ON DELETE CASCADE,
  at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor         TEXT NOT NULL,
  from_status   TEXT,
  to_status     TEXT NOT NULL,
  note          TEXT
);
CREATE INDEX idx_wo_history ON work_order_history(work_order_id, at);
