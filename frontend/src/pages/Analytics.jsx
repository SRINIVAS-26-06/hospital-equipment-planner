import { useState } from "react";
import { get } from "../api.js";
import { Badge, Bars, Card, Columns, Line, Empty, Field, pct, num, useData } from "../components/ui.jsx";

export default function Analytics({ ctx }) {
  const [days, setDays] = useState(30);
  const [dept, setDept] = useState("");
  const to = new Date();
  const from = new Date(Date.now() - days * 864e5);
  const p = { from: from.toISOString(), to: to.toISOString() };
  const departments = useData(() => get("/api/departments"), []);
  const byDept = useData(() => get("/api/analytics/utilization", { ...p, groupBy: "department" }), [days]);
  const byAsset = useData(() => get("/api/analytics/utilization", { ...p, groupBy: "asset", departmentId: dept }), [days, dept]);
  const daily = useData(() => get("/api/analytics/utilization/daily", { ...p, departmentId: dept }), [days, dept]);
  const usage = useData(() => get("/api/analytics/usage", { granularity: "month", departmentId: dept }), [dept]);
  const maint = useData(() => get("/api/analytics/maintenance", p), [days]);
  const bk = useData(() => get("/api/analytics/breakdowns", p), [days]);
  const trend = (daily.data || []).map((d) => ({ label: d.day.slice(5), value: d.utilization }));
  const usageM = Object.values((usage.data || []).reduce((a, r) => { const k = r.period.slice(0, 7); a[k] = a[k] || { label: k.slice(2), value: 0 }; a[k].value += Number(r.hours); return a; }, {}));

  return (
    <div>
      <div className="page-h"><div><h2>Analytics</h2><p>Utilization is used time ÷ available time. Time under maintenance, repair or failed calibration is excluded, idle time is not, and each hour counts for the department that owned the equipment at that time.</p></div></div>
      <div className="toolbar"><Field label="Window"><select value={days} onChange={(e) => setDays(Number(e.target.value))}>{[7, 14, 30, 60, 90].map((d) => <option key={d} value={d}>Last {d} days</option>)}</select></Field>
        <Field label="Department (asset view and trend)"><select value={dept} onChange={(e) => setDept(e.target.value)}><option value="">All departments</option>{departments.data?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select></Field></div>
      <div className="grid g-2">
        <Card title="Department utilization">{byDept.data ? <Bars max={100} unit="%" items={byDept.data.map((d) => ({ label: d.name, value: (d.utilization || 0) * 100, display: pct(d.utilization), color: d.flag === "OVERWORKED" ? "#f04438" : d.flag === "UNDERUSED" ? "#f79009" : "#2563eb" }))} /> : <div className="skeleton" />}</Card>
        <Card title="Daily utilization">{daily.data ? <Line points={trend} /> : <div className="skeleton" />}</Card>
        <Card title="Usage hours per month">{usage.data ? <Columns items={usageM} unit=" h" color="#7a5af8" /> : <div className="skeleton" />}</Card>
        {maint.data && <Card title="Maintenance compliance"><div className="list-row"><span>Completed on time</span><b>{pct(maint.data.onTimeRate)}</b></div><div className="progress"><i style={{ width: `${(maint.data.onTimeRate || 0) * 100}%` }} /></div>
          <div className="list-row"><span>Completed · open past due · upcoming</span><b>{maint.data.completed} · {maint.data.lateOpen} · {maint.data.upcoming}</b></div><div className="list-row"><span>Equipment overdue now</span><b>{maint.data.overdueAssets} ({pct(maint.data.overdueRate)})</b></div></Card>}
        {bk.data && <Card title="Breakdowns and downtime"><Columns color="#f04438" items={bk.data.byMonth.map((r) => ({ label: r.period.slice(2), value: Number(r.reports) }))} />
          <div className="list-row"><span>Reports · open</span><b>{bk.data.summary.total} · {bk.data.summary.open}</b></div><div className="list-row"><span>Mean repair time · total downtime</span><b>{num(bk.data.summary.meanRepairHours)} h · {num(bk.data.summary.totalDowntimeHours)} h</b></div></Card>}
      </div>
      <Card title="By equipment">{!byAsset.data ? <div className="skeleton" /> : !byAsset.data.length ? <Empty>No equipment in this window.</Empty> : (
        <div className="table-wrap"><table><thead><tr><th>Equipment</th><th>Available h</th><th>Unavailable h</th><th>Used h</th><th>Idle h</th><th>Utilization</th><th>Flag</th></tr></thead>
          <tbody>{byAsset.data.map((r) => <tr key={r.key}><td><b>{r.key}</b><span className="sub">{r.name}</span></td><td>{num(r.availableHours)}</td><td>{num(r.unavailableHours)}</td><td>{num(r.usedHours)}</td><td>{num(r.idleHours)}</td>
            <td style={{ minWidth: 130 }}><div className="progress"><i style={{ width: `${Math.min(100, (r.utilization || 0) * 100)}%` }} /></div>{pct(r.utilization)}</td><td><Badge value={r.flag} /></td></tr>)}</tbody></table></div>)}</Card>
    </div>
  );
}
