import json

import pandas as pd
import pytest

from planner_analytics import export
from planner_analytics.export import assert_safe, clean, build_reports, run, user_key

NOW = pd.Timestamp("2031-01-10 12:00", tz="UTC")


def t(day, hour=0):
    return pd.Timestamp(f"2031-01-{day:02d} {hour:02d}:00", tz="UTC")


def frames(**over):
    eq = pd.DataFrame([{
        "asset_tag": "VENT-0001", "name": "Vent", "category": "Ventilator", "department": "ICU", "lifecycle_status": "ACTIVE",
        "manufacturer": None, "model": None, "purchase_date": None, "purchase_cost": None, "warranty_end": None,
        "contract_type": "NONE", "contract_end": None, "location": None, "maintenance_interval_days": 90,
        "maintenance_threshold_hours": 500, "usage_hours_since_maintenance": 0, "last_maintenance_at": pd.NaT,
        "next_maintenance_due": t(1) + pd.Timedelta(days=200), "hours_overdue_since": pd.NaT, "calibration_required": False,
        "last_calibration_at": pd.NaT, "next_calibration_due": pd.NaT, "calibration_failed": False,
        "in_service_from": pd.NaT, "retired_at": pd.NaT}])
    f = {
        "equipment": eq,
        "usage": pd.DataFrame(columns=["usage_id", "asset_tag", "department", "start_at", "end_at", "source", "user_name", "batch_id"]),
        "maintenance": pd.DataFrame(columns=["maintenance_id", "asset_tag", "kind", "priority", "status", "engineer_name",
                                             "scheduled_start", "scheduled_end", "actual_start", "actual_end", "next_due_date"]),
        "calibration": pd.DataFrame(columns=["calibration_id", "asset_tag", "status", "result", "scheduled_for", "performed_at",
                                             "next_due_date", "certificate_number", "engineer_name"]),
        "breakdowns": pd.DataFrame(columns=["work_order_id", "asset_tag", "priority", "severity", "unsafe", "status", "reported_at",
                                            "triaged_at", "started_at", "resolved_at", "verified_at", "closed_at", "downtime_hours",
                                            "reporter_name", "engineer_name"]),
        "downtime": pd.DataFrame(columns=["downtime_id", "asset_tag", "start_at", "end_at", "reason"]),
        "dept_history": pd.DataFrame([{"asset_tag": "VENT-0001", "department": "ICU", "effective_from": pd.NaT, "effective_to": pd.NaT}]),
    }
    f.update(over)
    return f


def usage(rows):
    return pd.DataFrame(rows, columns=["usage_id", "asset_tag", "department", "start_at", "end_at", "source", "user_name", "batch_id"])


def test_usage_is_validated_deduplicated_and_pseudonymised():
    u = usage([
        (1, "VENT-0001", "ICU", t(5, 8), t(5, 10), "csv", "nurse1", 1),
        (2, "VENT-0001", "ICU", t(5, 8), t(5, 10), "csv", "nurse1", 1),     # same span twice
        (3, "VENT-0001", "ICU", t(5, 9), t(5, 11), "csv", "nurse2", 1),     # overlaps the first
        (4, "VENT-0001", "ICU", t(6, 10), t(6, 9), "csv", "nurse1", 1),     # ends before it starts
        (1, "VENT-0001", "ICU", t(7, 8), t(7, 9), "csv", "nurse1", 1),      # repeated id
        (5, "VENT-0001", "ICU", t(8, 8), t(8, 10), "api", "nurse1", None),
    ])
    out, report = clean(frames(usage=u), salt="s3cret")
    assert list(out["usage"]["usage_id"]) == [1, 5]
    assert report["usage"] == {"input": 6, "invalid_dropped": 1, "overlap_or_duplicate_dropped": 3, "output": 2}
    assert "user_name" not in out["usage"].columns
    assert out["usage"]["user_key"].iloc[0] == user_key("nurse1", "s3cret") != "nurse1"
    assert user_key("nurse1", "a") != user_key("nurse1", "b")             # salted


def test_reports_contain_no_sensitive_or_free_text_columns():
    out, _ = clean(frames(usage=usage([(1, "VENT-0001", "ICU", t(5, 8), t(5, 10), "csv", "nurse1", 1)])), "x")
    for name, df in build_reports(out, NOW, 5).items():
        assert_safe(name, df)
    for bad in ("patient_name", "MRN", "notes", "repair_details", "Date of Birth"):
        with pytest.raises(ValueError):
            assert_safe("x", pd.DataFrame({bad: [1]}))


def test_future_scheduled_maintenance_does_not_reduce_availability_before_its_start():
    m = pd.DataFrame([{"maintenance_id": 1, "asset_tag": "VENT-0001", "kind": "PREVENTIVE", "priority": "MEDIUM", "status": "SCHEDULED",
                       "engineer_name": None, "scheduled_start": t(12), "scheduled_end": t(12, 6), "actual_start": pd.NaT,
                       "actual_end": pd.NaT, "next_due_date": pd.NaT}])
    out, _ = clean(frames(maintenance=m), "x")
    ad = build_reports(out, NOW, 3)["asset_day_utilization"]
    assert (ad["unavailable_h"] == 0).all()


def test_completed_maintenance_and_failed_calibration_are_unavailable_time():
    m = pd.DataFrame([{"maintenance_id": 1, "asset_tag": "VENT-0001", "kind": "PREVENTIVE", "priority": "MEDIUM", "status": "COMPLETED",
                       "engineer_name": "eng", "scheduled_start": t(8, 8), "scheduled_end": t(8, 12), "actual_start": t(8, 8),
                       "actual_end": t(8, 14), "next_due_date": pd.NaT}])
    cal = pd.DataFrame([{"calibration_id": 1, "asset_tag": "VENT-0001", "status": "COMPLETED", "result": "FAIL", "scheduled_for": t(9),
                         "performed_at": t(9, 12), "next_due_date": pd.NaT, "certificate_number": None, "engineer_name": "eng"}])
    out, _ = clean(frames(maintenance=m, calibration=cal), "x")
    ad = build_reports(out, NOW, 3).get("asset_day_utilization").set_index("day")
    assert ad.loc["2031-01-08", "unavailable_h"] == pytest.approx(6)          # 08:00-14:00 actual, not the 4 h planned
    assert ad.loc["2031-01-09", "unavailable_h"] == pytest.approx(12)         # failed at 12:00, unresolved
    assert ad.loc["2031-01-10", "unavailable_h"] == pytest.approx(12)         # still failed up to NOW (12:00)


def test_usage_is_attributed_to_the_department_at_the_time_of_use():
    hist = pd.DataFrame([
        {"asset_tag": "VENT-0001", "department": "ICU", "effective_from": pd.NaT, "effective_to": t(8)},
        {"asset_tag": "VENT-0001", "department": "Surgery", "effective_from": t(8), "effective_to": pd.NaT}])
    u = usage([(1, "VENT-0001", "ICU", t(6, 8), t(6, 10), "csv", "n", 1), (2, "VENT-0001", "Surgery", t(9, 8), t(9, 11), "csv", "n", 1)])
    out, _ = clean(frames(usage=u, dept_history=hist), "x")
    rep = build_reports(out, NOW, 6)
    dd = rep["department_day_utilization"].set_index(["day", "department"])
    assert dd.loc[("2031-01-06", "ICU"), "used_h"] == pytest.approx(2)
    assert dd.loc[("2031-01-09", "Surgery"), "used_h"] == pytest.approx(3)
    assert ("2031-01-09", "ICU") not in dd.index                              # the past is not rewritten by the later transfer


def test_run_writes_manifest_with_hashes(tmp_path):
    out, _ = clean(frames(), "x")
    manifest = run(frames(), tmp_path, NOW, 3, "x", run_id="r1")
    saved = json.loads((tmp_path / "manifest.json").read_text())
    assert saved["run_id"] == "r1" and "usage_clean.csv" in saved["files"] and len(saved["files"]["equipment.csv"]["sha256"]) == 64
    assert manifest["files"]["asset_day_utilization.csv"]["rows"] == 3


def test_s3_upload_uses_caller_bucket_and_env_credentials(tmp_path, monkeypatch):
    run(frames(), tmp_path, NOW, 2, "x", run_id="r9")
    calls = []

    class FakeS3:
        def upload_file(self, path, bucket, key, ExtraArgs=None):
            calls.append((bucket, key, ExtraArgs))

    import boto3
    monkeypatch.setattr(boto3, "client", lambda name: FakeS3())
    keys = export.upload_s3(tmp_path, "my-bucket", "planner/", "r9")
    assert {b for b, _, _ in calls} == {"my-bucket"}
    assert "planner/latest/usage_clean.csv" in keys and "planner/runs/r9/manifest.json" in keys
    assert all(e == {"ServerSideEncryption": "AES256"} for _, _, e in calls)


def test_upload_requires_salt(monkeypatch):
    monkeypatch.delenv("EXPORT_SALT", raising=False)
    with pytest.raises(SystemExit):
        export.main(["--dsn", "postgresql://x", "--bucket", "b"])


def test_synthetic_frames_mirror_the_real_source_queries():
    """Keeps these fixtures honest: same columns, same order as export.SOURCE_SQL."""
    import re

    def sql_columns(sql):
        body = re.search(r"SELECT\s+(.*?)\s+FROM", sql, re.S).group(1)
        cols, depth, cur = [], 0, ""
        for ch in body:
            depth += ch == "("
            depth -= ch == ")"
            if ch == "," and depth == 0:
                cols.append(cur)
                cur = ""
            else:
                cur += ch
        cols.append(cur)
        out = []
        for c in cols:
            c = " ".join(c.split())
            out.append(re.split(r"\s+AS\s+", c, flags=re.I)[-1].split(".")[-1] if re.search(r"\s+AS\s+", c, re.I) else c.split(".")[-1])
        return out

    f = frames()
    for name, sql in export.SOURCE_SQL.items():
        assert list(f[name].columns) == sql_columns(sql), name
