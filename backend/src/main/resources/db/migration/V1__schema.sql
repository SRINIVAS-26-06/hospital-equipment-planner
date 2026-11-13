CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE departments (
  id   BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL UNIQUE
);

CREATE TABLE assets (
  id                            BIGSERIAL PRIMARY KEY,
  asset_tag                     TEXT NOT NULL UNIQUE,
  name                          TEXT NOT NULL,
  category                      TEXT NOT NULL,
  department_id                 BIGINT NOT NULL REFERENCES departments(id),
  status                        TEXT NOT NULL DEFAULT 'ACTIVE'
                                CHECK (status IN ('ACTIVE','IN_USE','OUT_OF_SERVICE')),
  maintenance_interval_days     INT NOT NULL DEFAULT 90,
  maintenance_threshold_hours   DOUBLE PRECISION NOT NULL DEFAULT 500,
  usage_hours_since_maintenance DOUBLE PRECISION NOT NULL DEFAULT 0,
  last_maintenance_at           TIMESTAMPTZ,
  next_maintenance_due          TIMESTAMPTZ
);

-- legacy / inconsistent identifiers that normalization alone cannot resolve
CREATE TABLE asset_aliases (
  alias    TEXT PRIMARY KEY,
  asset_id BIGINT NOT NULL REFERENCES assets(id)
);

CREATE TABLE bookings (
  id            BIGSERIAL PRIMARY KEY,
  asset_id      BIGINT NOT NULL REFERENCES assets(id),
  department_id BIGINT NOT NULL REFERENCES departments(id),
  requested_by  TEXT NOT NULL,           -- staff id only, never patient data
  start_at      TIMESTAMPTZ NOT NULL,
  end_at        TIMESTAMPTZ NOT NULL,
  status        TEXT NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE','CANCELLED','CANCELLED_TRANSFER')),
  CHECK (end_at > start_at),
  -- last line of defence: no two ACTIVE bookings of one asset may overlap
  CONSTRAINT bookings_no_overlap EXCLUDE USING gist
    (asset_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&)
    WHERE (status = 'ACTIVE')
);

CREATE TABLE maintenance_windows (
  id       BIGSERIAL PRIMARY KEY,
  asset_id BIGINT NOT NULL REFERENCES assets(id),
  kind     TEXT NOT NULL DEFAULT 'PREVENTIVE',
  start_at TIMESTAMPTZ NOT NULL,
  end_at   TIMESTAMPTZ NOT NULL,
  status   TEXT NOT NULL DEFAULT 'SCHEDULED'
           CHECK (status IN ('SCHEDULED','IN_PROGRESS','COMPLETED','CANCELLED')),
  CHECK (end_at > start_at),
  CONSTRAINT maintenance_no_overlap EXCLUDE USING gist
    (asset_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&)
    WHERE (status IN ('SCHEDULED','IN_PROGRESS'))
);

CREATE TABLE transfers (
  id            BIGSERIAL PRIMARY KEY,
  asset_id      BIGINT NOT NULL REFERENCES assets(id),
  from_dept_id  BIGINT NOT NULL REFERENCES departments(id),
  to_dept_id    BIGINT NOT NULL REFERENCES departments(id),
  transferred_at TIMESTAMPTZ NOT NULL
);

-- no patient columns by design: only asset, time span, and source
CREATE TABLE usage_events (
  id       BIGSERIAL PRIMARY KEY,
  asset_id BIGINT NOT NULL REFERENCES assets(id),
  start_at TIMESTAMPTZ NOT NULL,
  end_at   TIMESTAMPTZ NOT NULL,
  source   TEXT NOT NULL DEFAULT 'api',
  CHECK (end_at > start_at),
  UNIQUE (asset_id, start_at, end_at)
);

CREATE TABLE downtime_events (
  id       BIGSERIAL PRIMARY KEY,
  asset_id BIGINT NOT NULL REFERENCES assets(id),
  start_at TIMESTAMPTZ NOT NULL,
  end_at   TIMESTAMPTZ NOT NULL,
  reason   TEXT NOT NULL,
  CHECK (end_at > start_at)
);

CREATE INDEX idx_usage_asset_time ON usage_events(asset_id, start_at);
CREATE INDEX idx_downtime_asset_time ON downtime_events(asset_id, start_at);
