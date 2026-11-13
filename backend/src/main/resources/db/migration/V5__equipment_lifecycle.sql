-- Equipment details, lifecycle status separated from calculated availability, department history.
ALTER TABLE downtime_events ALTER COLUMN end_at DROP NOT NULL;   -- open-ended downtime (e.g. out of service)

ALTER TABLE assets
  ADD COLUMN lifecycle_status TEXT NOT NULL DEFAULT 'ACTIVE'
      CHECK (lifecycle_status IN ('ACTIVE','OUT_OF_SERVICE','RETIRED')),
  ADD COLUMN retired_at TIMESTAMPTZ,
  ADD COLUMN retired_reason TEXT,
  ADD COLUMN in_service_from TIMESTAMPTZ,            -- NULL = in service since before tracking began
  ADD COLUMN manufacturer TEXT,
  ADD COLUMN model TEXT,
  ADD COLUMN serial_number TEXT,
  ADD COLUMN purchase_date DATE,
  ADD COLUMN purchase_cost NUMERIC(14,2),
  ADD COLUMN supplier TEXT,
  ADD COLUMN warranty_start DATE,
  ADD COLUMN warranty_end DATE,
  ADD COLUMN contract_type TEXT NOT NULL DEFAULT 'NONE' CHECK (contract_type IN ('NONE','AMC','CMC')),
  ADD COLUMN contract_vendor TEXT,
  ADD COLUMN contract_start DATE,
  ADD COLUMN contract_end DATE,
  ADD COLUMN location TEXT,
  ADD COLUMN notes TEXT,
  ADD COLUMN calibration_required BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN calibration_interval_days INT,
  ADD COLUMN last_calibration_at TIMESTAMPTZ,
  ADD COLUMN next_calibration_due TIMESTAMPTZ,
  ADD COLUMN calibration_failed BOOLEAN NOT NULL DEFAULT FALSE;

-- assets.status is kept for backward compatibility but is no longer written by the application.
-- IN_USE is now derived from open usage sessions; only OUT_OF_SERVICE carries over as lifecycle.
UPDATE assets SET lifecycle_status = 'OUT_OF_SERVICE' WHERE status = 'OUT_OF_SERVICE';
INSERT INTO downtime_events(asset_id, start_at, end_at, reason)
SELECT id, now(), NULL, 'OUT_OF_SERVICE' FROM assets WHERE lifecycle_status = 'OUT_OF_SERVICE';

CREATE UNIQUE INDEX uq_assets_serial ON assets(serial_number) WHERE serial_number IS NOT NULL;
CREATE INDEX idx_assets_dept ON assets(department_id);

-- Which department owned an asset when. Historical utilization uses this, not the current department.
CREATE TABLE asset_department_history (
  id             BIGSERIAL PRIMARY KEY,
  asset_id       BIGINT NOT NULL REFERENCES assets(id),
  department_id  BIGINT NOT NULL REFERENCES departments(id),
  effective_from TIMESTAMPTZ NOT NULL,
  effective_to   TIMESTAMPTZ,
  CHECK (effective_to IS NULL OR effective_to > effective_from),
  CONSTRAINT dept_history_no_overlap EXCLUDE USING gist
    (asset_id WITH =, tstzrange(effective_from, effective_to, '[)') WITH &&)
);
CREATE INDEX idx_dept_history_asset ON asset_department_history(asset_id, effective_from);

-- backfill from existing assets + transfers (first period starts at -infinity)
INSERT INTO asset_department_history(asset_id, department_id, effective_from, effective_to)
SELECT a.id, a.department_id, '-infinity'::timestamptz, NULL
FROM assets a WHERE NOT EXISTS (SELECT 1 FROM transfers t WHERE t.asset_id = a.id);

INSERT INTO asset_department_history(asset_id, department_id, effective_from, effective_to)
SELECT asset_id, dept, eff_from, eff_to FROM (
  SELECT t.asset_id, t.from_dept_id AS dept, '-infinity'::timestamptz AS eff_from, t.transferred_at AS eff_to,
         row_number() OVER (PARTITION BY t.asset_id ORDER BY t.transferred_at, t.id) AS rn
  FROM transfers t
) first_period WHERE rn = 1
UNION ALL
SELECT t.asset_id, t.to_dept_id, t.transferred_at,
       lead(t.transferred_at) OVER (PARTITION BY t.asset_id ORDER BY t.transferred_at, t.id)
FROM transfers t;

-- bookings: audit columns, more cancellation reasons
ALTER TABLE bookings
  ADD COLUMN created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN purpose      TEXT,
  ADD COLUMN cancelled_by TEXT,
  ADD COLUMN cancelled_at TIMESTAMPTZ,
  ADD COLUMN cancel_reason TEXT;
DO $$ DECLARE c text; BEGIN
  FOR c IN SELECT conname FROM pg_constraint WHERE conrelid = 'bookings'::regclass AND contype = 'c' LOOP
    EXECUTE format('ALTER TABLE bookings DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
ALTER TABLE bookings ADD CONSTRAINT bookings_span_check CHECK (end_at > start_at);
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check
  CHECK (status IN ('ACTIVE','CANCELLED','CANCELLED_TRANSFER','CANCELLED_RETIRED','CANCELLED_REPAIR'));
CREATE INDEX idx_bookings_asset_time ON bookings(asset_id, start_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_bookings_user ON bookings(requested_by);
