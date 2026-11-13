import { useState } from "react";
import { get, post } from "../api.js";
import Documents from "../components/Documents.jsx";
import { Badge, Card, Empty, Field, Modal, Msg, Pager, fmt, localInput, toIso, useAction, useData } from "../components/ui.jsx";

export default function Calibration({ ctx }) {
  const { role, me } = ctx;
  const eng = role === "ENGINEER" || role === "ADMIN";
  const [f, setF] = useState({ status: "", mine: role === "ENGINEER" });
  const [page, setPage] = useState(0);
  const [msg, setMsg] = useState(null);
  const [adding, setAdding] = useState(false);
  const [sel, setSel] = useState(null);
  const [form, setForm] = useState({ scheduledFor: localInput(24), assignedEngineer: "" });
  const [res, setRes] = useState({ result: "PASS", certificateNumber: "", remarks: "", nextDueDate: "" });
  const list = useData(() => get("/api/calibrations", { ...f, page, size: 12 }), [JSON.stringify(f), page], 20000);
  const assets = useData(() => (eng ? get("/api/assets", { size: 200 }) : Promise.resolve(null)), []);
  const engineers = useData(() => (role === "ADMIN" ? get("/api/users", { role: "ENGINEER", active: true, size: 100 }) : Promise.resolve(null)), []);
  const run = useAction(setMsg, list.reload);

  return (
    <div>
      <div className="page-h"><div><h2>Calibration</h2><p>Schedule, record pass/fail with certificates, and keep next-due dates current. A failed calibration restricts the equipment until a passing one is recorded.</p></div>
        {eng && <button className="primary" onClick={() => setAdding(true)}>+ Schedule calibration</button>}</div>
      <Msg msg={msg} onClose={() => setMsg(null)} />
      <div className="chips" style={{ marginBottom: 12 }}>
        {["", "SCHEDULED", "COMPLETED", "CANCELLED"].map((s) => <button key={s} className={`chip ${f.status === s ? "on" : ""}`} onClick={() => { setF({ ...f, status: s }); setPage(0); }}>{s ? s.toLowerCase() : "all"}</button>)}
        {role === "ENGINEER" && <button className={`chip ${f.mine ? "on" : ""}`} onClick={() => { setF({ ...f, mine: !f.mine }); setPage(0); }}>Assigned to me</button>}
      </div>
      {!list.data ? <div className="skeleton" style={{ height: 200 }} /> : !list.data.items.length ? <Card title="Calibration"><Empty>No calibration records match.</Empty></Card> : (
        <div className="table-wrap"><table><thead><tr><th>Equipment</th><th>Scheduled</th><th>Performed</th><th>Result</th><th>Engineer</th><th /></tr></thead>
          <tbody>{list.data.items.map((c) => <tr key={c.id}>
            <td><b>{c.assetTag}</b><span className="sub">{c.assetName}</span></td><td>{fmt(c.scheduledFor)}</td><td>{fmt(c.performedAt)}{c.certificateNumber && <span className="sub">Certificate {c.certificateNumber}</span>}</td>
            <td>{c.result ? <Badge value={c.result} /> : <Badge value={c.status} />}{c.nextDueDate && <span className="sub">next {fmt(c.nextDueDate)}</span>}</td><td>{c.assignedEngineer || <span className="muted">unassigned</span>}</td>
            <td style={{ whiteSpace: "nowrap" }}>{c.canClaim && <button onClick={() => run(() => post(`/api/calibrations/${c.id}/assign`, { engineer: me.username }), "Assigned to you")}>Claim</button>}{" "}
              {c.canComplete && <button className="primary" onClick={() => setSel(c)}>Record result</button>}{" "}
              {c.canCancel && <button onClick={() => window.confirm("Cancel this calibration?") && run(() => post(`/api/calibrations/${c.id}/cancel`, {}), "Calibration cancelled")}>Cancel</button>}{" "}
              {(c.status === "COMPLETED" || c.canComplete) && <button onClick={() => setSel({ ...c, view: true })}>Certificates</button>}</td></tr>)}</tbody></table></div>)}
      <Pager page={list.data} onPage={setPage} />

      {adding && <Modal title="Schedule calibration" onClose={() => setAdding(false)}>
        <form onSubmit={async (e) => { e.preventDefault(); const r = await run(() => post("/api/calibrations", { assetId: Number(form.assetId), scheduledFor: toIso(form.scheduledFor), assignedEngineer: role === "ENGINEER" ? me.username : form.assignedEngineer || undefined }), "Calibration scheduled"); if (r) setAdding(false); }}>
          <div className="form"><Field label="Equipment"><select required value={form.assetId || ""} onChange={(e) => setForm({ ...form, assetId: e.target.value })}><option value="">Select…</option>{(assets.data?.items || []).map((a) => <option key={a.id} value={a.id}>{a.assetTag} · {a.name}</option>)}</select></Field>
            <Field label="Date and time"><input type="datetime-local" required value={form.scheduledFor} onChange={(e) => setForm({ ...form, scheduledFor: e.target.value })} /></Field>
            {role === "ADMIN" && <Field label="Engineer"><select value={form.assignedEngineer} onChange={(e) => setForm({ ...form, assignedEngineer: e.target.value })}><option value="">Unassigned</option>{(engineers.data?.items || []).map((u) => <option key={u.id} value={u.username}>{u.fullName || u.username}</option>)}</select></Field>}</div>
          <div className="actions"><button className="primary">Schedule</button><button type="button" onClick={() => setAdding(false)}>Cancel</button></div></form></Modal>}

      {sel && <Modal title={`${sel.assetTag} · calibration`} onClose={() => setSel(null)}>
        {sel.canComplete && !sel.view && <>
          <div className="form"><Field label="Result"><select value={res.result} onChange={(e) => setRes({ ...res, result: e.target.value })}><option>PASS</option><option>FAIL</option></select></Field>
            {res.result === "PASS" && <Field label="Certificate number *"><input value={res.certificateNumber} onChange={(e) => setRes({ ...res, certificateNumber: e.target.value })} /></Field>}
            {res.result === "PASS" && <Field label="Next due" hint="Defaults to the equipment’s interval"><input type="datetime-local" value={res.nextDueDate} onChange={(e) => setRes({ ...res, nextDueDate: e.target.value })} /></Field>}
            <div className="full"><Field label={res.result === "FAIL" ? "Remarks * (why it failed)" : "Remarks"}><textarea value={res.remarks} onChange={(e) => setRes({ ...res, remarks: e.target.value })} /></Field></div></div>
          {res.result === "FAIL" && <div className="warnbox">Recording a failure restricts this equipment immediately and cancels its active and upcoming bookings.</div>}
          <div className="actions"><button className={res.result === "FAIL" ? "danger" : "primary"} onClick={() => run(() => post(`/api/calibrations/${sel.id}/complete`, { result: res.result, certificateNumber: res.certificateNumber || undefined, remarks: res.remarks || undefined, nextDueDate: res.nextDueDate ? toIso(res.nextDueDate) : undefined }), "Calibration recorded").then((r) => r && setSel(null))}>Record {res.result.toLowerCase()}</button></div>
        </>}
        <h4>Certificates and documents</h4><Documents entityType="CALIBRATION" entityId={sel.id} canUpload={eng && (role === "ADMIN" || sel.assignedEngineer === me.username)} setMsg={setMsg} />
      </Modal>}
    </div>
  );
}
