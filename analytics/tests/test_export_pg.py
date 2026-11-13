"""Runs the real export against a freshly migrated PostgreSQL. Skipped unless PLANNER_TEST_DSN is set, e.g.
PLANNER_TEST_DSN="host=localhost port=5432 user=postgres"."""
import glob
import os
import re

import pandas as pd
import pytest

DSN = os.environ.get("PLANNER_TEST_DSN")
pytestmark = pytest.mark.skipif(not DSN, reason="PLANNER_TEST_DSN not set")

MIG = os.path.join(os.path.dirname(__file__), "..", "..", "backend", "src", "main", "resources", "db", "migration")


def test_export_from_migrated_postgres(tmp_path):
    import psycopg

    from planner_analytics import export

    with psycopg.connect(DSN, autocommit=True) as admin:
        admin.execute("DROP DATABASE IF EXISTS planner_export_test")
        admin.execute("CREATE DATABASE planner_export_test")
    db = psycopg.connect(DSN + " dbname=planner_export_test", autocommit=True)
    for f in sorted(glob.glob(os.path.join(MIG, "V*.sql")), key=lambda p: int(re.search(r"V(\d+)__", p).group(1))):
        db.execute(open(f).read())

    aid, icu, surgery = db.execute("SELECT a.id, a.department_id, (SELECT id FROM departments WHERE name='Surgery') FROM assets a WHERE asset_tag='VENT-0001'").fetchone()
    now = pd.Timestamp.now(tz="UTC").floor("h")
    h = lambda x: (now + pd.Timedelta(hours=x)).to_pydatetime()  # noqa: E731
    # transfer ICU -> Surgery 30 hours ago
    db.execute("UPDATE asset_department_history SET effective_to=%s WHERE asset_id=%s AND effective_to IS NULL", (h(-30), aid))
    db.execute("INSERT INTO asset_department_history(asset_id, department_id, effective_from) VALUES (%s,%s,%s)", (aid, surgery, h(-30)))
    db.execute("UPDATE assets SET department_id=%s WHERE id=%s", (surgery, aid))
    for start, end, dept in [(-50, -48, icu), (-10, -7, surgery)]:
        db.execute("INSERT INTO usage_events(asset_id,start_at,end_at,source,used_by,department_id) VALUES (%s,%s,%s,'csv','nurse',%s)", (aid, h(start), h(end), dept))
    db.execute("INSERT INTO maintenance_windows(asset_id,start_at,end_at,status,actual_start,actual_end,assigned_engineer) VALUES (%s,%s,%s,'COMPLETED',%s,%s,'engineer')",
               (aid, h(-20), h(-18), h(-20), h(-17)))
    db.execute("INSERT INTO work_orders(asset_id,title,description,reported_by,status,resolved_at) VALUES (%s,'secret title','secret description','staff','RESOLVED',%s)", (aid, h(-5)))

    frames = export.load_from_pg(DSN + " dbname=planner_export_test", now - pd.Timedelta(days=10))
    manifest = export.run(frames, tmp_path, now, 4, "salt", run_id="pg")

    assert manifest["files"]["equipment.csv"]["rows"] == 6
    usage = pd.read_csv(tmp_path / "usage_clean.csv")
    assert len(usage) == 2 and "user_name" not in usage.columns and (usage["user_key"].str.len() == 12).all()
    text = "".join(p.read_text() for p in tmp_path.glob("*.csv"))
    assert "secret title" not in text and "secret description" not in text and "nurse" not in text
    ad = pd.read_csv(tmp_path / "asset_day_utilization.csv")
    v = ad[ad["asset_tag"] == "VENT-0001"]
    assert set(v["department"]) == {"ICU", "Surgery"}                                  # both owners appear, each for its own period
    assert v["unavailable_h"].sum() == pytest.approx(3, abs=0.01)                      # actual maintenance 20h..17h before now = 3h
    db.close()
    with psycopg.connect(DSN, autocommit=True) as admin:
        admin.execute("DROP DATABASE IF EXISTS planner_export_test")
