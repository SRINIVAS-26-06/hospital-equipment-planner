import { useState } from "react";
import { get, patch, post } from "../api.js";
import Documents from "../components/Documents.jsx";
import { Badge, Card, Empty, Field, Modal, Msg, Pager, fmt, localInput, toIso, useAction, useData } from "../components/ui.jsx";

export default function Maintenance({ ctx }) {
  const { role, me } = ctx;
  const eng = role === "ENGINEER" || role === "ADMIN";
  const [f, setF] = useState({ status: "", mine: role === "ENGINEER" });
  const [page, setPage] = useState(0);
  const [msg, setMsg] = useState(null);
  const [adding, setAdding] = useState(false);
  const [sel, setSel] = useState(null);
  const [form, setForm] = useState({ kind: "PREVENTIVE", priority: "MEDIUM", startAt: localInput(24), endAt: localInput(28), assignedEngineer: "", checklist: "" });
  const list = useData(() => get("/api/maintenance", { ...f, page, size: 12 }), [JSON.stringify(f), page], 20000);
  const assets = useData(() => (eng ? get("/api/assets", { size: 200 }) : Promise.resolve(null)), []);
  const engineers = useData(() => (role === "ADMIN" ? get("/api/users", { role: "ENGINEER", active: true, size: 100 }) : Promise.resolve(null)), []);
  const run = useAction(setMsg, () => { list.reload(); });
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const schedule = async (e) => {
    e.preventDefault();
    const body = { assetId: Number(form.assetId), kind: form.kind, priority: form.priority, startAt: toIso(form.startAt), endAt: toIso(form.endAt),
      assignedEngineer: role === "ENGINEER" ? me.username : form.assignedEngineer || undefined,
      checklist: form.checklist ? form.checklist.split("\n").map((s) => s.trim()).filter(Boolean) : undefined };
    const r = await run(() => post("/api/maintenance", body), "Maintenance scheduled. Equipment stays available until the window starts.");
    if (r) setAdding(false);
  };

  return (
    <div>
      <div className="page-h"><div><h2>Maintenance</h2><p>Scheduled → In progress → Completed. Equipment is only restricted from its scheduled start (or when work actually begins), and counters reset only on completion.</p></div>
        {eng && <button className="primary" onClick={() => setAdding(true)}>+ Schedule maintenance</button>}</div>
      <Msg msg={msg} onClose={() => setMsg(null)} />
      <div className="chips" style={{ marginBottom: 12 }}>
        {["", "SCHEDULED", "IN_PROGRESS", "COMPLETED", "CANCELLED"].map((s) => <button key={s} className={`chip ${f.status === s ? "on" : ""}`} onClick={() => { setF({ ...f, status: s }); setPage(0); }}>{s ? s.replace("_", " ").toLowerCase() : "all"}</button>)}
        {role === "ENGINEER" && <button className={`chip ${f.mine ? "on" : ""}`} onClick={() => { setF({ ...f, mine: !f.mine }); setPage(0); }}>Assigned to me</button>}
      </div>
      {!list.data ? <div className="skeleton" style={{ height: 220 }} /> : !list.data.items.length ? <Card title="Maintenance"><Empty>No maintenance records match.</Empty></Card> : (
        <div className="table-wrap"><table><thead><tr><th>Equipment</th><th>Window</th><th>Priority</th><th>Engineer</th><th>Status</th><th /></tr></thead>
          <tbody>{list.data.items.map((m) => (
            <tr key={m.id}><td><b>{m.assetTag}</b><span className="sub">{m.assetName} · {m.kind.toLowerCase()}</span></td>
              <td>{fmt(m.startAt)}<span className="sub">to {fmt(m.endAt)}{m.actualStart ? ` · started ${fmt(m.actualStart)}` : ""}</span></td>
              <td><Badge value={m.priority} /></td><td>{m.assignedEngineer || <span className="muted">unassigned</span>}</td><td><Badge value={m.status} /></td>
              <td style={{ whiteSpace: "nowrap" }}>
                {m.canClaim && <button onClick={() => run(() => post(`/api/maintenance/${m.id}/assign`, { engineer: me.username }), "Assigned to you")}>Claim</button>}{" "}
                {m.canStart && <button className="primary" onClick={() => run(() => post(`/api/maintenance/${m.id}/start`, {}), "Maintenance started")}>Start</button>}{" "}
                {m.canComplete && <button className="primary" onClick={() => setSel(m)}>Complete</button>}{" "}
                <button onClick={() => setSel({ ...m, view: true })}>Details</button></td></tr>))}</tbody></table></div>
      )}
      <Pager page={list.data} onPage={setPage} />

      {adding && <Modal title="Schedule maintenance" onClose={() => setAdding(false)}>
        <form onSubmit={schedule}><div className="form">
          <Field label="Equipment"><select required value={form.assetId || ""} onChange={set("assetId")}><option value="">Select…</option>{(assets.data?.items || []).map((a) => <option key={a.id} value={a.id}>{a.assetTag} · {a.name}</option>)}</select></Field>
          <Field label="Kind"><select value={form.kind} onChange={set("kind")}><option>PREVENTIVE</option><option>CORRECTIVE</option><option>INSPECTION</option></select></Field>
          <Field label="Priority"><select value={form.priority} onChange={set("priority")}>{["LOW", "MEDIUM", "HIGH", "CRITICAL"].map((p) => <option key={p}>{p}</option>)}</select></Field>
          {role === "ADMIN" && <Field label="Engineer"><select value={form.assignedEngineer} onChange={set("assignedEngineer")}><option value="">Unassigned</option>{(engineers.data?.items || []).map((u) => <option key={u.id} value={u.username}>{u.fullName || u.username}</option>)}</select></Field>}
          <Field label="Start"><input type="datetime-local" required value={form.startAt} onChange={set("startAt")} /></Field>
          <Field label="End"><input type="datetime-local" required value={form.endAt} onChange={set("endAt")} /></Field>
          <div className="full"><Field label="Checklist (one item per line)" hint="Leave empty for the standard checklist"><textarea value={form.checklist} onChange={set("checklist")} /></Field></div></div>
        <div className="actions"><button className="primary">Schedule</button><button type="button" onClick={() => setAdding(false)}>Cancel</button></div></form></Modal>}
      {sel && <Work m={sel} onClose={() => setSel(null)} run={run} setMsg={setMsg} me={me} />}
    </div>
  );
}

function Work({ m, onClose, run, setMsg }) {
  const detail = useData(() => get(`/api/maintenance/${m.id}`), [m.id]);
  const d = detail.data || m;
  const [items, setItems] = useState(null);
  const [f, setF] = useState({ workPerformed: "", remarks: "", partsReplaced: "", nextDue: "" });
  const list = items || (d.checklist || []);
  const toggle = (i) => setItems(list.map((c, j) => (j === i ? { ...c, done: !c.done } : c)));
  const done = () => { onClose(); };
  const completing = m.canComplete && !m.view;
  return (
    <Modal title={`${d.assetTag} · ${d.kind?.toLowerCase()} maintenance`} onClose={onClose} wide>
      <div style={{ display: "flex", gap: 8, marginBottom: 10 }}><Badge value={d.status} /><Badge value={d.priority} /></div>
      <div className="kv"><div><span>Scheduled</span>{fmt(d.startAt)} → {fmt(d.endAt)}</div><div><span>Actual</span>{d.actualStart ? `${fmt(d.actualStart)} → ${fmt(d.actualEnd)}` : "–"}</div><div><span>Engineer</span>{d.assignedEngineer || "–"}</div><div><span>Next due</span>{fmt(d.nextDueDate)}</div></div>
      <h4>Checklist</h4>
      <div className="checklist">{list.length ? list.map((c, i) => <label key={i}><input type="checkbox" checked={!!c.done} disabled={!(d.canEdit || completing)} onChange={() => toggle(i)} />{c.item}</label>) : <Empty>No checklist.</Empty>}</div>
      {(d.canEdit || completing) && <div className="form" style={{ marginTop: 10 }}>
        <div className="full"><Field label="Work performed"><textarea value={f.workPerformed || d.workPerformed || ""} onChange={(e) => setF({ ...f, workPerformed: e.target.value })} /></Field></div>
        <Field label="Parts replaced"><input value={f.partsReplaced || d.partsReplaced || ""} onChange={(e) => setF({ ...f, partsReplaced: e.target.value })} /></Field>
        <Field label="Remarks"><input value={f.remarks || d.remarks || ""} onChange={(e) => setF({ ...f, remarks: e.target.value })} /></Field>
        {completing && <Field label="Next maintenance due" hint="Defaults to the equipment’s interval"><input type="datetime-local" value={f.nextDue} onChange={(e) => setF({ ...f, nextDue: e.target.value })} /></Field>}</div>}
      {!(d.canEdit || completing) && (d.workPerformed || d.remarks) && <p>{d.workPerformed} {d.partsReplaced && <><br /><small className="muted">Parts: {d.partsReplaced}</small></>}</p>}
      <div className="actions">
        {d.canEdit && <button onClick={() => run(() => patch(`/api/maintenance/${m.id}`, { checklist: list.map(({ item, done }) => ({ item, done: !!done })), workPerformed: f.workPerformed || undefined, partsReplaced: f.partsReplaced || undefined, remarks: f.remarks || undefined }), "Progress saved")}>Save progress</button>}
        {completing && <button className="primary" onClick={() => run(() => post(`/api/maintenance/${m.id}/complete`, { checklist: list.map(({ item, done }) => ({ item, done: !!done })), workPerformed: f.workPerformed || d.workPerformed, partsReplaced: f.partsReplaced || undefined, remarks: f.remarks || undefined, nextDueDate: f.nextDue ? toIso(f.nextDue) : undefined }), "Maintenance completed and counters reset").then((r) => r && done())}>Complete maintenance</button>}
        {d.canCancel && <button className="danger" onClick={() => window.confirm("Cancel this maintenance? Counters will not be reset.") && run(() => post(`/api/maintenance/${m.id}/cancel`, {}), "Maintenance cancelled").then(done)}>Cancel</button>}
      </div>
      <h4>Documents</h4><Documents entityType="MAINTENANCE" entityId={m.id} canUpload={d.canEdit || m.canComplete} setMsg={setMsg} />
    </Modal>
  );
}
