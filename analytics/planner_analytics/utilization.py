"""Pandas implementation of the utilization model. Same formula as UtilizationService.java:

  available = window - unavailable (maintenance + downtime)
  used      = union(usage) inside window, minus time that overlaps unavailable
  idle      = available - used          (idle is not unavailable)
  utilization = used / available        (department = sum(used) / sum(available))
"""
import pandas as pd

UNDERUSED_BELOW = 0.30
OVERWORKED_ABOVE = 0.85


def _merge(spans):
    out = []
    for s, e in sorted(spans):
        if out and s <= out[-1][1]:
            out[-1] = (out[-1][0], max(out[-1][1], e))
        else:
            out.append((s, e))
    return out


def _clip(spans, lo, hi):
    return [(max(s, lo), min(e, hi)) for s, e in spans if min(e, hi) > max(s, lo)]


def _seconds(merged):
    return sum((e - s).total_seconds() for s, e in merged)


def _intersect(a, b):
    i = j = 0
    total = 0.0
    while i < len(a) and j < len(b):
        lo, hi = max(a[i][0], b[j][0]), min(a[i][1], b[j][1])
        if hi > lo:
            total += (hi - lo).total_seconds()
        if a[i][1] < b[j][1]:
            i += 1
        else:
            j += 1
    return total


def _spans(df, asset_tag):
    if df is None or df.empty:
        return []
    sub = df[df["asset_tag"] == asset_tag]
    return list(zip(sub["start"], sub["end"]))


def _flag(util):
    if util is None:
        return "NO_AVAILABLE_TIME"
    if util < UNDERUSED_BELOW:
        return "UNDERUSED"
    if util > OVERWORKED_ABOVE:
        return "OVERWORKED"
    return "NORMAL"


def utilization_by_asset(assets, usage, maintenance, downtime, start, end):
    """assets: asset_tag, department. usage/maintenance/downtime: asset_tag,start,end (tz-aware)."""
    start, end = pd.Timestamp(start, tz="UTC") if pd.Timestamp(start).tzinfo is None else pd.Timestamp(start), \
        pd.Timestamp(end, tz="UTC") if pd.Timestamp(end).tzinfo is None else pd.Timestamp(end)
    total = (end - start).total_seconds()
    rows = []
    for a in assets.itertuples():
        down = _merge(_clip(_spans(maintenance, a.asset_tag) + _spans(downtime, a.asset_tag), start, end))
        used = _merge(_clip(_spans(usage, a.asset_tag), start, end))
        unavail = _seconds(down)
        used_s = _seconds(used) - _intersect(used, down)
        avail = max(0.0, total - unavail)
        util = used_s / avail if avail > 0 else None
        rows.append({
            "asset_tag": a.asset_tag, "department": a.department,
            "total_h": total / 3600, "unavailable_h": unavail / 3600, "available_h": avail / 3600,
            "used_h": used_s / 3600, "idle_h": (avail - used_s) / 3600,
            "utilization": util, "flag": _flag(util),
        })
    return pd.DataFrame(rows)


def utilization_by_department(per_asset: pd.DataFrame) -> pd.DataFrame:
    g = per_asset.groupby("department", as_index=False)[["total_h", "unavailable_h", "available_h", "used_h", "idle_h"]].sum()
    g["utilization"] = g.apply(lambda r: r.used_h / r.available_h if r.available_h > 0 else None, axis=1)
    g["flag"] = g["utilization"].map(lambda u: _flag(None if pd.isna(u) else u))
    return g



def _col(df, name):
    return df[name] if df is not None and not df.empty and name in df.columns else None


def _down_spans(tag, now, f):
    """Unavailable time for one asset: maintenance, downtime events, breakdowns, failed calibration, overdue lockout."""
    spans = []
    m = f.get("maintenance")
    if m is not None and not m.empty:
        for r in m[(m["asset_tag"] == tag) & (m["status"] != "CANCELLED")].itertuples():
            if r.status == "COMPLETED":
                s = r.actual_start if pd.notna(r.actual_start) else r.scheduled_start
                e = r.actual_end if pd.notna(r.actual_end) else r.scheduled_end
            elif r.status == "IN_PROGRESS":
                s = r.actual_start if pd.notna(r.actual_start) else r.scheduled_start
                e = max(now, s)
            else:  # SCHEDULED counts only once its start has arrived
                s, e = r.scheduled_start, min(r.scheduled_end, now)
            if e > s:
                spans.append((s, e))
    d = f.get("downtime")
    if d is not None and not d.empty:
        for r in d[d["asset_tag"] == tag].itertuples():
            e = r.end_at if pd.notna(r.end_at) else now
            if e > r.start_at:
                spans.append((r.start_at, e))
    b = f.get("breakdowns")
    if b is not None and not b.empty:
        for r in b[b["asset_tag"] == tag].itertuples():
            if r.status in ("REPORTED", "TRIAGED", "ASSIGNED", "IN_PROGRESS"):
                e = now
            elif r.unsafe:
                e = r.verified_at if pd.notna(r.verified_at) else now
            else:
                e = r.resolved_at if pd.notna(r.resolved_at) else now
            if e > r.reported_at:
                spans.append((r.reported_at, e))
    c = f.get("calibration")
    if c is not None and not c.empty:
        done = c[(c["asset_tag"] == tag) & (c["status"] == "COMPLETED")]
        passes = sorted(done[done["result"] == "PASS"]["performed_at"])
        for r in done[done["result"] == "FAIL"].itertuples():
            later = [p for p in passes if p > r.performed_at]
            e = later[0] if later else now
            if e > r.performed_at:
                spans.append((r.performed_at, e))
    return spans


def _overdue_now(row, now):
    due = row.next_maintenance_due if pd.notna(row.next_maintenance_due) else None
    by_date = due if due is not None and due < now else None
    by_hours = row.hours_overdue_since if pd.notna(row.hours_overdue_since) else None
    cands = [x for x in (by_date, by_hours) if x is not None]
    return min(cands) if cands else None


def utilization_asset_day(frames, now, days):
    """One row per (UTC day, asset, department-at-the-time). Department figures are ratio-of-sums later."""
    now = pd.Timestamp(now)
    eq, usage, hist = frames["equipment"], frames.get("usage"), frames["dept_history"]
    first_day = (now.normalize() - pd.Timedelta(days=days - 1))
    rows = []
    for a in eq.itertuples():
        spans = _down_spans(a.asset_tag, now, frames)
        od = _overdue_now(a, now)
        if od is not None and now > od:
            spans.append((od, now))
        used = []
        if usage is not None and not usage.empty:
            u = usage[usage["asset_tag"] == a.asset_tag]
            used = list(zip(u["start_at"], u["end_at"]))
        periods = hist[hist["asset_tag"] == a.asset_tag].sort_values("effective_from", na_position="first")
        for k in range(days):
            ds = first_day + pd.Timedelta(days=k)
            de = min(ds + pd.Timedelta(days=1), now)
            lo = max(ds, a.in_service_from) if pd.notna(a.in_service_from) else ds
            hi = min(de, a.retired_at) if pd.notna(a.retired_at) else de
            for p in periods.itertuples():
                pf = p.effective_from if pd.notna(p.effective_from) else pd.Timestamp.min.tz_localize("UTC")
                pt = p.effective_to if pd.notna(p.effective_to) else pd.Timestamp.max.tz_localize("UTC")
                lo2, hi2 = max(lo, pf), min(hi, pt)
                if hi2 <= lo2:
                    continue
                down = _merge(_clip(spans, lo2, hi2))
                usedm = _merge(_clip(used, lo2, hi2))
                total = (hi2 - lo2).total_seconds()
                unavail = _seconds(down)
                used_s = _seconds(usedm) - _intersect(usedm, down)
                avail = max(0.0, total - unavail)
                util = used_s / avail if avail > 0 else None
                rows.append({"day": ds.date().isoformat(), "asset_tag": a.asset_tag, "department": p.department,
                             "total_h": total / 3600, "unavailable_h": unavail / 3600, "available_h": avail / 3600,
                             "used_h": used_s / 3600, "idle_h": (avail - used_s) / 3600,
                             "utilization": util, "flag": _flag(util)})
    cols = ["day", "asset_tag", "department", "total_h", "unavailable_h", "available_h", "used_h", "idle_h", "utilization", "flag"]
    return pd.DataFrame(rows, columns=cols)
