import { useState } from "react";
import { download, get, upload } from "../api.js";
import { Empty, fmt, useAction, useData } from "./ui.jsx";

/** Certificates, service reports and attachments for one record. Patient information must never be uploaded. */
export default function Documents({ entityType, entityId, canUpload, setMsg }) {
  const { data, reload } = useData(() => get("/api/documents", { entityType, entityId }), [entityType, entityId]);
  const [kind, setKind] = useState("ATTACHMENT");
  const run = useAction(setMsg, reload);
  const send = (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const fd = new FormData();
    fd.append("file", file);
    fd.append("entityType", entityType);
    fd.append("entityId", entityId);
    fd.append("kind", kind);
    run(() => upload("/api/documents", fd), "Document uploaded");
    e.target.value = "";
  };
  return (
    <div>
      {!data?.length ? <Empty>No documents attached.</Empty> : data.map((d) => (
        <div className="list-row" key={d.id}>
          <span><b>{d.filename}</b><small>{d.docKind.replaceAll("_", " ").toLowerCase()} · {(d.sizeBytes / 1024).toFixed(0)} KB · {d.uploadedBy} · {fmt(d.uploadedAt)}</small></span>
          <button onClick={() => download(`/api/documents/${d.id}/download`, d.filename).catch(() => setMsg({ type: "err", text: "Download failed" }))}>Download</button>
        </div>
      ))}
      {canUpload && (
        <div className="toolbar" style={{ marginTop: 12 }}>
          <label className="field"><span>Type</span>
            <select value={kind} onChange={(e) => setKind(e.target.value)}>
              {["CERTIFICATE", "SERVICE_REPORT", "INVOICE", "MANUAL", "ATTACHMENT"].map((k) => <option key={k} value={k}>{k.replaceAll("_", " ").toLowerCase()}</option>)}
            </select></label>
          <label className="field grow"><span>File (PDF, PNG, JPEG, TXT or CSV, max 5 MB)</span><input type="file" accept=".pdf,.png,.jpg,.jpeg,.txt,.csv" onChange={send} /></label>
        </div>
      )}
      {canUpload && <div className="warnbox">Do not upload anything that contains patient names or medical record numbers.</div>}
    </div>
  );
}
