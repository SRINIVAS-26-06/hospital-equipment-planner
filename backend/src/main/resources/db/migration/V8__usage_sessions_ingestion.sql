-- Usage sessions (open/closed), who/where, and ingestion batches with row-level results.
CREATE TABLE ingestion_batches (
  id              BIGSERIAL PRIMARY KEY,
  source          TEXT NOT NULL,
  filename        TEXT,
  file_sha256     TEXT,
  uploaded_by     TEXT NOT NULL,
  uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  total_rows      INT NOT NULL DEFAULT 0,
  accepted_rows   INT NOT NULL DEFAULT 0,
  rejected_rows   INT NOT NULL DEFAULT 0,
  status          TEXT NOT NULL DEFAULT 'PROCESSING' CHECK (status IN ('PROCESSING','COMPLETED','PARTIAL','REJECTED','FAILED')),
  ignored_columns TEXT   -- names only; values of ignored columns are never read or stored
);

CREATE TABLE ingestion_row_results (
  id             BIGSERIAL PRIMARY KEY,
  batch_id       BIGINT NOT NULL REFERENCES ingestion_batches(id) ON DELETE CASCADE,
  row_number     INT NOT NULL,
  asset_ref      TEXT,
  start_text     TEXT,
  end_text       TEXT,
  outcome        TEXT NOT NULL CHECK (outcome IN ('ACCEPTED','REJECTED')),
  reason         TEXT,
  usage_event_id BIGINT
);
CREATE INDEX idx_ingest_rows ON ingestion_row_results(batch_id, row_number);

ALTER TABLE usage_events ALTER COLUMN end_at DROP NOT NULL;   -- NULL end = session in progress
DO $$ DECLARE c text; BEGIN
  FOR c IN SELECT conname FROM pg_constraint WHERE conrelid = 'usage_events'::regclass AND contype = 'c' LOOP
    EXECUTE format('ALTER TABLE usage_events DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
ALTER TABLE usage_events ADD CONSTRAINT usage_events_span_check CHECK (end_at IS NULL OR end_at > start_at);

ALTER TABLE usage_events
  ADD COLUMN used_by            TEXT,
  ADD COLUMN department_id      BIGINT REFERENCES departments(id),   -- asset's department when the session started
  ADD COLUMN user_department_id BIGINT REFERENCES departments(id),
  ADD COLUMN batch_id           BIGINT REFERENCES ingestion_batches(id),
  ADD COLUMN booking_id         BIGINT REFERENCES bookings(id),
  ADD COLUMN ingested_at        TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE usage_events u SET department_id = (
  SELECT h.department_id FROM asset_department_history h
  WHERE h.asset_id = u.asset_id AND h.effective_from <= u.start_at
    AND (h.effective_to IS NULL OR h.effective_to > u.start_at));

CREATE UNIQUE INDEX uq_usage_open_session ON usage_events(asset_id) WHERE end_at IS NULL;
CREATE INDEX idx_usage_user ON usage_events(used_by);
