import json
from pathlib import Path

import pandas as pd
import pytest

from planner_analytics.ids import normalize_asset_id
from planner_analytics.ingest import clean_usage
from planner_analytics.utilization import utilization_by_asset, utilization_by_department

DAY = pd.Timestamp("2031-01-01", tz="UTC")


def h(n):
    return DAY + pd.Timedelta(hours=n)


@pytest.mark.parametrize("raw", ["VENT-0001", "vent 1", "Vent_001", " vent-1 ", "VENT0001", "vent-00001"])
def test_normalizer_matches_java_canonical_form(raw):
    assert normalize_asset_id(raw) == "VENT-0001"


def test_normalizer_edge_cases():
    assert normalize_asset_id("old-vent-a") == "OLDVENTA"
    assert normalize_asset_id("  -- ") is None
    assert normalize_asset_id(None) is None


def test_ingest_drops_patient_columns_and_rejects_bad_rows():
    raw = pd.DataFrame({
        "Equipment ID": ["vent 1", "VENT-0001", "VENT-0001", "nope-9", "vent-2", "vent 1", "vent-2"],
        "Start Time": ["2031-01-01T00:00:00Z", "2031-01-01T00:00:00Z", "2031-01-01T00:30:00Z",
                       "2031-01-01T00:00:00Z", "2031-01-01T05:00:00Z", "garbage", "2031-01-02T00:00:00Z"],
        "End Time": ["2031-01-01T01:00:00Z", "2031-01-01T01:00:00Z", "2031-01-01T02:00:00Z",
                     "2031-01-01T01:00:00Z", "2031-01-01T04:00:00Z", "2031-01-01T01:00:00Z",
                     "2031-01-02T01:00:00Z"],
        "Patient Name": ["Jane"] * 7,
        "MRN": ["123"] * 7,
    })
    clean, rejects, dropped = clean_usage(raw, known_tags={"VENT-0001", "VENT-0002"})
    assert sorted(dropped) == ["MRN", "Patient Name"]
    assert "Patient Name" not in clean.columns and "MRN" not in clean.columns
    assert list(clean["asset_tag"]) == ["VENT-0001", "VENT-0002"]
    assert sorted(rejects["reason"]) == ["BAD_TIMESTAMP", "DUPLICATE", "DUPLICATE_OR_OVERLAP", "INVALID_SPAN",
                                         "UNKNOWN_ASSET"]


def test_alias_resolves_legacy_ids():
    raw = pd.DataFrame({"asset": ["old-vent-a"], "start": ["2031-01-01T00:00:00Z"], "end": ["2031-01-01T01:00:00Z"]})
    clean, rejects, _ = clean_usage(raw, aliases={"OLDVENTA": "VENT-0001"})
    assert list(clean["asset_tag"]) == ["VENT-0001"] and rejects.empty


def test_missing_required_column_raises():
    with pytest.raises(ValueError):
        clean_usage(pd.DataFrame({"asset": ["x"], "start": ["2031-01-01"]}))


def _frame(rows):
    return pd.DataFrame(rows, columns=["asset_tag", "start", "end"])


def test_utilization_denominator_excludes_maintenance_and_idle_is_separate():
    assets = pd.DataFrame({"asset_tag": ["A"], "department": ["ICU"]})
    usage = _frame([("A", h(0), h(8)), ("A", h(14), h(18)), ("A", h(15), h(16))])  # last is a duplicate span
    maint = _frame([("A", h(6), h(12))])
    r = utilization_by_asset(assets, usage, maint, None, h(0), h(24)).iloc[0]
    assert r.total_h == 24 and r.unavailable_h == 6 and r.available_h == 18
    assert r.used_h == 10          # 8h minus 2h inside maintenance, plus 4h; duplicate not double counted
    assert r.idle_h == 8
    assert r.utilization == pytest.approx(10 / 18)
    assert r.flag == "NORMAL"


def test_department_is_ratio_of_sums_not_mean_of_ratios():
    assets = pd.DataFrame({"asset_tag": ["A", "B"], "department": ["ICU", "ICU"]})
    usage = _frame([("A", h(0), h(24)), ("B", h(0), h(1))])
    maint = _frame([("B", h(1), h(23))])   # B has only 2 available hours, 1 used -> 50%
    per = utilization_by_asset(assets, usage, maint, None, h(0), h(24))
    dept = utilization_by_department(per).iloc[0]
    assert dept.utilization == pytest.approx(25 / 26)         # not mean(1.0, 0.5) = 0.75
    assert dept.flag == "OVERWORKED"


def test_fully_unavailable_asset_has_no_utilization_instead_of_divide_by_zero():
    assets = pd.DataFrame({"asset_tag": ["A"], "department": ["ICU"]})
    r = utilization_by_asset(assets, None, _frame([("A", h(-5), h(30))]), None, h(0), h(24)).iloc[0]
    assert r.available_h == 0 and r.utilization is None and r.flag == "NO_AVAILABLE_TIME"


def test_underused_flag():
    assets = pd.DataFrame({"asset_tag": ["A"], "department": ["ICU"]})
    r = utilization_by_asset(assets, _frame([("A", h(0), h(2))]), None, None, h(0), h(24)).iloc[0]
    assert r.flag == "UNDERUSED"


FIXTURE = Path(__file__).resolve().parents[2] / "testdata" / "utilization_cases.json"
_cases = json.loads(FIXTURE.read_text())


@pytest.mark.parametrize("case", _cases["cases"], ids=lambda c: c["name"])
def test_fixture_cases(case):
    """Same cases as UtilizationMathTest.java, so Python and Java cannot drift."""
    assets = pd.DataFrame({"asset_tag": ["A"], "department": ["ICU"]})
    usage = _frame([("A", h(s), h(e)) for s, e in case["used"]]) if case["used"] else None
    down = _frame([("A", h(s), h(e)) for s, e in case["down"]]) if case["down"] else None
    r = utilization_by_asset(assets, usage, down, None, h(0), h(_cases["windowHours"])).iloc[0]
    exp = case["expected"]
    assert r.unavailable_h == pytest.approx(exp["unavailable"])
    assert r.available_h == pytest.approx(exp["available"])
    assert r.used_h == pytest.approx(exp["used"])
    if exp["utilization"] is None:
        assert r.utilization is None
    else:
        assert r.utilization == pytest.approx(exp["utilization"])


def test_sample_pipeline_end_to_end(tmp_path):
    from planner_analytics.sample_pipeline import build

    info = build(tmp_path, days=10)
    assert info["clean"] > 0 and info["rejected"] > 0
    assert sorted(info["dropped_columns"]) == ["MRN", "Patient Name"]
    for f in tmp_path.glob("*.csv"):
        if f.name != "usage_raw.csv":                      # the raw file is the messy input by design
            head = f.read_text().splitlines()[0].lower()
            assert "patient" not in head and "mrn" not in head, f.name
    asset_day = pd.read_csv(tmp_path / "asset_day_utilization.csv")
    assert asset_day["utilization"].dropna().between(0, 1.0001).all()
    assert (asset_day["available_h"] <= asset_day["total_h"] + 1e-9).all()
    dept = pd.read_csv(tmp_path / "department_day_utilization.csv")
    assert set(dept["department"]) == {"ICU", "Emergency", "Radiology", "Surgery"}
