"""Builds a realistic sample dataset end to end so Power BI has data to load:

  raw messy logs (mixed id formats, patient columns, duplicates, bad rows)
    -> ingest.clean_usage -> utilization per asset-day and per department-day

Usage: python -m planner_analytics.sample_pipeline --out ../powerbi/sample
"""
import argparse
import random
from pathlib import Path

import pandas as pd

from .ingest import clean_usage
from .utilization import _flag, utilization_by_asset

ASSETS = [  # tag, name, category, department, target utilization
    ("VENT-0001", "Ventilator A", "Ventilator", "ICU", 0.92),
    ("VENT-0002", "Ventilator B", "Ventilator", "Emergency", 0.55),
    ("VENT-0003", "Ventilator C", "Ventilator", "ICU", 0.12),
    ("INFP-0001", "Infusion Pump A", "Infusion Pump", "ICU", 0.88),
    ("INFP-0002", "Infusion Pump B", "Infusion Pump", "Surgery", 0.40),
    ("XRAY-0001", "Portable X-Ray", "Imaging", "Radiology", 0.20),
    ("XRAY-0002", "Portable X-Ray 2", "Imaging", "Emergency", 0.60),
    ("DEFB-0001", "Defibrillator A", "Defibrillator", "Emergency", 0.10),
    ("DEFB-0002", "Defibrillator B", "Defibrillator", "Surgery", 0.35),
    ("USND-0001", "Ultrasound A", "Imaging", "Surgery", 0.70),
    ("USND-0002", "Ultrasound B", "Imaging", "Radiology", 0.50),
    ("MONT-0001", "Patient Monitor A", "Monitor", "ICU", 0.86),
]
PATIENT_NOISE = ["Jane Doe", "John Roe", "A. Patient"]


def _messy(tag: str, rng: random.Random) -> str:
    prefix, num = tag.split("-")
    return rng.choice([tag, f"{prefix.lower()} {int(num)}", f"{prefix}_{num[1:]}", f"{prefix.title()}-{int(num):03d}",
                       f"{prefix}{num}"])


def generate(end: pd.Timestamp, days: int = 60, seed: int = 7):
    rng = random.Random(seed)
    start = end - pd.Timedelta(days=days)
    assets = pd.DataFrame(ASSETS, columns=["asset_tag", "name", "category", "department", "target"])

    maint_rows = []
    for tag, *_ in ASSETS:
        day = rng.randint(3, 25)
        while day < days:
            s = start + pd.Timedelta(days=day, hours=rng.randint(0, 16))
            maint_rows.append((tag, s, s + pd.Timedelta(hours=6), "PREVENTIVE"))
            day += rng.randint(26, 35)
    maint = pd.DataFrame(maint_rows, columns=["asset_tag", "start", "end", "kind"])

    rows = []
    for tag, _, _, _, target in ASSETS:
        m = maint[maint["asset_tag"] == tag]
        for d in range(days):
            day0 = start + pd.Timedelta(days=d)
            hours = max(0.0, min(22.0, rng.gauss(target * 24, 2.0)))
            sessions = rng.randint(1, 3)
            per = hours / sessions
            slot = 24 / sessions
            for k in range(sessions):
                s = day0 + pd.Timedelta(hours=k * slot + rng.uniform(0, max(0.0, slot - per)))
                e = s + pd.Timedelta(hours=per)
                if e - s < pd.Timedelta(minutes=5):
                    continue
                if ((m["start"] < e) & (m["end"] > s)).any():
                    continue  # nobody uses a device that is in the workshop
                rows.append((_messy(tag, rng), s, e, rng.choice(["bedside", "scanner", "manual"]),
                             rng.choice(PATIENT_NOISE), str(rng.randint(10000, 99999))))
    raw = pd.DataFrame(rows, columns=["Equipment ID", "Start Time", "End Time", "System", "Patient Name", "MRN"])

    # incomplete / inconsistent logs
    bad = raw.sample(frac=0.01, random_state=seed)
    raw = pd.concat([raw, bad], ignore_index=True)                                        # exact duplicates
    raw.loc[raw.sample(frac=0.005, random_state=seed + 1).index, "End Time"] = None       # missing end
    raw.loc[raw.sample(frac=0.005, random_state=seed + 2).index, "Equipment ID"] = "ZZZ-9999"  # unknown asset
    return assets, raw, maint


def daily(assets, usage, maint, start, days):
    frames = []
    for d in range(days):
        s = start + pd.Timedelta(days=d)
        df = utilization_by_asset(assets[["asset_tag", "department"]], usage, maint, None, s, s + pd.Timedelta(days=1))
        df.insert(0, "day", s.date().isoformat())
        frames.append(df)
    return pd.concat(frames, ignore_index=True)


def department_daily(asset_day: pd.DataFrame) -> pd.DataFrame:
    g = asset_day.groupby(["day", "department"], as_index=False)[
        ["total_h", "unavailable_h", "available_h", "used_h", "idle_h"]].sum()
    g["utilization"] = g["used_h"] / g["available_h"]          # ratio of sums
    g["flag"] = g["utilization"].map(_flag)
    return g


def build(out: Path, end=None, days=60, seed=7):
    end = pd.Timestamp(end or "2026-11-01", tz="UTC")
    assets, raw, maint = generate(end, days, seed)
    known = set(assets["asset_tag"])
    clean, rejects, dropped = clean_usage(raw, known_tags=known)
    start = end - pd.Timedelta(days=days)
    asset_day = daily(assets, clean, maint, start, days)
    dept_day = department_daily(asset_day)

    out.mkdir(parents=True, exist_ok=True)
    assets.drop(columns="target").to_csv(out / "assets.csv", index=False)
    raw.to_csv(out / "usage_raw.csv", index=False)
    clean.to_csv(out / "usage_clean.csv", index=False)
    rejects.to_csv(out / "usage_rejects.csv", index=False)
    maint.to_csv(out / "maintenance.csv", index=False)
    asset_day.to_csv(out / "asset_day_utilization.csv", index=False)
    dept_day.to_csv(out / "department_day_utilization.csv", index=False)
    return {"clean": len(clean), "rejected": len(rejects), "dropped_columns": dropped,
            "asset_days": len(asset_day)}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="sample")
    ap.add_argument("--days", type=int, default=60)
    a = ap.parse_args()
    print(build(Path(a.out), days=a.days))
