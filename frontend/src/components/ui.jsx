import { useCallback, useEffect, useRef, useState } from "react";

export const fmt = (v) => (v ? new Date(v).toLocaleString([], { dateStyle: "medium", timeStyle: "short" }) : "–");
export const fmtDate = (v) => (v ? new Date(v).toLocaleDateString([], { dateStyle: "medium" }) : "–");
export const pct = (u) => (u == null ? "n/a" : `${(u * 100).toFixed(1)}%`);
export const num = (v, d = 1) => (v == null ? "–" : Number(v).toFixed(d));
export const toIso = (local) => (local ? new Date(local).toISOString() : null);
export const localInput = (offsetHours = 0) => {
  const d = new Date(Date.now() + offsetHours * 36e5);
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
};

export function Badge({ value }) {
  return <span className={`badge b-${String(value).toLowerCase()}`}>{String(value).replaceAll("_", " ")}</span>;
}

export function Msg({ msg, onClose }) {
  if (!msg) return null;
  return (
    <div className={`msg ${msg.type}`} role="status">
      <span>{msg.text}</span>
      {onClose && <button className="link" onClick={onClose}>dismiss</button>}
    </div>
  );
}

/** Runs an async action, shows the outcome, and reloads data. Errors come from the backend's {code,message}. */
export function useAction(setMsg, reload) {
  return useCallback(async (fn, okText) => {
    try {
      const r = await fn();
      setMsg({ type: "ok", text: okText });
      if (reload) reload();
      return r;
    } catch (e) {
      const fields = e.fieldErrors && Object.keys(e.fieldErrors).length ? " (" + Object.entries(e.fieldErrors).map(([k, v]) => `${k}: ${v}`).join("; ") + ")" : "";
      setMsg({ type: "err", text: `${e.code ? e.code + ": " : ""}${e.message}${fields}` });
      return null;
    }
  }, [setMsg, reload]);
}

/** Loads a list (or page) and re-loads on deps change, on demand, and on an optional poll interval. */
export function useData(fetcher, deps, pollMs) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const ref = useRef(fetcher);
  ref.current = fetcher;
  const load = useCallback(() => ref.current().then((d) => { setData(d); setError(null); }).catch((e) => setError(e.message)), []);
  useEffect(() => { load(); }, [load, ...deps]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!pollMs) return undefined;
    const t = setInterval(load, pollMs);
    return () => clearInterval(t);
  }, [load, pollMs]);
  return { data, error, reload: load };
}

export function Pager({ page, onPage }) {
  if (!page || page.totalPages <= 1) return page ? <div className="pager muted">{page.total} result{page.total === 1 ? "" : "s"}</div> : null;
  return (
    <div className="pager">
      <button disabled={page.page <= 0} onClick={() => onPage(page.page - 1)}>Prev</button>
      <span>Page {page.page + 1} of {page.totalPages} · {page.total} results</span>
      <button disabled={page.page + 1 >= page.totalPages} onClick={() => onPage(page.page + 1)}>Next</button>
    </div>
  );
}

export function Modal({ title, onClose, children, wide }) {
  useEffect(() => {
    const h = (e) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [onClose]);
  return (
    <div className="modal-bg" onMouseDown={onClose}>
      <div className={`modal ${wide ? "wide" : ""}`} role="dialog" aria-label={title} onMouseDown={(e) => e.stopPropagation()}>
        <div className="modal-h"><h3>{title}</h3><button className="link" onClick={onClose}>close</button></div>
        {children}
      </div>
    </div>
  );
}

export function Field({ label, children, hint }) {
  return <label className="field"><span>{label}</span>{children}{hint && <small className="muted">{hint}</small>}</label>;
}

export function Stat({ label, value, tone, onClick, sub }) {
  return (
    <button className={`stat ${tone || ""}`} onClick={onClick} disabled={!onClick}>
      <b>{value ?? "–"}</b><span>{label}</span>{sub && <small>{sub}</small>}
    </button>
  );
}

export function Card({ title, children, actions }) {
  return <section className="card"><div className="card-h"><h3>{title}</h3>{actions}</div>{children}</section>;
}

export function Empty({ children = "Nothing to show" }) {
  return <p className="muted empty">{children}</p>;
}

// ---------------- charts (dependency-free SVG/CSS) ----------------
export function Donut({ items, size = 150 }) {
  const total = items.reduce((s, i) => s + i.value, 0);
  if (!total) return <Empty>No data</Empty>;
  let acc = 0;
  const r = 52, c = 2 * Math.PI * r;
  return (
    <div className="donut">
      <svg viewBox="0 0 140 140" width={size} height={size} role="img" aria-label="distribution">
        <circle cx="70" cy="70" r={r} fill="none" stroke="#eef1f5" strokeWidth="18" />
        {items.filter((i) => i.value > 0).map((i) => {
          const len = (i.value / total) * c;
          const el = <circle key={i.label} cx="70" cy="70" r={r} fill="none" stroke={i.color} strokeWidth="18"
            strokeDasharray={`${len} ${c - len}`} strokeDashoffset={-acc} transform="rotate(-90 70 70)" />;
          acc += len;
          return el;
        })}
        <text x="70" y="75" textAnchor="middle" fontSize="22" fontWeight="700">{total}</text>
      </svg>
      <ul>{items.map((i) => <li key={i.label}><i style={{ background: i.color }} />{i.label}<b>{i.value}</b></li>)}</ul>
    </div>
  );
}

export function Bars({ items, max, unit = "", color = "#0b5cab" }) {
  if (!items.length) return <Empty>No data</Empty>;
  const top = max ?? Math.max(...items.map((i) => i.value), 0.0001);
  return (
    <div className="bars">
      {items.map((i) => (
        <div className="bar-row" key={i.label} title={`${i.label}: ${i.value}${unit}`}>
          <span className="bar-l">{i.label}</span>
          <div className="bar-t"><div style={{ width: `${Math.min(100, (i.value / top) * 100)}%`, background: i.color || color }} /></div>
          <span className="bar-v">{typeof i.display === "string" ? i.display : `${Number(i.value).toFixed(1)}${unit}`}</span>
        </div>
      ))}
    </div>
  );
}

export function Columns({ items, unit = "", color = "#0b5cab", height = 120 }) {
  if (!items.length) return <Empty>No data</Empty>;
  const top = Math.max(...items.map((i) => i.value), 0.0001);
  return (
    <div className="cols" style={{ height }}>
      {items.map((i) => (
        <div key={i.label} className="col" title={`${i.label}: ${Number(i.value).toFixed(1)}${unit}`}>
          <div className="col-bar" style={{ height: `${(i.value / top) * 100}%`, background: color }} />
          <small>{i.label}</small>
        </div>
      ))}
    </div>
  );
}

export function Line({ points, height = 130, ymax = 1, fmtY = (v) => `${Math.round(v * 100)}%` }) {
  const pts = points.filter((p) => p.value != null);
  if (pts.length < 2) return <Empty>Not enough data yet</Empty>;
  const W = 520, H = height, padL = 34, padB = 20;
  const x = (i) => padL + (i / (points.length - 1)) * (W - padL - 8);
  const y = (v) => 8 + (1 - Math.min(v, ymax) / ymax) * (H - padB - 8);
  const path = points.map((p, i) => (p.value == null ? null : `${x(i)},${y(p.value)}`)).filter(Boolean).join(" ");
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="line" role="img" aria-label="trend">
      {[0, 0.5, 1].map((t) => <g key={t}><line x1={padL} x2={W - 8} y1={y(t * ymax)} y2={y(t * ymax)} stroke="#e5e7eb" /><text x="2" y={y(t * ymax) + 4} fontSize="10" fill="#6b7280">{fmtY(t * ymax)}</text></g>)}
      <polyline points={path} fill="none" stroke="#0b5cab" strokeWidth="2.5" />
      {points.map((p, i) => p.value != null && <circle key={i} cx={x(i)} cy={y(p.value)} r="3" fill="#0b5cab"><title>{`${p.label}: ${fmtY(p.value)}`}</title></circle>)}
      <text x={padL} y={H - 4} fontSize="10" fill="#6b7280">{points[0].label}</text>
      <text x={W - 8} y={H - 4} fontSize="10" fill="#6b7280" textAnchor="end">{points[points.length - 1].label}</text>
    </svg>
  );
}

export const STATE_COLORS = {
  AVAILABLE: "#16a34a", BOOKED: "#2563eb", IN_USE: "#7c3aed", UNDER_MAINTENANCE: "#d97706",
  OVERDUE_MAINTENANCE: "#dc2626", UNDER_REPAIR: "#b91c1c", CALIBRATION_FAILED: "#be185d",
  OUT_OF_SERVICE: "#6b7280", RETIRED: "#9ca3af",
};
