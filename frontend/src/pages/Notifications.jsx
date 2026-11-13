import { useState } from "react";
import { get, post } from "../api.js";
import { Badge, Card, Empty, Msg, Pager, fmt, useAction, useData } from "../components/ui.jsx";

const ROUTE = { ASSET: "equipment", MAINTENANCE: "maintenance", CALIBRATION: "calibration", WORK_ORDER: "workorders", BOOKING: "bookings" };

export default function Notifications({ ctx }) {
  const { role, go, refreshUnread } = ctx;
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [page, setPage] = useState(0);
  const [msg, setMsg] = useState(null);
  const list = useData(() => get("/api/notifications", { unreadOnly, page, size: 15 }), [unreadOnly, page], 15000);
  const after = () => { list.reload(); refreshUnread(); };
  const run = useAction(setMsg, after);
  const open = async (n) => {
    if (!n.isRead) await post(`/api/notifications/${n.id}/read`, {}).catch(() => {});
    after();
    const r = ROUTE[n.entityType];
    if (r) go(r, n.entityType === "ASSET" ? n.entityId : undefined);
  };
  return (
    <div>
      <div className="page-h"><div><h2>Notifications</h2><p>Maintenance, calibration, breakdowns, work orders, warranty expiry and booking updates. This list refreshes automatically.</p></div>
        <div className="actions" style={{ margin: 0 }}>
          {role === "ADMIN" && <button onClick={() => run(() => post("/api/notifications/scan", {}), "Scan complete")}>Run due-date scan</button>}
          <button onClick={() => run(() => post("/api/notifications/read-all", {}), "All marked as read")}>Mark all read</button></div></div>
      <Msg msg={msg} onClose={() => setMsg(null)} />
      <div className="chips" style={{ marginBottom: 12 }}><button className={`chip ${!unreadOnly ? "on" : ""}`} onClick={() => { setUnreadOnly(false); setPage(0); }}>All</button><button className={`chip ${unreadOnly ? "on" : ""}`} onClick={() => { setUnreadOnly(true); setPage(0); }}>Unread</button></div>
      <Card title="Inbox">{!list.data ? <div className="skeleton" /> : !list.data.items.length ? <Empty>You are all caught up.</Empty> : list.data.items.map((n) => (
        <div key={n.id} className={`note ${n.isRead ? "" : "unread"}`} onClick={() => open(n)} style={{ cursor: "pointer" }}>
          {!n.isRead && <span className="dot" />}{n.title} <Badge value={n.severity} /><small>{n.message} · {fmt(n.createdAt)}</small></div>))}
        <Pager page={list.data} onPage={setPage} /></Card>
    </div>
  );
}
