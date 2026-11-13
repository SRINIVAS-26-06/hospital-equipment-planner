import re
from pathlib import Path

import pandas as pd
import pytest

from planner_analytics import load_snowflake as ls
from planner_analytics.export import build_reports, clean, write_reports
from tests.test_export import NOW, frames, usage, t

SQL = Path(__file__).resolve().parents[2] / "snowflake"


def read(name):
    return (SQL / name).read_text()


def test_placeholders_are_required_and_replaced():
    good = {"S3_BUCKET": "b", "S3_PREFIX": "p/", "AWS_ROLE_ARN": "arn:x", "RUN_ID": "r1"}
    out = ls.render(read("01_setup.sql"), good)
    assert "{{" not in out and "s3://b/p/" in out
    with pytest.raises(KeyError):
        ls.render(read("01_setup.sql"), {**good, "S3_BUCKET": ""})


def test_sql_files_contain_no_hardcoded_bucket_role_or_credentials():
    text = "".join(read(f) for f in ls.FILES)
    assert "planner-usage-exports" not in text
    assert not re.search(r"arn:aws:iam::\d{6,}", text)
    assert not re.search(r"(?i)(aws_secret|aws_key_id|password\s*=)", text)
    assert set(ls.PLACEHOLDER.findall(text)) <= {"S3_BUCKET", "S3_PREFIX", "AWS_ROLE_ARN", "RUN_ID"}


def test_statement_splitter_ignores_semicolons_in_strings_and_comments():
    s = "-- a; b\nSELECT 'x;y'; INSERT INTO t VALUES (1);\n-- tail\n"
    assert ls.split_statements(s) == ["SELECT 'x;y'", "INSERT INTO t VALUES (1)"]
    assert len(ls.split_statements(ls.render(read("02_load.sql"), {"RUN_ID": "r", "S3_BUCKET": "b", "S3_PREFIX": "p/", "AWS_ROLE_ARN": "a"}))) > 25


def test_loads_are_merges_by_natural_key_and_reloads_cannot_duplicate():
    load = read("02_load.sql")
    for target in ["USAGE_EVENTS", "ASSETS", "MAINTENANCE", "CALIBRATION", "BREAKDOWNS", "DOWNTIME",
                   "ASSET_DAY_UTILIZATION", "DEPARTMENT_DAY_UTILIZATION"]:
        assert re.search(rf"MERGE INTO {target} t USING", load), target
    assert not re.search(r"(?i)\bINSERT INTO (USAGE_EVENTS|ASSETS|MAINTENANCE|CALIBRATION|BREAKDOWNS|DOWNTIME)\b", load)
    assert load.count("QUALIFY ROW_NUMBER()") >= 8          # staging is de-duplicated before every merge


def test_copy_column_lists_match_the_csv_headers_the_exporter_writes(tmp_path):
    out, _ = clean(frames(usage=usage([(1, "VENT-0001", "ICU", t(5, 8), t(5, 10), "csv", "n", 1)])), "x")
    write_reports(build_reports(out, NOW, 3), tmp_path, "r", {})
    load = read("02_load.sql")
    pattern = re.compile(r"COPY INTO (\w+) \(([^)]*)\)\s+FROM @usage_stage/latest/(\w+\.csv)", re.S)
    found = pattern.findall(load)
    assert len(found) == 6
    for table, cols, filename in found:
        listed = [c.strip().lower() for c in cols.split(",")]
        header = pd.read_csv(tmp_path / filename, nrows=0).columns.tolist()
        assert listed == header, f"{filename}: {listed} != {header}"
    # the no-column-list COPYs rely on positional order, so the staging tables must match the CSV width
    for table, filename, width in [("STG_DEPARTMENT_HISTORY", "department_history.csv", 4), ("STG_ASSET_DAY", "asset_day_utilization.csv", 10),
                                   ("STG_DEPARTMENT_DAY", "department_day_utilization.csv", 9), ("STG_COMPLIANCE", "maintenance_compliance.csv", 2)]:
        assert len(pd.read_csv(tmp_path / filename, nrows=0).columns) == width, filename


def test_existing_views_keep_their_names_and_columns_for_power_bi():
    v = read("03_views.sql")
    assert re.search(r"CREATE OR REPLACE VIEW V_ASSET_DAY_UTILIZATION AS\s+SELECT ASSET_TAG, DEPARTMENT, DAY,\s+TOTAL_H \* 3600\s+AS TOTAL_S", v)
    assert re.search(r"CREATE OR REPLACE VIEW V_DEPARTMENT_UTILIZATION AS\s+SELECT DEPARTMENT, DAY,", v)
