-- Maintenance lifecycle (SCHEDULED -> IN_PROGRESS -> COMPLETED) and calibration management.
ALTER TABLE maintenance_windows
  ADD COLUMN priority          TEXT NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  ADD COLUMN assigned_engineer TEXT,
  ADD COLUMN checklist         JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN work_performed    TEXT,
  ADD COLUMN remarks           TEXT,
  ADD COLUMN parts_replaced    TEXT,
  ADD COLUMN actual_start      TIMESTAMPTZ,
  ADD COLUMN actual_end        TIMESTAMPTZ,
  ADD COLUMN next_due_date     TIMESTAMPTZ,
  ADD COLUMN created_by        TEXT,
  ADD COLUMN created_at        TIMESTAMPTZ NOT NULL DEFAULT now();

-- legacy rows had no actual times: use the scheduled span so historical utilization is unchanged
UPDATE maintenance_windows SET actual_start = start_at, actual_end = end_at WHERE status = 'COMPLETED';
UPDATE maintenance_windows SET actual_start = start_at WHERE status = 'IN_PROGRESS';

CREATE INDEX idx_maint_asset ON maintenance_windows(asset_id, start_at);
CREATE INDEX idx_maint_engineer ON maintenance_windows(assigned_engineer) WHERE status IN ('SCHEDULED','IN_PROGRESS');

CREATE TABLE calibrations (
  id                 BIGSERIAL PRIMARY KEY,
  asset_id           BIGINT NOT NULL REFERENCES assets(id),
  scheduled_for      TIMESTAMPTZ NOT NULL,
  status             TEXT NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','COMPLETED','CANCELLED')),
  assigned_engineer  TEXT,
  performed_at       TIMESTAMPTZ,
  result             TEXT CHECK (result IN ('PASS','FAIL')),
  certificate_number TEXT,
  remarks            TEXT,
  next_due_date      TIMESTAMPTZ,
  created_by         TEXT,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((status = 'COMPLETED') = (result IS NOT NULL AND performed_at IS NOT NULL))
);
CREATE INDEX idx_calibrations_asset ON calibrations(asset_id, scheduled_for);

CREATE TABLE documents (
  id           BIGSERIAL PRIMARY KEY,
  entity_type  TEXT NOT NULL CHECK (entity_type IN ('ASSET','MAINTENANCE','CALIBRATION','WORK_ORDER')),
  entity_id    BIGINT NOT NULL,
  doc_kind     TEXT NOT NULL DEFAULT 'ATTACHMENT',
  filename     TEXT NOT NULL,
  content_type TEXT NOT NULL,
  size_bytes   BIGINT NOT NULL,
  content      BYTEA NOT NULL,
  uploaded_by  TEXT NOT NULL,
  uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_documents_entity ON documents(entity_type, entity_id);
