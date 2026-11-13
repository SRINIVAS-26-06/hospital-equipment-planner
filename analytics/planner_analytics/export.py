"""Operational data export: PostgreSQL -> validated, de-duplicated CSV reports (+ optional S3 upload).

  DATABASE_URL=postgresql://... EXPORT_SALT=... python -m planner_analytics.export --out out/ [--days 35] [--since-days 7]
  S3_BUCKET=my-bucket S3_PREFIX=planner/   (optional; credentials come from the IAM role / environment, never from code)

Privacy: columns are allow-listed in SQL, free-text fields (notes, remarks, titles, descriptions, purpose) are never
selected, staff usernames are replaced by a salted hash, and every output is scanned for sensitive column names
before it is written. Patient identifiers do not exist in the database and therefore cannot be exported.
"""
import argparse
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

from .utilization import _flag, utilization_asset_day

SOURCE_SQL = {
    "equipment": """SELECT a.asset_tag, a.name, a.category, d.name AS department, a.lifecycle_status, a.manufacturer, a.model,
        a.purchase_date, a.purchase_cost, a.warranty_end, a.contract_type, a.contract_end, a.location,
        a.maintenance_interval_days, a.maintenance_threshold_hours, a.usage_hours_since_maintenance,
        a.last_maintenance_at, a.next_maintenance_due, a.hours_overdue_since, a.calibration_required,
        a.last_calibration_at, a.next_calibration_due, a.calibration_failed, a.in_service_from, a.retired_at
        FROM assets a JOIN departments d ON d.id = a.department_id ORDER BY a.asset_tag""",
    "usage": """SELECT u.id AS usage_id, a.asset_tag, d.name AS department, u.start_at, u.end_at, u.source, u.used_by AS user_name, u.batch_id
        FROM usage_events u JOIN assets a ON a.id = u.asset_id LEFT JOIN departments d ON d.id = u.department_id
        WHERE u.end_at IS NOT NULL AND u.end_at >= %(since)s ORDER BY u.id""",
    "maintenance": """SELECT m.id AS maintenance_id, a.asset_tag, m.kind, m.priority, m.status, m.assigned_engineer AS engineer_name,
        m.start_at AS scheduled_start, m.end_at AS scheduled_end, m.actual_start, m.actual_end, m.next_due_date
        FROM maintenance_windows m JOIN assets a ON a.id = m.asset_id ORDER BY m.id""",
    "calibration": """SELECT c.id AS calibration_id, a.asset_tag, c.status, c.result, c.scheduled_for, c.performed_at, c.next_due_date,
        c.certificate_number, c.assigned_engineer AS engineer_name
        FROM calibrations c JOIN assets a ON a.id = c.asset_id ORDER BY c.id""",
    "breakdowns": """SELECT w.id AS work_order_id, a.asset_tag, w.priority, w.severity, w.unsafe, w.status, w.reported_at, w.triaged_at,
        w.started_at, w.resolved_at, w.verified_at, w.closed_at, w.downtime_hours, w.reported_by AS reporter_name,
        w.assigned_engineer AS engineer_name
        FROM work_orders w JOIN assets a ON a.id = w.asset_id ORDER BY w.id""",
    "downtime": """SELECT e.id AS downtime_id, a.asset_tag, e.start_at, e.end_at, e.reason
        FROM downtime_events e JOIN assets a ON a.id = e.asset_id ORDER BY e.id""",
    "dept_history": """SELECT a.asset_tag, d.name AS department,
        CASE WHEN h.effective_from = '-infinity' THEN NULL ELSE h.effective_from END AS effective_from, h.effective_to
        FROM asset_department_history h JOIN assets a ON a.id = h.asset_id JOIN departments d ON d.id = h.department_id
        ORDER BY a.asset_tag, h.effective_from""",
}
TIME_COLS = {"purchase_date", "warranty_end", "contract_end"}   # plain dates are left as dates
SENSITIVE = re.compile(r"patient|mrn|medical.?record|dob|birth|ssn|diagnos|nric|phone|e-?mail|address|first.?name|last.?name|full.?name", re.I)
FREE_TEXT = {"notes", "remarks", "purpose", "description", "title", "repair_details", "resolution_remarks", "parts_replaced",
             "work_performed", "cancel_reason", "reason_text"}
KNOWN_DOWNTIME = {"POWER_FAILURE", "RELOCATION", "SOFTWARE_UPDATE", "AWAITING_PARTS", "OUT_OF_SERVICE", "OVERDUE_LOCKOUT"}


def user_key(name, salt):
    if name is None or (isinstance(name, float) and pd.isna(name)):
        return None
    return hashlib.sha256(f"{salt}:{name}".encode()).hexdigest()[:12]


def load_from_pg(dsn, since):
    import psycopg

    frames = {}
    with psycopg.connect(dsn) as conn:
        for name, sql in SOURCE_SQL.items():
            with conn.cursor() as cur:
                cur.execute(sql, {"since": since})
                cols = [d.name for d in cur.description]
                frames[name] = pd.DataFrame(cur.fetchall(), columns=cols)
    return {k: _to_times(v) for k, v in frames.items()}


def _to_times(df):
    df = df.copy()
    for c in df.columns:
        if c not in TIME_COLS and (c.endswith("_at") or c.endswith("_start") or c.endswith("_end")
                                   or c in ("scheduled_for", "next_due_date", "effective_from", "effective_to")):
            df[c] = pd.to_datetime(df[c], utc=True, errors="coerce")
    return df


def assert_safe(name, df):
    bad = [c for c in df.columns if SENSITIVE.search(c) or c in FREE_TEXT]
    if bad:
        raise ValueError(f"refusing to export '{name}': sensitive or free-text columns {bad}")


def clean(frames, salt):
    """Validate, de-duplicate and pseudonymise. Returns (frames, quality report)."""
    out, report = {k: _to_times(v) for k, v in frames.items()}, {}

    u = out["usage"]
    n0 = len(u)
    u = u.dropna(subset=["asset_tag", "start_at", "end_at"])
    u = u[u["end_at"] > u["start_at"]]
    n_invalid = n0 - len(u)
    u = u.drop_duplicates(subset=["usage_id"]).drop_duplicates(subset=["asset_tag", "start_at", "end_at"])
    # overlapping sessions for one asset are data errors: keep the earliest, drop the rest
    keep, prev_end, prev_tag = [], None, None
    for r in u.sort_values(["asset_tag", "start_at"]).itertuples():
        if r.asset_tag == prev_tag and prev_end is not None and r.start_at < prev_end:
            continue
        keep.append(r.Index)
        prev_tag, prev_end = r.asset_tag, r.end_at
    n_overlap = len(u) - len(keep)
    u = u.loc[keep].copy()
    u["duration_h"] = (u["end_at"] - u["start_at"]).dt.total_seconds() / 3600
    out["usage"] = u.reset_index(drop=True)
    report["usage"] = {"input": n0, "invalid_dropped": n_invalid, "overlap_or_duplicate_dropped": n0 - n_invalid - len(u), "output": len(u)}

    m = out["maintenance"]
    m["completed_on_time"] = (m["status"] == "COMPLETED") & (m["actual_end"] <= m["scheduled_end"])
    m["duration_h"] = (m["actual_end"] - m["actual_start"]).dt.total_seconds() / 3600
    out["maintenance"] = m.drop_duplicates(subset=["maintenance_id"])

    d = out["downtime"].drop_duplicates(subset=["downtime_id"])
    d["reason"] = d["reason"].where(d["reason"].isin(KNOWN_DOWNTIME), "OTHER")   # legacy free-text reasons collapse to OTHER
    d["duration_h"] = (d["end_at"] - d["start_at"]).dt.total_seconds() / 3600
    out["downtime"] = d

    b = out["breakdowns"].drop_duplicates(subset=["work_order_id"])
    b["resolution_h"] = (b["resolved_at"] - b["reported_at"]).dt.total_seconds() / 3600
    out["breakdowns"] = b
    out["calibration"] = out["calibration"].drop_duplicates(subset=["calibration_id"])

    for key, cols in {"usage": ["user_name"], "maintenance": ["engineer_name"], "calibration": ["engineer_name"],
                      "breakdowns": ["reporter_name", "engineer_name"]}.items():
        for c in cols:
            out[key][c.replace("_name", "_key")] = out[key][c].map(lambda x: user_key(x, salt))
            out[key] = out[key].drop(columns=[c])
    return out, report


def build_reports(frames, now, days):
    asset_day = utilization_asset_day(frames, now, days)
    dept_day = asset_day.groupby(["day", "department"], as_index=False)[
        ["total_h", "unavailable_h", "available_h", "used_h", "idle_h"]].sum()
    dept_day["utilization"] = dept_day.apply(lambda r: r.used_h / r.available_h if r.available_h > 0 else None, axis=1)
    dept_day["flag"] = dept_day["utilization"].map(lambda x: _flag(None if pd.isna(x) else x))
    eq = frames["equipment"]
    mc = frames["maintenance"]
    compliance = pd.DataFrame({
        "metric": ["completed", "completed_on_time", "open_past_scheduled_end", "cancelled"],
        "value": [int((mc["status"] == "COMPLETED").sum()), int(mc["completed_on_time"].sum()),
                  int(((mc["status"].isin(["SCHEDULED", "IN_PROGRESS"])) & (mc["scheduled_end"] < pd.Timestamp(now))).sum()),
                  int((mc["status"] == "CANCELLED").sum())]})
    return {
        "equipment": eq.drop(columns=["hours_overdue_since"]),
        "usage_clean": frames["usage"],
        "maintenance": mc,
        "calibration": frames["calibration"],
        "breakdowns": frames["breakdowns"],
        "downtime": frames["downtime"],
        "department_history": frames["dept_history"],
        "asset_day_utilization": asset_day,
        "department_day_utilization": dept_day,
        "maintenance_compliance": compliance,
    }


def write_reports(reports, out_dir, run_id, quality):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    files = {}
    for name, df in reports.items():
        assert_safe(name, df)
        path = out / f"{name}.csv"
        df.to_csv(path, index=False)
        files[path.name] = {"rows": int(len(df)), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    manifest = {"run_id": run_id, "generated_at": datetime.now(timezone.utc).isoformat(), "files": files, "quality": quality}
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2, default=str))
    return manifest


def upload_s3(out_dir, bucket, prefix, run_id):
    """Uploads the CSVs under <prefix>/latest/ and <prefix>/runs/<run_id>/. Bucket and prefix come from the caller."""
    import boto3

    s3 = boto3.client("s3")   # IAM role or standard AWS_* environment variables
    keys = []
    for p in sorted(Path(out_dir).glob("*")):
        if p.suffix not in (".csv", ".json"):
            continue
        for sub in ("latest", f"runs/{run_id}"):
            key = f"{prefix.rstrip('/')}/{sub}/{p.name}" if prefix else f"{sub}/{p.name}"
            s3.upload_file(str(p), bucket, key, ExtraArgs={"ServerSideEncryption": "AES256"})
            keys.append(key)
    return keys


def run(frames, out_dir, now, days, salt, run_id=None):
    run_id = run_id or now.strftime("%Y%m%dT%H%M%SZ")
    cleaned, quality = clean(frames, salt)
    reports = build_reports(cleaned, now, days)
    return write_reports(reports, out_dir, run_id, quality)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="out")
    ap.add_argument("--days", type=int, default=35, help="utilization window in days (recomputed on every run)")
    ap.add_argument("--since-days", type=int, default=7, help="re-export usage ended in the last N days (loads are idempotent)")
    ap.add_argument("--dsn", default=os.environ.get("DATABASE_URL"))
    ap.add_argument("--bucket", default=os.environ.get("S3_BUCKET"))
    ap.add_argument("--prefix", default=os.environ.get("S3_PREFIX", "planner/"))
    a = ap.parse_args(argv)
    if not a.dsn:
        sys.exit("DATABASE_URL (or --dsn) is required")
    salt = os.environ.get("EXPORT_SALT", "")
    if a.bucket and not salt:
        sys.exit("EXPORT_SALT must be set before uploading, so staff identifiers are pseudonymised")
    now = datetime.now(timezone.utc)
    since = now - pd.Timedelta(days=max(a.since_days, a.days))
    frames = load_from_pg(a.dsn, since)
    manifest = run(frames, a.out, pd.Timestamp(now), a.days, salt)
    print(json.dumps(manifest["quality"]), "files:", {k: v["rows"] for k, v in manifest["files"].items()})
    if a.bucket:
        print("uploaded", len(upload_s3(a.out, a.bucket, a.prefix, manifest["run_id"])), "objects to s3://" + a.bucket)
    return 0


if __name__ == "__main__":
    sys.exit(main())
