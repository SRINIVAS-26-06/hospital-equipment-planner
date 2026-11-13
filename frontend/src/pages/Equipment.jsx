import { useEffect, useState } from "react";
import { get, post, put } from "../api.js";
import Documents from "../components/Documents.jsx";
import { Badge, Empty, Field, Modal, Msg, Pager, Card, fmt, fmtDate, useAction, useData } from "../components/ui.jsx";

const STATES = ["AVAILABLE", "BOOKED", "IN_USE", "UNDER_MAINTENANCE", "OVERDUE_MAINTENANCE", "UNDER_REPAIR", "CALIBRATION_FAILED", "OUT_OF_SERVICE"];
const BLANK = { assetTag: "", name: "", category: "", departmentId: "", manufacturer: "", model: "", serialNumber: "", purchaseDate: "", purchaseCost: "",
  supplier: "", warrantyStart: "", warrantyEnd: "", contractType: "NONE", contractVendor: "", contractStart: "", contractEnd: "", location: "", notes: "",
  maintenanceIntervalDays: 90, maintenanceThresholdHours: 500, calibrationRequired: false, calibrationIntervalDays: "", aliases: "" };

const clean = (f) => {
  const o = {};
  Object.entries(f).forEach(([k, v]) => { if (v !== "" && v !== null) o[k] = v; });
  ["maintenanceIntervalDays", "calibrationIntervalDays"].forEach((k) => { if (o[k] !== undefined) o[k] = Number(o[k]); });
  ["maintenanceThresholdHours", "purchaseCost"].forEach((k) => { if (o[k] !== undefined) o[k] = Number(o[k]); });
  if (o.departmentId) o.departmentId = Number(o.departmentId);
  o.aliases = f.aliases ? f.aliases.split(",").map((s) => s.trim()).filter(Boolean) : [];
  return o;
};

function EquipmentForm({ initial, departments, onSaved, setMsg, onClose }) {
  const editing = !!initial?.id;
  const [f, setF] = useState(initial ? { ...BLANK, ...Object.fromEntries(Object.entries(initial).map(([k, v]) => [k, v ?? ""])), aliases: (initial.aliases || []).join(", ") } : BLANK);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.type === "checkbox" ? e.target.checked : e.target.value });
  const run = useAction(setMsg);
  const submit = async (e) => {
    e.preventDefault();
    const r = await run(() => (editing ? put(`/api/assets/${initial.id}`, clean(f)) : post("/api/assets", clean(f))), editing ? "Equipment updated" : "Equipment added");
    if (r) onSaved(r);
  };
  return (
    <form onSubmit={submit}>
      <div className="form">
        <Field label="Asset tag *" hint="e.g. VENT-0007; formats like 'vent 7' are normalised"><input required disabled={editing} value={f.assetTag} onChange={set("assetTag")} /></Field>
        <Field label="Name *"><input required value={f.name} onChange={set("name")} /></Field>
        <Field label="Category *"><input required value={f.category} onChange={set("category")} placeholder="Ventilator, Imaging…" /></Field>
        {!editing && <Field label="Department *"><select required value={f.departmentId} onChange={set("departmentId")}><option value="">Select…</option>{departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select></Field>}
        <Field label="Location"><input value={f.location} onChange={set("location")} placeholder="Room / bay (no patient details)" /></Field>
        <Field label="Manufacturer"><input value={f.manufacturer} onChange={set("manufacturer")} /></Field>
        <Field label="Model"><input value={f.model} onChange={set("model")} /></Field>
        <Field label="Serial number"><input value={f.serialNumber} onChange={set("serialNumber")} /></Field>
        <Field label="Purchase date"><input type="date" value={f.purchaseDate} onChange={set("purchaseDate")} /></Field>
        <Field label="Purchase cost"><input type="number" min="0" step="0.01" value={f.purchaseCost} onChange={set("purchaseCost")} /></Field>
        <Field label="Supplier"><input value={f.supplier} onChange={set("supplier")} /></Field>
        <Field label="Warranty start"><input type="date" value={f.warrantyStart} onChange={set("warrantyStart")} /></Field>
        <Field label="Warranty end"><input type="date" value={f.warrantyEnd} onChange={set("warrantyEnd")} /></Field>
        <Field label="Service contract"><select value={f.contractType} onChange={set("contractType")}><option value="NONE">None</option><option value="AMC">AMC</option><option value="CMC">CMC</option></select></Field>
        {f.contractType !== "NONE" && <>
          <Field label="Contract vendor"><input value={f.contractVendor} onChange={set("contractVendor")} /></Field>
          <Field label="Contract start"><input type="date" value={f.contractStart} onChange={set("contractStart")} /></Field>
          <Field label="Contract end"><input type="date" value={f.contractEnd} onChange={set("contractEnd")} /></Field>
        </>}
        <Field label="Maintenance interval (days)"><input type="number" min="1" value={f.maintenanceIntervalDays} onChange={set("maintenanceIntervalDays")} /></Field>
        <Field label="Maintenance threshold (usage hours)"><input type="number" min="1" value={f.maintenanceThresholdHours} onChange={set("maintenanceThresholdHours")} /></Field>
        <Field label="Calibration required"><select value={f.calibrationRequired ? "yes" : "no"} onChange={(e) => setF({ ...f, calibrationRequired: e.target.value === "yes" })}><option value="no">No</option><option value="yes">Yes</option></select></Field>
        {f.calibrationRequired && <Field label="Calibration interval (days)"><input type="number" min="1" value={f.calibrationIntervalDays} onChange={set("calibrationIntervalDays")} /></Field>}
        {!editing && <Field label="Alternative IDs" hint="Comma separated legacy identifiers"><input value={f.aliases} onChange={set("aliases")} /></Field>}
        <div className="full"><Field label="Notes"><textarea value={f.notes} onChange={set("notes")} /></Field></div>
      </div>
      <div className="actions"><button className="primary">{editing ? "Save changes" : "Add equipment"}</button><button type="button" onClick={onClose}>Cancel</button></div>
    </form>
  );
}

function Detail({ id, ctx, departments, onClose, onChanged }) {
  const { role, go } = ctx;
  const [tab, setTab] = useState("overview");
  const [msg, setMsg] = useState(null);
  const [edit, setEdit] = useState(false);
  const [panel, setPanel] = useState(null);
  const [form, setForm] = useState({});
  const asset = useData(() => get(`/api/assets/${id}`), [id], 20000);
  const history = useData(() => (tab === "history" ? get(`/api/assets/${id}/history`) : Promise.resolve(null)), [tab, id]);
  const maint = useData(() => (tab === "maintenance" ? get("/api/maintenance", { assetId: id, size: 20 }) : Promise.resolve(null)), [tab, id]);
  const cal = useData(() => (tab === "calibration" ? get("/api/calibrations", { assetId: id, size: 20 }) : Promise.resolve(null)), [tab, id]);
  const depts = useData(() => (tab === "departments" ? get(`/api/assets/${id}/department-history`) : Promise.resolve(null)), [tab, id]);
  const reload = () => { asset.reload(); onChanged(); };
  const run = useAction(setMsg, reload);
  const a = asset.data;
  if (!a) return <Modal title="Equipment" onClose={onClose}><div className="skeleton" /><div className="skeleton" /></Modal>;
  const admin = role === "ADMIN";
  const retired = a.lifecycleStatus === "RETIRED";
  const tabs = [["overview", "Overview"], ["history", "History"], ["maintenance", "Maintenance"], ["calibration", "Calibration"], ["departments", "Departments"], ["documents", "Documents"]];

  if (edit) return <Modal title={`Edit ${a.assetTag}`} onClose={() => setEdit(false)} wide><EquipmentForm initial={a} departments={departments} setMsg={setMsg} onClose={() => setEdit(false)} onSaved={() => { setEdit(false); reload(); }} /></Modal>;

  return (
    <Modal title={`${a.assetTag} · ${a.name}`} onClose={onClose} wide>
      <Msg msg={msg} onClose={() => setMsg(null)} />
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 10 }}><Badge value={a.availability} /><Badge value={a.lifecycleStatus} />{a.calibrationRequired && <span className="badge">calibration tracked</span>}</div>
      <div className="tabs">{tabs.map(([k, l]) => <button key={k} className={tab === k ? "on" : ""} onClick={() => setTab(k)}>{l}</button>)}</div>

      {tab === "overview" && <>
        <div className="kv">
          {[["Department", a.departmentName], ["Category", a.category], ["Location", a.location], ["Manufacturer", a.manufacturer], ["Model", a.model], ["Serial number", a.serialNumber],
            ["Purchased", fmtDate(a.purchaseDate)], ["Supplier", a.supplier], ["Warranty", a.warrantyEnd ? `${a.warrantyActive ? "active until" : "ended"} ${fmtDate(a.warrantyEnd)}` : "–"],
            ["Service contract", a.contractType === "NONE" ? "None" : `${a.contractType} · ${a.contractVendor || ""} until ${fmtDate(a.contractEnd)}`],
            ["Last maintenance", fmt(a.lastMaintenanceAt)], ["Next maintenance due", fmt(a.nextMaintenanceDue)],
            ["Usage since maintenance", `${Number(a.usageHoursSinceMaintenance).toFixed(1)} / ${a.maintenanceThresholdHours} h`],
            ["Last calibration", fmt(a.lastCalibrationAt)], ["Next calibration due", a.calibrationRequired ? fmt(a.nextCalibrationDue) : "Not required"],
            ...(retired ? [["Retired", `${fmt(a.retiredAt)} · ${a.retiredReason}`]] : [])].map(([k, v]) => <div key={k}><span>{k}</span>{v || "–"}</div>)}
        </div>
        {a.notes && <p className="muted">{a.notes}</p>}
        {a.hardBlocked && !retired && <div className="warnbox">This equipment cannot be booked or used right now: {a.availability.replaceAll("_", " ").toLowerCase()}.</div>}
        {!retired && <div className="actions">
          {(role === "STAFF" || admin) && <button className="primary" disabled={a.hardBlocked} onClick={() => go("bookings", a.id)}>Book</button>}
          {role !== "VIEWER" && <button onClick={() => go("workorders", `new-${a.id}`)}>Report breakdown</button>}
          {admin && <button onClick={() => setEdit(true)}>Edit</button>}
          {admin && <button onClick={() => setPanel(panel === "transfer" ? null : "transfer")}>Transfer</button>}
          {admin && <button onClick={() => setPanel(panel === "status" ? null : "status")}>{a.lifecycleStatus === "ACTIVE" ? "Take out of service" : "Return to service"}</button>}
          {admin && <button className="danger" onClick={() => setPanel(panel === "retire" ? null : "retire")}>Retire</button>}
        </div>}
        {panel === "transfer" && <div className="card" style={{ marginTop: 12 }}><div className="form">
          <Field label="New department"><select value={form.to || ""} onChange={(e) => setForm({ ...form, to: e.target.value })}><option value="">Select…</option>{departments.filter((d) => d.id !== a.departmentId).map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select></Field>
          <Field label="Reason"><input value={form.reason || ""} onChange={(e) => setForm({ ...form, reason: e.target.value })} /></Field></div>
          <p className="muted" style={{ fontSize: 13 }}>The transfer takes effect now. Past usage stays with the previous department; future bookings are cancelled and the people affected are notified.</p>
          <button className="primary" disabled={!form.to} onClick={() => run(() => post(`/api/assets/${id}/transfer`, { toDepartmentId: Number(form.to), reason: form.reason }), "Equipment transferred").then(() => setPanel(null))}>Confirm transfer</button></div>}
        {panel === "status" && <div className="card" style={{ marginTop: 12 }}>
          <Field label={a.lifecycleStatus === "ACTIVE" ? "Reason (required)" : "Note"}><input value={form.reason || ""} onChange={(e) => setForm({ ...form, reason: e.target.value })} /></Field>
          <p className="muted" style={{ fontSize: 13 }}>{a.lifecycleStatus === "ACTIVE" ? "Active and upcoming bookings are cancelled and owners notified." : "The equipment becomes bookable again unless another restriction applies."}</p>
          <button className="primary" onClick={() => run(() => post(`/api/assets/${id}/lifecycle`, { status: a.lifecycleStatus === "ACTIVE" ? "OUT_OF_SERVICE" : "ACTIVE", reason: form.reason }), "Status updated").then(() => setPanel(null))}>Confirm</button></div>}
        {panel === "retire" && <div className="card" style={{ marginTop: 12 }}>
          <div className="warnbox">Retiring keeps all history but cannot be undone. Bookings and scheduled maintenance are cancelled.</div>
          <Field label="Reason (required)"><input value={form.reason || ""} onChange={(e) => setForm({ ...form, reason: e.target.value })} /></Field>
          <button className="danger" disabled={!form.reason} onClick={() => run(() => post(`/api/assets/${id}/retire`, { reason: form.reason }), "Equipment retired").then(() => setPanel(null))} style={{ marginTop: 10 }}>Retire equipment</button></div>}
      </>}

      {tab === "history" && (history.data ? (history.data.length ? <ul className="timeline">{history.data.map((h, i) => <li key={i}><b>{h.title}</b> <span className="badge">{h.type.toLowerCase()}</span><small>{fmt(h.at)}{h.detail ? ` · ${h.detail}` : ""}{h.actor ? ` · ${h.actor}` : ""}</small></li>)}</ul> : <Empty>No history yet.</Empty>) : <div className="skeleton" />)}
      {tab === "maintenance" && (maint.data ? (maint.data.items.length ? <div className="table-wrap"><table><thead><tr><th>Scheduled</th><th>Kind</th><th>Priority</th><th>Engineer</th><th>Status</th></tr></thead><tbody>{maint.data.items.map((m) => <tr key={m.id}><td>{fmt(m.startAt)}</td><td>{m.kind}</td><td><Badge value={m.priority} /></td><td>{m.assignedEngineer || "–"}</td><td><Badge value={m.status} /></td></tr>)}</tbody></table></div> : <Empty>No maintenance records.</Empty>) : <div className="skeleton" />)}
      {tab === "calibration" && (cal.data ? (cal.data.items.length ? <div className="table-wrap"><table><thead><tr><th>Scheduled</th><th>Performed</th><th>Result</th><th>Certificate</th><th>Next due</th></tr></thead><tbody>{cal.data.items.map((c) => <tr key={c.id}><td>{fmt(c.scheduledFor)}</td><td>{fmt(c.performedAt)}</td><td>{c.result ? <Badge value={c.result} /> : <Badge value={c.status} />}</td><td>{c.certificateNumber || "–"}</td><td>{fmt(c.nextDueDate)}</td></tr>)}</tbody></table></div> : <Empty>No calibration records.</Empty>) : <div className="skeleton" />)}
      {tab === "departments" && (depts.data ? <ul className="timeline">{depts.data.map((d) => <li key={d.id}><b>{d.departmentName}</b><small>{d.effectiveFrom ? fmt(d.effectiveFrom) : "from the start of records"} → {d.effectiveTo ? fmt(d.effectiveTo) : "present"}</small></li>)}</ul> : <div className="skeleton" />)}
      {tab === "documents" && <Documents entityType="ASSET" entityId={id} canUpload={admin} setMsg={setMsg} />}
    </Modal>
  );
}

export default function Equipment({ ctx }) {
  const { role, routeId, go } = ctx;
  const [f, setF] = useState({ q: "", departmentId: "", category: "", availability: "", lifecycle: "", sort: "assetTag", desc: false });
  const [page, setPage] = useState(0);
  const [adding, setAdding] = useState(false);
  const [msg, setMsg] = useState(null);
  const [q, setQ] = useState("");
  useEffect(() => { const t = setTimeout(() => { setF((x) => ({ ...x, q })); setPage(0); }, 300); return () => clearTimeout(t); }, [q]);
  const departments = useData(() => get("/api/departments"), []);
  const categories = useData(() => get("/api/assets/categories"), []);
  const list = useData(() => get("/api/assets", { ...f, page, size: 15 }), [JSON.stringify(f), page], 20000);
  const setFilter = (k) => (e) => { setF({ ...f, [k]: e.target.value }); setPage(0); };
  const open = routeId && /^\d+$/.test(routeId) ? Number(routeId) : null;

  return (
    <div>
      <div className="page-h"><div><h2>Equipment</h2><p>Search the fleet, check live availability and manage each asset’s lifecycle.</p></div>
        {role === "ADMIN" && <button className="primary" onClick={() => setAdding(true)}>+ Add equipment</button>}</div>
      <Msg msg={msg} onClose={() => setMsg(null)} />
      <div className="toolbar">
        <Field label="Search"><input className="grow" placeholder="Tag, name, serial, model, location…" value={q} onChange={(e) => setQ(e.target.value)} /></Field>
        <Field label="Department"><select value={f.departmentId} onChange={setFilter("departmentId")}><option value="">All</option>{departments.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select></Field>
        <Field label="Category"><select value={f.category} onChange={setFilter("category")}><option value="">All</option>{categories.data?.map((c) => <option key={c}>{c}</option>)}</select></Field>
        <Field label="Availability"><select value={f.availability} onChange={setFilter("availability")}><option value="">Any</option>{STATES.map((s) => <option key={s} value={s}>{s.replaceAll("_", " ").toLowerCase()}</option>)}</select></Field>
        <Field label="Lifecycle"><select value={f.lifecycle} onChange={setFilter("lifecycle")}><option value="">Active &amp; out of service</option><option value="ACTIVE">Active</option><option value="OUT_OF_SERVICE">Out of service</option><option value="RETIRED">Retired</option></select></Field>
        <Field label="Sort"><select value={f.sort + (f.desc ? ":d" : "")} onChange={(e) => { const [s, d] = e.target.value.split(":"); setF({ ...f, sort: s, desc: d === "d" }); }}>
          <option value="assetTag">Tag</option><option value="name">Name</option><option value="department">Department</option><option value="availability">Availability</option>
          <option value="nextMaintenanceDue">Maintenance due (soonest)</option><option value="nextCalibrationDue">Calibration due (soonest)</option><option value="warrantyEnd">Warranty ending</option></select></Field>
      </div>
      {!list.data ? <div className="skeleton" style={{ height: 240 }} /> : !list.data.items.length ? <Card title="No equipment found"><Empty>Try clearing a filter.</Empty></Card> : (
        <div className="table-wrap"><table>
          <thead><tr><th>Equipment</th><th>Department</th><th>Availability</th><th>Lifecycle</th><th>Next maintenance</th><th>Calibration</th><th>Warranty</th></tr></thead>
          <tbody>{list.data.items.map((a) => (
            <tr key={a.id} className="click" onClick={() => go("equipment", a.id)}>
              <td><b>{a.assetTag}</b><span className="sub">{a.name}{a.location ? ` · ${a.location}` : ""}</span></td>
              <td>{a.departmentName}<span className="sub">{a.category}</span></td>
              <td><Badge value={a.availability} /></td><td><Badge value={a.lifecycleStatus} /></td>
              <td>{fmtDate(a.nextMaintenanceDue)}<span className="sub">{Number(a.usageHoursSinceMaintenance).toFixed(0)}/{a.maintenanceThresholdHours} h</span></td>
              <td>{a.calibrationRequired ? (a.calibrationFailed ? <Badge value="FAIL" /> : fmtDate(a.nextCalibrationDue)) : <span className="muted">n/a</span>}</td>
              <td>{a.warrantyEnd ? fmtDate(a.warrantyEnd) : "–"}</td>
            </tr>))}</tbody></table></div>
      )}
      <Pager page={list.data} onPage={setPage} />
      {adding && <Modal title="Add equipment" onClose={() => setAdding(false)} wide><EquipmentForm departments={departments.data || []} setMsg={setMsg} onClose={() => setAdding(false)} onSaved={(r) => { setAdding(false); list.reload(); go("equipment", r.id); }} /></Modal>}
      {open && <Detail id={open} ctx={ctx} departments={departments.data || []} onClose={() => go("equipment")} onChanged={list.reload} />}
    </div>
  );
}
