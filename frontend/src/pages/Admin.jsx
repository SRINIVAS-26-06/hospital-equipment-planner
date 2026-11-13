import { useState } from "react";
import { get, patch, post } from "../api.js";
import { Badge, Card, Empty, Field, Modal, Msg, Pager, fmt, useAction, useData } from "../components/ui.jsx";

const ROLES = ["ADMIN", "ENGINEER", "STAFF", "VIEWER"];

function Users({ setMsg }) {
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [edit, setEdit] = useState(null);
  const [pw, setPw] = useState(null);
  const depts = useData(() => get("/api/departments", { includeInactive: false }), []);
  const list = useData(() => get("/api/users", { q, page, size: 12 }), [q, page]);
  const run = useAction(setMsg, list.reload);
  const [f, setF] = useState({});
  const open = (u) => { setF(u ? { fullName: u.fullName, email: u.email || "", role: u.role, departmentId: u.departmentId || "", active: u.active } : { username: "", password: "", fullName: "", email: "", role: "STAFF", departmentId: "", active: true }); setEdit(u || {}); };
  const save = async (e) => {
    e.preventDefault();
    const body = { ...f, departmentId: f.departmentId ? Number(f.departmentId) : undefined, email: f.email || undefined };
    const r = await run(() => (edit.id ? patch(`/api/users/${edit.id}`, body) : post("/api/users", body)), edit.id ? "User updated" : "User created");
    if (r) setEdit(null);
  };
  return (
    <>
      <div className="toolbar"><Field label="Search"><input value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} placeholder="Username or name" /></Field><button className="primary btn" onClick={() => open(null)}>+ New user</button></div>
      {!list.data ? <div className="skeleton" style={{ height: 160 }} /> : <div className="table-wrap"><table><thead><tr><th>User</th><th>Role</th><th>Department</th><th>Status</th><th /></tr></thead>
        <tbody>{list.data.items.map((u) => <tr key={u.id}><td><b>{u.username}</b><span className="sub">{u.fullName}{u.email ? ` · ${u.email}` : ""}</span></td><td><Badge value={u.role} /></td><td>{u.departmentName || "–"}</td><td><Badge value={u.active ? "ACTIVE" : "CANCELLED"} /></td>
          <td style={{ whiteSpace: "nowrap" }}><button onClick={() => open(u)}>Edit</button> <button onClick={() => setPw({ id: u.id, username: u.username, password: "" })}>Reset password</button></td></tr>)}</tbody></table></div>}
      <Pager page={list.data} onPage={setPage} />
      {edit && <Modal title={edit.id ? `Edit ${edit.username}` : "New user"} onClose={() => setEdit(null)}><form onSubmit={save}><div className="form">
        {!edit.id && <Field label="Username *"><input required pattern="[A-Za-z0-9._-]{3,40}" value={f.username} onChange={(e) => setF({ ...f, username: e.target.value })} /></Field>}
        {!edit.id && <Field label="Password *" hint="At least 8 characters"><input required minLength={8} type="password" value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} /></Field>}
        <Field label="Full name *"><input required value={f.fullName} onChange={(e) => setF({ ...f, fullName: e.target.value })} /></Field>
        <Field label="Email"><input type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></Field>
        <Field label="Role"><select value={f.role} onChange={(e) => setF({ ...f, role: e.target.value })}>{ROLES.map((r) => <option key={r}>{r}</option>)}</select></Field>
        <Field label="Department"><select value={f.departmentId} onChange={(e) => setF({ ...f, departmentId: e.target.value })}><option value="">None</option>{depts.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select></Field>
        {edit.id && <Field label="Account"><select value={String(f.active)} onChange={(e) => setF({ ...f, active: e.target.value === "true" })}><option value="true">Active</option><option value="false">Deactivated</option></select></Field>}</div>
        <div className="actions"><button className="primary">Save</button><button type="button" onClick={() => setEdit(null)}>Cancel</button></div></form></Modal>}
      {pw && <Modal title={`Reset password · ${pw.username}`} onClose={() => setPw(null)}><form onSubmit={async (e) => { e.preventDefault(); const r = await run(() => post(`/api/users/${pw.id}/reset-password`, { password: pw.password }), "Password reset"); if (r !== null) setPw(null); }}>
        <Field label="New password" hint="At least 8 characters. Share it securely."><input required minLength={8} type="password" value={pw.password} onChange={(e) => setPw({ ...pw, password: e.target.value })} /></Field><div className="actions"><button className="primary">Reset</button></div></form></Modal>}
    </>
  );
}

function Departments({ setMsg }) {
  const list = useData(() => get("/api/departments", { includeInactive: true }), []);
  const run = useAction(setMsg, list.reload);
  const [name, setName] = useState("");
  const [rename, setRename] = useState(null);
  return (
    <>
      <div className="toolbar"><Field label="New department"><input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Cardiology" /></Field>
        <button className="primary btn" disabled={!name.trim()} onClick={() => run(() => post("/api/departments", { name }), "Department added").then(() => setName(""))}>Add</button></div>
      {!list.data ? <div className="skeleton" style={{ height: 120 }} /> : <div className="table-wrap"><table><thead><tr><th>Department</th><th>Equipment</th><th>Status</th><th /></tr></thead>
        <tbody>{list.data.map((d) => <tr key={d.id}><td>{rename?.id === d.id ? <input value={rename.name} onChange={(e) => setRename({ ...rename, name: e.target.value })} /> : <b>{d.name}</b>}</td><td>{d.assetCount}</td><td><Badge value={d.active ? "ACTIVE" : "CANCELLED"} /></td>
          <td style={{ whiteSpace: "nowrap" }}>{rename?.id === d.id ? <><button className="primary" onClick={() => run(() => patch(`/api/departments/${d.id}`, { name: rename.name }), "Renamed").then(() => setRename(null))}>Save</button> <button onClick={() => setRename(null)}>Cancel</button></> :
            <><button onClick={() => setRename({ id: d.id, name: d.name })}>Rename</button> <button onClick={() => run(() => patch(`/api/departments/${d.id}`, { name: d.name, active: !d.active }), d.active ? "Department deactivated" : "Department reactivated")}>{d.active ? "Deactivate" : "Reactivate"}</button></>}</td></tr>)}</tbody></table></div>}
    </>
  );
}

function Audit() {
  const [f, setF] = useState({ username: "", entityType: "", action: "" });
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState(null);
  const list = useData(() => get("/api/audit", { ...f, page, size: 20 }), [JSON.stringify(f), page], 30000);
  const set = (k) => (e) => { setF({ ...f, [k]: e.target.value }); setPage(0); };
  return (
    <>
      <div className="toolbar"><Field label="User"><input value={f.username} onChange={set("username")} placeholder="username" /></Field>
        <Field label="Entity"><select value={f.entityType} onChange={set("entityType")}><option value="">All</option>{["ASSET", "BOOKING", "USAGE", "INGESTION_BATCH", "MAINTENANCE", "CALIBRATION", "WORK_ORDER", "USER", "DEPARTMENT", "DOCUMENT"].map((t) => <option key={t}>{t}</option>)}</select></Field>
        <Field label="Action"><input value={f.action} onChange={set("action")} placeholder="e.g. EQUIPMENT_RETIRED" /></Field></div>
      {!list.data ? <div className="skeleton" style={{ height: 200 }} /> : !list.data.items.length ? <Card title="Audit log"><Empty>No entries.</Empty></Card> : <div className="table-wrap"><table><thead><tr><th>When</th><th>User</th><th>Action</th><th>Entity</th></tr></thead>
        <tbody>{list.data.items.map((a) => <tr key={a.id} className="click" onClick={() => setOpen(a)}><td>{fmt(a.at)}</td><td>{a.username}</td><td><b>{a.action.replaceAll("_", " ").toLowerCase()}</b></td><td>{a.entityType} #{a.entityId}</td></tr>)}</tbody></table></div>}
      <Pager page={list.data} onPage={setPage} />
      {open && <Modal title={open.action} onClose={() => setOpen(null)}><p className="muted">{open.username} · {fmt(open.at)} · {open.entityType} #{open.entityId}</p>
        {open.oldValue && <><h4>Before</h4><pre className="card" style={{ overflow: "auto", fontSize: 12 }}>{JSON.stringify(open.oldValue, null, 2)}</pre></>}
        {open.newValue && <><h4>After</h4><pre className="card" style={{ overflow: "auto", fontSize: 12 }}>{JSON.stringify(open.newValue, null, 2)}</pre></>}</Modal>}
    </>
  );
}

export default function Admin() {
  const [tab, setTab] = useState("users");
  const [msg, setMsg] = useState(null);
  return (
    <div>
      <div className="page-h"><div><h2>Administration</h2><p>Users and roles, departments, and the audit trail of every important change.</p></div></div>
      <Msg msg={msg} onClose={() => setMsg(null)} />
      <div className="tabs">{[["users", "Users"], ["departments", "Departments"], ["audit", "Audit log"]].map(([k, l]) => <button key={k} className={tab === k ? "on" : ""} onClick={() => setTab(k)}>{l}</button>)}</div>
      {tab === "users" && <Users setMsg={setMsg} />}{tab === "departments" && <Departments setMsg={setMsg} />}{tab === "audit" && <Audit />}
    </div>
  );
}
