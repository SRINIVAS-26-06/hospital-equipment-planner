-- Transactional Outbox for analytics-relevant hospital equipment entities.
-- Every captured business-table change and its outbox event commit/rollback
-- in the same PostgreSQL transaction.

CREATE TABLE outbox_events (
    event_id       BIGSERIAL PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id   TEXT NOT NULL,
    event_type     TEXT NOT NULL CHECK (event_type IN ('INSERT', 'UPDATE', 'DELETE')),
    payload        JSONB NOT NULL,
    status         TEXT NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at   TIMESTAMPTZ,
    attempts       INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at      TIMESTAMPTZ,
    last_error     TEXT
);

CREATE INDEX idx_outbox_pending
    ON outbox_events (available_at, event_id)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX idx_outbox_aggregate
    ON outbox_events (aggregate_type, aggregate_id, event_id);

CREATE INDEX idx_outbox_created_at
    ON outbox_events (created_at);

CREATE OR REPLACE FUNCTION capture_outbox_event()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_old       JSONB;
    v_new       JSONB;
    v_row       JSONB;
    v_id        TEXT;
    v_operation TEXT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        v_new := to_jsonb(NEW);
        v_row := v_new;
        v_operation := 'INSERT';
    ELSIF TG_OP = 'UPDATE' THEN
        v_old := to_jsonb(OLD);
        v_new := to_jsonb(NEW);

        -- Avoid emitting noise for UPDATE statements that do not change values.
        IF v_old IS NOT DISTINCT FROM v_new THEN
            RETURN NEW;
        END IF;

        v_row := v_new;
        v_operation := 'UPDATE';
    ELSIF TG_OP = 'DELETE' THEN
        v_old := to_jsonb(OLD);
        v_row := v_old;
        v_operation := 'DELETE';
    ELSE
        RAISE EXCEPTION 'Unsupported operation for outbox capture: %', TG_OP;
    END IF;

    v_id := v_row ->> 'id';
    IF v_id IS NULL THEN
        RAISE EXCEPTION 'Outbox capture expects an id column on table %', TG_TABLE_NAME;
    END IF;

    INSERT INTO outbox_events (
        aggregate_type,
        aggregate_id,
        event_type,
        payload
    ) VALUES (
        TG_TABLE_NAME,
        v_id,
        v_operation,
        jsonb_build_object(
            'schema_version', 1,
            'table', TG_TABLE_NAME,
            'operation', v_operation,
            'record_id', v_id,
            'old', v_old,
            'new', v_new
        )
    );

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

-- Tables intentionally selected for operational/analytics events.
-- Documents, app_users, audit_log, notifications and ingestion bookkeeping
-- are excluded to avoid exporting file bytes, credentials or unrelated data.
DO $$
DECLARE
    v_table TEXT;
    v_tables TEXT[] := ARRAY[
        'departments',
        'assets',
        'bookings',
        'maintenance_windows',
        'transfers',
        'usage_events',
        'downtime_events',
        'asset_department_history',
        'calibrations',
        'work_orders',
        'work_order_history'
    ];
BEGIN
    FOREACH v_table IN ARRAY v_tables LOOP
        EXECUTE format(
            'CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %I '
            'FOR EACH ROW EXECUTE FUNCTION capture_outbox_event()',
            'trg_outbox_' || v_table,
            v_table
        );
    END LOOP;
END;
$$;
