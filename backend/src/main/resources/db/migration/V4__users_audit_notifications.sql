-- Persistent users, audit trail, in-app notifications. Additive only.
CREATE TABLE app_users (
  id            BIGSERIAL PRIMARY KEY,
  username      TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  full_name     TEXT NOT NULL DEFAULT '',
  email         TEXT,
  role          TEXT NOT NULL CHECK (role IN ('ADMIN','ENGINEER','STAFF','VIEWER')),
  department_id BIGINT REFERENCES departments(id),
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
  id          BIGSERIAL PRIMARY KEY,
  at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  username    TEXT NOT NULL,
  action      TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id   TEXT,
  old_value   JSONB,
  new_value   JSONB
);
CREATE INDEX idx_audit_at     ON audit_log(at DESC);
CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id);

CREATE TABLE notifications (
  id            BIGSERIAL PRIMARY KEY,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  type          TEXT NOT NULL,
  severity      TEXT NOT NULL DEFAULT 'INFO' CHECK (severity IN ('INFO','WARNING','CRITICAL')),
  title         TEXT NOT NULL,
  message       TEXT NOT NULL,
  entity_type   TEXT,
  entity_id     BIGINT,
  audience_role TEXT CHECK (audience_role IN ('ALL','ADMIN','ENGINEER','STAFF','VIEWER')),
  audience_user TEXT,
  dedupe_key    TEXT UNIQUE,
  CHECK (audience_role IS NOT NULL OR audience_user IS NOT NULL)
);
CREATE INDEX idx_notifications_created ON notifications(created_at DESC);

CREATE TABLE notification_reads (
  notification_id BIGINT NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
  username        TEXT NOT NULL,
  read_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (notification_id, username)
);

ALTER TABLE departments ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
