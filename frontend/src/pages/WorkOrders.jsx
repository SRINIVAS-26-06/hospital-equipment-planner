import { useEffect, useState } from "react";
import { get, post } from "../api.js";
import Documents from "../components/Documents.jsx";
import { Badge, Card, Empty, Field, Modal, Msg, Pager, fmt, useAction, useData } from "../components/ui.jsx";

const FLOW = ["REPORTED", "TRIAGED", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "VERIFIED", "CLOSED"];

function Stepper({ status }) {
  const at = FLOW.indexOf(status);
  return <div className="chips" style={{ margin: "6px 0 12px" }}>{FLOW.map((s, i) => <span key={s} className={`chip ${i <= at ? "on" : ""}`}>{s.replace("_", " ").toLowerCase()}</span>)}</div>;
}

function Detail({ id, ctx, onClose, onChanged }) {
  const { role, me } = ctx;
  const [msg, setMsg] = useState(null);
  const [f, setF] = useState({ priority: "", severity: "", unsafe: false, note: "", engineer: "", repairDetails: "", partsReplaced: "", resolutionRemarks: "" });
  const d = useData(() => get(`/api/work-orders/${id}`), [id]);
  const engineers = useData(() => (role === "ADMIN" ? get("/api/users", { role: "ENGINEER", active: true, size: 100 }) : Promise.resolve(null)), []);
  const run = useAction(setMsg, () => { d.reload(); onChanged(); });
  if (!d.data) return <Modal title="Work order" onClose={onClose}><div className="skeleton" /></Modal>;
  const w = d.data.workOrder;
  const act = (path, body, ok) => run(() => post(`/api/work-orders/${id}/${path}`, body ?? {}), ok);
  return (
    <Modal title={`#${w.id} · ${w.assetTag} · ${w.title}`} onClose={onClose} wide>
      <Msg msg={msg} onClose={() => setMsg(null)} />
      <Stepper status={w.status} />
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}><Badge value={w.priority} /><Badge value={w.severity} />{w.unsafe && <Badge value="CRITICAL" />}{w.unsafe && <span className="badge b-critical">UNSAFE — not bookable</span>}{w.blocking && !w.unsafe && <span className="badge b-warning">equipment blocked</span>}</div>
      <p>{w.description}</p>
      <div className="kv"><div><span>Reported</span>{w.reportedBy} · {fmt(w.reportedAt)}</div><div><span>Engineer</span>{w.assignedEngineer || "–"}</div><div><span>Started</span>{fmt(w.startedAt)}</div><div><span>Resolved</span>{fmt(w.resolvedAt)}</div>
        <div><span>Downtime</span>{w.downtimeHours != null ? `${w.downtimeHours} h` : "–"}</div><div><span>Verified</span>{w.verifiedBy ? `${w.verifiedBy} · ${fmt(w.verifiedAt)}` : "–"}</div></div>
      {w.repairDetails && <><h4>Repair</h4><p>{w.repairDetails}{w.partsReplaced && <><br /><small className="muted">Parts replaced: {w.partsReplaced}</small></>}{w.resolutionRemarks && <><br /><small className="muted">{w.resolutionRemarks}</small></>}</p></>}

      {w.canTriage && <Card title="Triage"><div className="form"><Field label="Priority"><select value={f.priority} onChange={(e) => setF({ ...f, priority: e.target.value })}><option value="">Keep ({w.priority})</option>{["LOW", "MEDIUM", "HIGH", "CRITICAL"].map((p) => <option key={p}>{p}</option>)}</select></Field>
        <Field label="Severity"><select value={f.severity} onChange={(e) => setF({ ...f, severity: e.target.value })}><option value="">Keep ({w.severity})</option>{["MINOR", "MAJOR", "CRITICAL"].map((p) => <option key={p}>{p}</option>)}</select></Field>
        <Field label="Unsafe"><select value={String(f.unsafe)} onChange={(e) => setF({ ...f, unsafe: e.target.value === "true" })}><option value="false">No</option><option value="true">Yes — cancel bookings</option></select></Field>
        <Field label="Note"><input value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} /></Field></div>
        <div className="actions"><button className="primary" onClick={() => act("triage", { priority: f.priority || undefined, severity: f.severity || undefined, unsafe: f.unsafe || w.unsafe, note: f.note || undefined }, "Triaged")}>Triage</button></div></Card>}
      {w.canAssign && <Card title="Assign engineer">{role === "ADMIN" ? <div className="toolbar" style={{ marginBottom: 0 }}><Field label="Engineer"><select value={f.engineer} onChange={(e) => setF({ ...f, engineer: e.target.value })}><option value="">Select…</option>{(engineers.data?.items || []).map((u) => <option key={u.id} value={u.username}>{u.fullName || u.username}</option>)}</select></Field>
        <button className="primary btn" disabled={!f.engineer} onClick={() => act("assign", { engineer: f.engineer }, "Engineer assigned")}>Assign</button></div>
        : <button className="primary" onClick={() => act("assign", { engineer: me.username }, "Assigned to you")}>Take this work order</button>}</Card>}
      {w.canResolve && <Card title="Record repair"><div className="form"><div className="full"><Field label="Repair details *"><textarea value={f.repairDetails} onChange={(e) => setF({ ...f, repairDetails: e.target.value })} /></Field></div>
        <Field label="Parts replaced"><input value={f.partsReplaced} onChange={(e) => setF({ ...f, partsReplaced: e.target.value })} /></Field><Field label="Resolution remarks"><input value={f.resolutionRemarks} onChange={(e) => setF({ ...f, resolutionRemarks: e.target.value })} /></Field></div>
        <div className="actions"><button className="primary" disabled={!f.repairDetails.trim()} onClick={() => act("resolve", { repairDetails: f.repairDetails, partsReplaced: f.partsReplaced || undefined, resolutionRemarks: f.resolutionRemarks || undefined }, "Marked resolved; the reporter will be asked to verify")}>Mark resolved</button></div></Card>}
      <div className="actions">
        {w.canStart && <button className="primary" onClick={() => act("start", {}, "Repair started")}>Start repair</button>}
        {w.canVerify && <button className="primary" onClick={() => act("verify", {}, "Fix verified")}>Verify the fix</button>}
        {w.canReopen && <button className="danger" onClick={() => act("reopen", { note: "Fix not accepted" }, "Reopened")}>Fix not accepted</button>}
        {w.canClose && <button className="primary" onClick={() => act("close", {}, "Work order closed")}>Close</button>}
      </div>
      <h4>History</h4><ul className="timeline">{d.data.history.map((h, i) => <li key={i}><b>{h.fromStatus ? `${h.fromStatus.replace("_", " ")} → ` : ""}{h.toStatus.replace("_", " ")}</b><small>{fmt(h.at)} · {h.actor}{h.note ? ` · ${h.note}` : ""}</small></li>)}</ul>
      {role !== "VIEWER" && <><h4>Documents</h4><Documents entityType="WORK_ORDER" entityId={id}
        canUpload={role === "ADMIN" || (role === "ENGINEER" && w.assignedEngineer === me.username) || (role === "STAFF" && w.reportedBy === me.username)} setMsg={setMsg} /></>}
    </Modal>
  );
}

export default function WorkOrders({ ctx }) {
  const { role, routeId, go } = ctx;
  const [f, setF] = useState({ openOnly: true, mine: false });
  const [page, setPage] = useState(0);
  const [msg, setMsg] = useState(null);
  const [open, setOpen] = useState(null);
  const startNew = routeId?.startsWith("new-") ? routeId.slice(4) : "";
  const [adding, setAdding] = useState(!!startNew);
  const [form, setForm] = useState({ assetId: startNew, title: "", description: "", severity: "MINOR", unsafe: false });
  useEffect(() => { if (startNew) { setForm((x) => ({ ...x, assetId: startNew })); setAdding(true); } }, [startNew]);
  const assets = useData(() => get("/api/assets", { size: 200 }), []);
  const list = useData(() => get("/api/work-orders", { ...f, page, size: 12 }), [JSON.stringify(f), page], 20000);
  const run = useAction(setMsg, list.reload);
  const submit = async (e) => {
    e.preventDefault();
    const r = await run(() => post("/api/work-orders", { assetId: Number(form.assetId), title: form.title, description: form.description, severity: form.severity, unsafe: form.unsafe }), "Breakdown reported. Engineering has been notified.");
    if (r) { setAdding(false); go("workorders"); setForm({ assetId: "", title: "", description: "", severity: "MINOR", unsafe: false }); }
  };
  return (
    <div>
      <div className="page-h"><div><h2>{role === "STAFF" ? "Breakdowns" : "Work orders"}</h2><p>Reported → Triaged → Assigned → In progress → Resolved → Verified → Closed. Equipment with an unresolved breakdown cannot be booked.</p></div>
        {role !== "VIEWER" && <button className="primary" onClick={() => setAdding(true)}>+ Report breakdown</button>}</div>
      <Msg msg={msg} onClose={() => setMsg(null)} />
      <div className="chips" style={{ marginBottom: 12 }}>
        <button className={`chip ${f.openOnly ? "on" : ""}`} onClick={() => { setF({ ...f, openOnly: !f.openOnly }); setPage(0); }}>Open only</button>
        {(role === "ENGINEER" || role === "ADMIN") && <button className={`chip ${f.mine ? "on" : ""}`} onClick={() => { setF({ ...f, mine: !f.mine }); setPage(0); }}>Assigned to me</button>}
      </div>
      {!list.data ? <div className="skeleton" style={{ height: 200 }} /> : !list.data.items.length ? <Card title="Work orders"><Empty>{role === "STAFF" ? "You have not reported any breakdowns." : "No work orders match."}</Empty></Card> : (
        <div className="table-wrap"><table><thead><tr><th>#</th><th>Equipment</th><th>Problem</th><th>Priority</th><th>Status</th><th>Engineer</th></tr></thead>
          <tbody>{list.data.items.map((w) => <tr key={w.id} className="click" onClick={() => setOpen(w.id)}><td>{w.id}</td><td><b>{w.assetTag}</b><span className="sub">{w.assetName}</span></td>
            <td>{w.title}{w.unsafe && <span className="badge b-critical" style={{ marginLeft: 6 }}>UNSAFE</span>}<span className="sub">{fmt(w.reportedAt)} · {w.reportedBy}</span></td>
            <td><Badge value={w.priority} /></td><td><Badge value={w.status} /></td><td>{w.assignedEngineer || "–"}</td></tr>)}</tbody></table></div>)}
      <Pager page={list.data} onPage={setPage} />
      {adding && <Modal title="Report a breakdown" onClose={() => { setAdding(false); go("workorders"); }}>
        <form onSubmit={submit}><div className="form">
          <Field label="Equipment"><select required value={form.assetId} onChange={(e) => setForm({ ...form, assetId: e.target.value })}><option value="">Select…</option>{(assets.data?.items || []).map((a) => <option key={a.id} value={a.id}>{a.assetTag} · {a.name}</option>)}</select></Field>
          <Field label="Severity"><select value={form.severity} onChange={(e) => setForm({ ...form, severity: e.target.value })}><option value="MINOR">Minor — still works</option><option value="MAJOR">Major — impaired</option><option value="CRITICAL">Critical — not working</option></select></Field>
          <div className="full"><Field label="What is wrong? *"><input required maxLength={200} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} /></Field></div>
          <div className="full"><Field label="Details *" hint="Describe the fault only. Never include patient names or MRNs."><textarea required value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></Field></div>
          <div className="full"><label style={{ display: "flex", gap: 8, alignItems: "center" }}><input style={{ width: "auto" }} type="checkbox" checked={form.unsafe} onChange={(e) => setForm({ ...form, unsafe: e.target.checked })} /> This equipment is unsafe to use</label></div></div>
        {form.unsafe && <div className="warnbox">Marking it unsafe cancels its active and upcoming bookings immediately and keeps it blocked until the fix is verified.</div>}
        <div className="actions"><button className="primary">Report breakdown</button></div></form></Modal>}
      {open && <Detail id={open} ctx={ctx} onClose={() => setOpen(null)} onChanged={list.reload} />}
    </div>
  );
}
