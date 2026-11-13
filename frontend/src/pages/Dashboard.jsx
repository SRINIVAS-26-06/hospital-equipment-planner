import { get } from "../api.js";
import { Badge, Bars, Card, Columns, Donut, Empty, Line, STATE_COLORS, Stat, fmt, fmtDate, num, pct, useData } from "../components/ui.jsx";

const GREETING = () => { const h = new Date().getHours(); return h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening"; };
const ROLE_LINE = {
  ADMIN: "Here is the state of the equipment fleet and everything that needs a decision.",
  ENGINEER: "Your assigned maintenance, calibration and repairs, plus what is overdue.",
  STAFF: "Find equipment, manage your bookings and report problems quickly.",
  VIEWER: "A read-only view of availability, maintenance and utilization.",
};

function List({ items, render, empty }) {
  if (!items?.length) return <Empty>{empty}</Empty>;
  return <div>{items.map(render)}</div>;
}

export default function Dashboard({ ctx }) {
  const { role, me, go } = ctx;
  const summary = useData(() => get("/api/dashboard"), [], 15000);       // polls every 15 s
  const charts = useData(() => get("/api/dashboard/charts"), [], 60000); // polls every 60 s
  const s = summary.data;
  const c = charts.data;
  if (summary.error && !s) return <p className="msg err">{summary.error}</p>;
  if (!s) return <div><div className="skeleton" style={{ height: 90 }} /><div className="skeleton" style={{ height: 160 }} /></div>;

  const e = s.equipment, k = s.kpis, mine = s.mine;
  const isAdmin = role === "ADMIN", isEng = role === "ENGINEER", isStaff = role === "STAFF";
  const ago = Math.max(0, Math.round((Date.now() - new Date(s.generatedAt).getTime()) / 1000));

  const availability = [
    ["Available", e.available, "AVAILABLE"], ["Booked", e.booked, "BOOKED"], ["In use", e.inUse, "IN_USE"],
    ["Under maintenance", e.underMaintenance, "UNDER_MAINTENANCE"], ["Overdue maintenance", e.overdueMaintenance, "OVERDUE_MAINTENANCE"],
    ["Under repair", e.underRepair, "UNDER_REPAIR"], ["Calibration failed", e.calibrationFailed, "CALIBRATION_FAILED"],
    ["Out of service", e.outOfService, "OUT_OF_SERVICE"],
  ].map(([label, value, key]) => ({ label, value, color: STATE_COLORS[key] }));

  const dept = (c?.departmentUtilization || []).map((d) => ({ label: d.name, value: (d.utilization || 0) * 100, display: pct(d.utilization),
    color: d.flag === "OVERWORKED" ? "#f04438" : d.flag === "UNDERUSED" ? "#f79009" : "#2563eb" }));
  const trend = (c?.utilizationTrend || []).map((d) => ({ label: d.day.slice(5), value: d.utilization }));
  const usageDaily = Object.values((c?.usageDaily || []).reduce((acc, r) => { acc[r.period] = acc[r.period] || { label: r.period.slice(5), value: 0 }; acc[r.period].value += Number(r.hours); return acc; }, {}));
  const usageMonthly = Object.values((c?.usageMonthly || []).reduce((acc, r) => { const m = r.period.slice(0, 7); acc[m] = acc[m] || { label: m.slice(2), value: 0 }; acc[m].value += Number(r.hours); return acc; }, {}));
  const bk = (c?.breakdowns?.byMonth || []).map((r) => ({ label: r.period.slice(2), value: Number(r.reports) }));
  const mt = c?.maintenance;

  return (
    <div>
      <div className="hero">
        <div><h2>{GREETING()}, {me.fullName?.split(" ")[0] || me.username}</h2><p>{ROLE_LINE[role]}</p></div>
        <div className="live"><i />Live · refreshed {ago}s ago · auto-refresh on</div>
      </div>

      <div className="grid g-stats">
        <Stat label="Total equipment" value={e.total} tone="info" onClick={() => go("equipment")} />
        <Stat label="Available now" value={e.available} tone="good" onClick={() => go("equipment")} sub={e.total ? `${Math.round((e.available / e.total) * 100)}% of fleet` : ""} />
        <Stat label="In use" value={e.inUse} tone="violet" />
        <Stat label="Under maintenance" value={e.underMaintenance} tone="warn" onClick={() => go("maintenance")} />
        <Stat label="Out of service" value={e.outOfService + e.underRepair + e.calibrationFailed} tone={e.outOfService + e.underRepair + e.calibrationFailed ? "bad" : ""} sub={`${e.underRepair} repair · ${e.calibrationFailed} calibration`} />
        <Stat label="Overdue maintenance" value={e.overdueMaintenance} tone={e.overdueMaintenance ? "bad" : "good"} onClick={() => go("maintenance")} />
        {(isAdmin || isEng || isStaff) && <Stat label="Active bookings" value={k.activeBookings} tone="info" sub={`${k.upcomingBookings} upcoming`} onClick={isEng ? undefined : () => go("bookings")} />}
        {(isAdmin || isEng) && <Stat label="Pending breakdowns" value={k.pendingBreakdowns} tone={k.pendingBreakdowns ? "warn" : "good"} onClick={() => go("workorders")} />}
        {(isAdmin || isEng) && <Stat label="Open work orders" value={k.openWorkOrders} tone={k.openWorkOrders ? "warn" : "good"} onClick={() => go("workorders")} />}
        <Stat label="Maintenance due ≤14d" value={k.maintenanceDueSoon + k.upcomingMaintenance} tone="warn" onClick={() => go("maintenance")} />
        <Stat label="Calibration due ≤30d" value={k.calibrationDue} tone={k.calibrationDue ? "warn" : "good"} onClick={() => go("calibration")} />
        {(isAdmin || role === "VIEWER") && <Stat label="Warranty ending ≤30d" value={k.warrantyExpiring} tone={k.warrantyExpiring ? "warn" : ""} />}
      </div>

      {(isStaff || isEng || isAdmin) && (
        <div className="grid g-2">
          {isStaff && <Card title="Your active session" actions={<button className="link" onClick={() => go("usage")}>Open usage</button>}>
            <List items={mine.activeSessions} empty="No session in progress." render={(r) => <div className="list-row" key={r.id}><span><b>{r.assetTag}</b><small>{r.assetName} · since {fmt(r.startAt)}</small></span><Badge value="IN_USE" /></div>} />
          </Card>}
          {(isStaff || isAdmin) && <Card title="Your upcoming bookings" actions={<button className="link" onClick={() => go("bookings")}>Manage</button>}>
            <List items={mine.bookings} empty="You have no upcoming bookings." render={(r) => <div className="list-row" key={r.id}><span><b>{r.assetTag}</b><small>{r.assetName}</small></span><small>{fmt(r.startAt)}</small></div>} />
          </Card>}
          {(isEng || isAdmin) && <Card title="Your maintenance queue" actions={<button className="link" onClick={() => go("maintenance")}>Open</button>}>
            <List items={mine.maintenance} empty="Nothing assigned to you." render={(r) => <div className="list-row" key={r.id}><span><b>{r.assetTag}</b><small>{fmt(r.startAt)} · {r.priority}</small></span><Badge value={r.status} /></div>} />
          </Card>}
          {(isEng || isAdmin) && <Card title="Your calibrations" actions={<button className="link" onClick={() => go("calibration")}>Open</button>}>
            <List items={mine.calibrations} empty="No calibration assigned to you." render={(r) => <div className="list-row" key={r.id}><span><b>{r.assetTag}</b><small>{r.assetName}</small></span><small>{fmt(r.scheduledFor)}</small></div>} />
          </Card>}
          {(isEng || isAdmin) && <Card title="Your work orders" actions={<button className="link" onClick={() => go("workorders")}>Open</button>}>
            <List items={mine.workOrders} empty="No work orders assigned to you." render={(r) => <div className="list-row" key={r.id}><span><b>{r.assetTag}</b><small>{r.title}</small></span><Badge value={r.status} /></div>} />
          </Card>}
          {isStaff && <Card title="Breakdowns you reported" actions={<button className="link" onClick={() => go("workorders")}>Report / track</button>}>
            <List items={mine.reportedBreakdowns} empty="You have not reported any breakdowns." render={(r) => <div className="list-row" key={r.id}><span><b>{r.assetTag}</b><small>{r.title}</small></span><Badge value={r.status} /></div>} />
          </Card>}
        </div>
      )}

      <div className="grid g-2">
        <Card title="Equipment availability right now"><Donut items={availability} /></Card>
        <Card title="Department utilization · last 7 days">{c ? <Bars items={dept} max={100} unit="%" /> : <div className="skeleton" />}
          <p className="muted" style={{ fontSize: 12 }}>Used time ÷ available time. Maintenance, repairs and failed calibration are not counted against a department.</p></Card>
        <Card title="Fleet utilization trend · 14 days">{c ? <Line points={trend} /> : <div className="skeleton" />}</Card>
        <Card title="Usage hours per day">{c ? <Columns items={usageDaily} unit=" h" color="#2563eb" /> : <div className="skeleton" />}</Card>
        {(isAdmin || isEng || role === "VIEWER") && <Card title="Usage hours per month">{c ? <Columns items={usageMonthly} unit=" h" color="#7a5af8" /> : <div className="skeleton" />}</Card>}
        {(isAdmin || isEng) && <Card title="Breakdowns reported per month">{c ? <Columns items={bk} color="#f04438" /> : <div className="skeleton" />}
          {c && <p className="muted" style={{ fontSize: 12 }}>Mean repair time {num(c.breakdowns.summary?.meanRepairHours)} h · total downtime {num(c.breakdowns.summary?.totalDowntimeHours)} h</p>}</Card>}
        {mt && <Card title="Maintenance compliance · 90 days">
          <div className="list-row"><span>Completed on time</span><b>{pct(mt.onTimeRate)}</b></div>
          <div className="progress"><i style={{ width: `${(mt.onTimeRate || 0) * 100}%` }} /></div>
          <div className="list-row"><span>Completed / still open past due</span><b>{mt.completed} / {mt.lateOpen}</b></div>
          <div className="list-row"><span>Equipment currently overdue</span><b>{mt.overdueAssets} of {mt.activeAssets} ({pct(mt.overdueRate)})</b></div>
        </Card>}
      </div>

      <div className="grid g-2">
        <Card title="Upcoming maintenance"><List items={s.upcomingMaintenance} empty="No maintenance scheduled." render={(r) => <div className="list-row" key={r.id}><span><b>{r.assetTag}</b><small>{r.assetName} · {r.priority}{r.assignedEngineer ? ` · ${r.assignedEngineer}` : " · unassigned"}</small></span><small>{fmt(r.startAt)}</small></div>} /></Card>
        <Card title="Overdue maintenance"><List items={s.overdueAssets} empty="Nothing is overdue." render={(r) => <div className="list-row" key={r.id}><span><b>{r.assetTag}</b><small>{r.name} · {num(r.usageHoursSinceMaintenance, 0)}/{num(r.maintenanceThresholdHours, 0)} h</small></span><span><Badge value="OVERDUE_MAINTENANCE" /><small>due {fmtDate(r.nextMaintenanceDue)}</small></span></div>} /></Card>
        <Card title="Calibration due or failed"><List items={s.calibrationDueList} empty="No calibration is due." render={(r) => <div className="list-row" key={r.id}><span><b>{r.assetTag}</b><small>{r.name}</small></span>{r.calibrationFailed ? <Badge value="CALIBRATION_FAILED" /> : <small>due {fmtDate(r.nextCalibrationDue)}</small>}</div>} /></Card>
        {role !== "VIEWER" && <Card title="Open work orders"><List items={s.openWorkOrders} empty="No open breakdowns." render={(r) => <div className="list-row" key={r.id}><span><b>{r.assetTag}</b><small>{r.title}{r.unsafe ? " · UNSAFE" : ""}</small></span><span><Badge value={r.priority} /> <Badge value={r.status} /></span></div>} /></Card>}
      </div>
    </div>
  );
}
