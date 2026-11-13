"""Clean raw equipment usage logs and (optionally) ship them to S3 and the planner API.

Usage:
  python -m planner_analytics.ingest raw_usage.csv --out out/ [--aliases aliases.csv]
         [--s3-bucket my-bucket --s3-prefix usage/] [--api-url http://localhost:8080]
"""
import argparse
import os
import sys
from pathlib import Path

import pandas as pd

from .ids import normalize_asset_id

# Allow-list: anything else (patient name, MRN, room, notes...) is dropped, never written out.
COLUMN_ALIASES = {
    "asset_id": {"asset_id", "assetid", "asset", "equipment_id", "equipment", "device_id", "asset_tag"},
    "start": {"start", "start_time", "start_at", "begin", "timestamp_start", "used_from"},
    "end": {"end", "end_time", "end_at", "stop", "timestamp_end", "used_to"},
    "source": {"source", "system", "origin"},
}


def _map_columns(df: pd.DataFrame) -> tuple[pd.DataFrame, list[str]]:
    rename, keep = {}, []
    for col in df.columns:
        key = str(col).strip().lower().replace(" ", "_")
        for canon, names in COLUMN_ALIASES.items():
            if key in names and canon not in rename.values():
                rename[col] = canon
                keep.append(col)
                break
    dropped = [str(c) for c in df.columns if c not in keep]
    return df[keep].rename(columns=rename), dropped


def clean_usage(raw: pd.DataFrame, aliases: dict[str, str] | None = None,
                known_tags: set[str] | None = None) -> tuple[pd.DataFrame, pd.DataFrame, list[str]]:
    """Returns (clean, rejects, dropped_columns)."""
    aliases = aliases or {}
    df, dropped = _map_columns(raw)
    for needed in ("asset_id", "start", "end"):
        if needed not in df.columns:
            raise ValueError(f"missing required column: {needed}")
    if "source" not in df.columns:
        df["source"] = "batch"

    df = df.copy()
    df["raw_asset_id"] = df["asset_id"]
    df["asset_tag"] = df["asset_id"].map(normalize_asset_id).map(lambda t: aliases.get(t, t))
    df["start"] = pd.to_datetime(df["start"], utc=True, errors="coerce")
    df["end"] = pd.to_datetime(df["end"], utc=True, errors="coerce")

    reason = pd.Series("", index=df.index)
    reason[df["asset_tag"].isna()] = "MISSING_ASSET"
    if known_tags is not None:
        reason[(reason == "") & ~df["asset_tag"].isin(known_tags)] = "UNKNOWN_ASSET"
    reason[(reason == "") & (df["start"].isna() | df["end"].isna())] = "BAD_TIMESTAMP"
    reason[(reason == "") & (df["end"] <= df["start"])] = "INVALID_SPAN"
    dup = df.duplicated(subset=["asset_tag", "start", "end"], keep="first")
    reason[(reason == "") & dup] = "DUPLICATE"

    # overlapping spans for one asset: keep the first, flag the rest (matches the API rule)
    ok = df[reason == ""].sort_values(["asset_tag", "start"])
    prev_end, prev_tag, overlaps = None, None, []
    for idx, row in ok.iterrows():
        if row["asset_tag"] == prev_tag and prev_end is not None and row["start"] < prev_end:
            overlaps.append(idx)
            continue
        prev_tag, prev_end = row["asset_tag"], row["end"]
    reason[overlaps] = "DUPLICATE_OR_OVERLAP"

    cols = ["asset_tag", "start", "end", "source"]
    clean = df[reason == ""][cols].sort_values(["asset_tag", "start"]).reset_index(drop=True)
    rejects = df[reason != ""][["raw_asset_id", "start", "end"]].assign(reason=reason[reason != ""])
    return clean, rejects.reset_index(drop=True), dropped


def push_to_api(clean: pd.DataFrame, api_url: str, batch: int = 500) -> dict:
    import requests

    sent = {"accepted": 0, "rejected": 0}
    for i in range(0, len(clean), batch):
        part = clean.iloc[i:i + batch]
        body = [
            {"assetRef": r.asset_tag, "startAt": r.start.isoformat().replace("+00:00", "Z"),
             "endAt": r.end.isoformat().replace("+00:00", "Z"), "source": r.source}
            for r in part.itertuples()
        ]
        user, pw = os.environ.get("PLANNER_API_USER"), os.environ.get("PLANNER_API_PASSWORD")
        if not user or not pw:
            raise SystemExit("PLANNER_API_USER and PLANNER_API_PASSWORD are required to push to the API")
        resp = requests.post(f"{api_url.rstrip('/')}/api/usage", json=body, timeout=60, auth=(user, pw))
        resp.raise_for_status()
        data = resp.json()
        sent["accepted"] += data["accepted"]
        sent["rejected"] += len(data["rejected"])
        sent.setdefault("batches", []).append(data.get("batchId"))
    return sent


def upload_to_s3(path: Path, bucket: str, prefix: str) -> str:
    import boto3  # credentials come from the instance role (IAM), never from code

    key = f"{prefix.rstrip('/')}/{path.name}" if prefix else path.name
    boto3.client("s3").upload_file(str(path), bucket, key)
    return f"s3://{bucket}/{key}"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("--out", default="out")
    ap.add_argument("--aliases", help="CSV with columns alias,asset_tag")
    ap.add_argument("--assets", help="CSV with column asset_tag; rows for other tags are rejected")
    ap.add_argument("--s3-bucket")
    ap.add_argument("--s3-prefix", default="usage/")
    ap.add_argument("--api-url")
    a = ap.parse_args(argv)

    raw = pd.read_csv(a.input, dtype=str)
    aliases = None
    if a.aliases:
        al = pd.read_csv(a.aliases, dtype=str)
        aliases = {normalize_asset_id(r.alias): normalize_asset_id(r.asset_tag) for r in al.itertuples()}
    known = None
    if a.assets:
        known = set(pd.read_csv(a.assets, dtype=str)["asset_tag"].map(normalize_asset_id))

    clean, rejects, dropped = clean_usage(raw, aliases, known)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    clean.to_csv(out / "usage_clean.csv", index=False)
    rejects.to_csv(out / "usage_rejects.csv", index=False)
    print(f"clean={len(clean)} rejected={len(rejects)} dropped_columns={dropped}")
    if a.s3_bucket:
        print("uploaded", upload_to_s3(out / "usage_clean.csv", a.s3_bucket, a.s3_prefix))
    if a.api_url:
        print("api", push_to_api(clean, a.api_url))
    return 0


if __name__ == "__main__":
    sys.exit(main())
