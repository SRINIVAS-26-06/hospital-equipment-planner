import { useState } from "react";
import { get, post, upload } from "../api.js";
import { Badge, Card, Empty, Field, Modal, Msg, Pager, fmt, num, useAction, useData } from "../components/ui.jsx";

function Result({ r }) {
  return (
    <Card title={`Upload result · batch #${r.batchId}`}>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 8 }}>
        <Badge value={r.status} /><span className="badge b-completed">{r.accepted} accepted</span><span className={`badge ${r.rejectedCount ? "b-critical" : ""}`}>{r.rejectedCount} rejected</span><span className="badge">{r.totalRows} rows</span>
      </div>
      {r.duplicateFile && <div className="warnbox">This exact file was uploaded before. Rows already recorded were rejected as duplicates, so nothing was counted twice.</div>}
      {r.ignoredColumns?.length > 0 && <p className="muted" style={{ fontSize: 13 }}>Ignored columns (never read or stored): {r.ignoredColumns.join(", ")}</p>}
      {r.rejected.length > 0 && <div className="table-wrap"><table><thead><tr><th>Row</th><th>Asset</th><th>Start</th><th>End</th><th>Reason</th></tr></thead>
        <tbody>{r.rejected.map((x) => <tr key={x.rowNumber}><td>{x.rowNumber}</td><td>{x.assetRef}</td><td>{x.start}</td><td>{x.end}</td><td><Badge value={x.reason} /></td></tr>)}</tbody></table></div>}
      {r.rejectedCount > r.rejected.length && <p className="muted">Showing the first {r.rejected.length}; open the batch for all rows.</p>}
    </Card>
  );
}

export default function Usage({ ctx }) {
  const { role } = ctx;
  const canRecord = role === "STAFF" || role === "ADMIN";
  const [tab, setTab] = useState("sessions");
  const [msg, setMsg] = useState(null);
  const [assetId, setAssetId] = useState("");
  const [page, setPage] = useState(0);
  const [mine, setMine] = useState(role === "STAFF");
  const [result, setResult] = useState(null);
  const [batch, setBatch] = useState(null);
  const assets = useData(() => get("/api/assets", { size: 200 }), []);
  const active = useData(() => get("/api/usage", { activeOnly: true, mine: role === "STAFF", size: 20 }), [], 15000);
  const hist = useData(() => get("/api/usage", { mine, page, size: 12 }), [mine, page], 20000);
  const batches = useData(() => (tab === "batches" ? get("/api/usage/batches", { size: 20 }) : Promise.resolve(null)), [tab]);
  const reload = () => { active.reload(); hist.reload(); batches.reload(); };
  const run = useAction(setMsg, reload);
  const usable = (assets.data?.items || []).filter((a) => !a.hardBlocked && a.availability !== "IN_USE");

  const send = async (e) => {
    e.preventDefault();
    const file = e.target.file.files[0];
    if (!file) return;
    const fd = new FormData();
    fd.append("file", file);
    fd.append("source", e.target.source.value || "csv-upload");
    const r = await run(() => upload("/api/usage/upload", fd), "Upload processed");
    if (r) setResult(r);
  };

  return (
    <div>
      <div className="page-h"><div><h2>Usage</h2><p>Start and end sessions, upload logs, and review who used what, for how long.</p></div></div>
      <Msg msg={msg} onClose={() => setMsg(null)} />
      <div className="tabs">{[["sessions", "Sessions"], ...(canRecord ? [["upload", "CSV upload"]] : []), ["batches", "Upload batches"]].map(([k, l]) => <button key={k} className={tab === k ? "on" : ""} onClick={() => setTab(k)}>{l}</button>)}</div>

      {tab === "sessions" && <>
        {canRecord && <Card title="Start a session"><div className="toolbar" style={{ marginBottom: 0 }}>
          <Field label="Equipment"><select value={assetId} onChange={(e) => setAssetId(e.target.value)}><option value="">Select equipment…</option>{usable.map((a) => <option key={a.id} value={a.id}>{a.assetTag} · {a.name}</option>)}</select></Field>
          <button className="primary btn" disabled={!assetId} onClick={() => run(() => post("/api/usage/start", { assetId: Number(assetId) }), "Session started").then(() => setAssetId(""))}>Start session</button></div></Card>}
        <div style={{ height: 14 }} />
        <Card title="In progress">{!active.data?.items.length ? <Empty>No sessions are running.</Empty> : active.data.items.map((u) => (
          <div className="list-row" key={u.id}><span><b>{u.assetTag}</b><small>{u.assetName} · {u.usedBy} · since {fmt(u.startAt)} · {num(u.durationHours, 2)} h so far</small></span>
            {u.canEnd && <button className="primary" onClick={() => run(() => post(`/api/usage/${u.id}/end`, {}), "Session ended")}>End session</button>}</div>))}</Card>
        <div className="chips" style={{ margin: "16px 0 10px" }}>{role !== "ENGINEER" && <button className={`chip ${mine ? "on" : ""}`} onClick={() => { setMine(!mine); setPage(0); }}>Only mine</button>}</div>
        {!hist.data ? <div className="skeleton" style={{ height: 160 }} /> : !hist.data.items.length ? <Card title="History"><Empty>No usage recorded yet.</Empty></Card> : (
          <div className="table-wrap"><table><thead><tr><th>Equipment</th><th>Department then</th><th>User</th><th>Start</th><th>Duration</th><th>Source</th></tr></thead>
            <tbody>{hist.data.items.map((u) => <tr key={u.id}><td><b>{u.assetTag}</b><span className="sub">{u.assetName}</span></td><td>{u.departmentName || "–"}</td><td>{u.usedBy || "–"}</td><td>{fmt(u.startAt)}</td><td>{u.endAt ? `${num(u.durationHours, 2)} h` : <Badge value="IN_PROGRESS" />}</td><td>{u.source}{u.batchId ? <span className="sub">batch #{u.batchId}</span> : null}</td></tr>)}</tbody></table></div>)}
        <Pager page={hist.data} onPage={setPage} />
      </>}

      {tab === "upload" && <>
        <Card title="Upload usage CSV">
          <form onSubmit={send}>
            <div className="form"><Field label="CSV file" hint="Columns: asset id, start time, end time (any order or header style, e.g. ‘vent 1’ or VENT-0001)"><input type="file" name="file" accept=".csv,text/csv" required /></Field>
              <Field label="Source label"><input name="source" placeholder="e.g. bedside-export" /></Field></div>
            <div className="warnbox">Only the asset, start and end columns are read. Any other column (such as patient names or MRNs) is ignored and never stored. Each row is validated on its own: valid rows are kept even if others are rejected.</div>
            <div className="actions"><button className="primary">Upload and validate</button></div>
          </form>
        </Card>
        {result && <div style={{ marginTop: 14 }}><Result r={result} /></div>}
      </>}

      {tab === "batches" && (!batches.data ? <div className="skeleton" style={{ height: 160 }} /> : !batches.data.items.length ? <Card title="Batches"><Empty>No uploads yet.</Empty></Card> : (
        <div className="table-wrap"><table><thead><tr><th>Batch</th><th>Uploaded</th><th>By</th><th>Source</th><th>Rows</th><th>Status</th></tr></thead>
          <tbody>{batches.data.items.map((b) => <tr key={b.id} className="click" onClick={() => get(`/api/usage/batches/${b.id}`, { size: 200 }).then(setBatch).catch((e) => setMsg({ type: "err", text: e.message }))}>
            <td>#{b.id}<span className="sub">{b.filename || "JSON batch"}</span></td><td>{fmt(b.uploadedAt)}</td><td>{b.uploadedBy}</td><td>{b.source}</td>
            <td>{b.acceptedRows} ok · {b.rejectedRows} rejected<span className="sub">{b.totalRows} total</span></td><td><Badge value={b.status} /></td></tr>)}</tbody></table></div>))}

      {batch && <Modal title={`Batch #${batch.batch.id}`} onClose={() => setBatch(null)} wide>
        <p className="muted">{batch.batch.filename || "JSON batch"} · {batch.batch.uploadedBy} · {fmt(batch.batch.uploadedAt)} · ingested at row level with an outcome and reason.</p>
        <div className="table-wrap"><table><thead><tr><th>Row</th><th>Asset</th><th>Start</th><th>End</th><th>Outcome</th><th>Reason</th></tr></thead>
          <tbody>{batch.rows.items.map((r) => <tr key={r.rowNumber}><td>{r.rowNumber}</td><td>{r.assetRef}</td><td>{r.startText}</td><td>{r.endText}</td><td><Badge value={r.outcome === "ACCEPTED" ? "COMPLETED" : "CRITICAL"} /></td><td>{r.reason ? <Badge value={r.reason} /> : "–"}</td></tr>)}</tbody></table></div>
        <Pager page={batch.rows} onPage={() => {}} />
      </Modal>}
    </div>
  );
}
