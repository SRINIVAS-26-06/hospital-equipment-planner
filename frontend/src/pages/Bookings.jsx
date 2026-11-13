import { useState } from "react";
import { get, post } from "../api.js";
import { Badge, Card, Empty, Field, Msg, Pager, fmt, localInput, toIso, useAction, useData } from "../components/ui.jsx";

export default function Bookings({ ctx }) {
  const { role, routeId } = ctx;
  const [f, setF] = useState({ mine: role === "STAFF", status: "ACTIVE", upcoming: true });
  const [page, setPage] = useState(0);
  const [msg, setMsg] = useState(null);
  const [form, setForm] = useState({ assetId: routeId && /^\d+$/.test(routeId) ? routeId : "", startAt: localInput(1), endAt: localInput(3), purpose: "" });
  const assets = useData(() => get("/api/assets", { size: 200 }), []);
  const list = useData(() => get("/api/bookings", { ...f, page, size: 12 }), [JSON.stringify(f), page], 20000);
  const run = useAction(setMsg, list.reload);
  const canBook = role === "STAFF" || role === "ADMIN";
  const bookable = (assets.data?.items || []).filter((a) => !a.hardBlocked);
  const selected = (assets.data?.items || []).find((a) => String(a.id) === String(form.assetId));
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  return (
    <div>
      <div className="page-h"><div><h2>Bookings</h2><p>Reserve equipment. Overlaps are rejected by the database itself, so two people can never hold the same slot.</p></div></div>
      <Msg msg={msg} onClose={() => setMsg(null)} />
      {canBook && (
        <Card title="New booking">
          <form onSubmit={(e) => { e.preventDefault(); run(() => post("/api/bookings", { assetId: Number(form.assetId), startAt: toIso(form.startAt), endAt: toIso(form.endAt), purpose: form.purpose || undefined }), "Booking confirmed"); }}>
            <div className="form">
              <Field label="Equipment"><select required value={form.assetId} onChange={set("assetId")}><option value="">Select…</option>{bookable.map((a) => <option key={a.id} value={a.id}>{a.assetTag} · {a.name} ({a.departmentName})</option>)}</select></Field>
              <Field label="Start"><input type="datetime-local" required value={form.startAt} onChange={set("startAt")} /></Field>
              <Field label="End"><input type="datetime-local" required value={form.endAt} onChange={set("endAt")} /></Field>
              <Field label="Purpose" hint="Never include patient names or MRNs"><input maxLength={200} value={form.purpose} onChange={set("purpose")} /></Field>
            </div>
            {selected && <p className="muted" style={{ fontSize: 13 }}>Currently <Badge value={selected.availability} /></p>}
            <div className="actions"><button className="primary">Confirm booking</button></div>
          </form>
        </Card>
      )}
      <div className="chips" style={{ margin: "16px 0 10px" }}>
        {role !== "VIEWER" && <button className={`chip ${f.mine ? "on" : ""}`} onClick={() => { setF({ ...f, mine: !f.mine }); setPage(0); }}>Only mine</button>}
        {[["ACTIVE", "Active"], ["CANCELLED", "Cancelled"], ["", "All"]].map(([v, l]) => <button key={l} className={`chip ${f.status === v ? "on" : ""}`} onClick={() => { setF({ ...f, status: v, upcoming: v === "ACTIVE" }); setPage(0); }}>{l}</button>)}
      </div>
      {!list.data ? <div className="skeleton" style={{ height: 200 }} /> : !list.data.items.length ? <Card title="Bookings"><Empty>No bookings match.</Empty></Card> : (
        <div className="table-wrap"><table>
          <thead><tr><th>Equipment</th><th>When</th><th>Booked by</th><th>Status</th><th /></tr></thead>
          <tbody>{list.data.items.map((b) => (
            <tr key={b.id}><td><b>{b.assetTag}</b><span className="sub">{b.assetName} · {b.departmentName}</span></td>
              <td>{fmt(b.startAt)}<span className="sub">to {fmt(b.endAt)}</span></td>
              <td>{b.requestedBy}{b.purpose && <span className="sub">{b.purpose}</span>}</td>
              <td><Badge value={b.status} />{b.cancelReason && <span className="sub">{b.cancelReason}</span>}</td>
              <td>{b.canCancel && <button className="danger" onClick={() => window.confirm("Cancel this booking?") && run(() => post(`/api/bookings/${b.id}/cancel`, {}), "Booking cancelled")}>Cancel</button>}</td></tr>))}</tbody></table></div>
      )}
      <Pager page={list.data} onPage={setPage} />
    </div>
  );
}
