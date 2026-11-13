import { useCallback, useEffect, useState } from "react";
import { get, setCredentials, setUnauthorizedHandler } from "./api.js";
import { Msg, useData } from "./components/ui.jsx";
import Dashboard from "./pages/Dashboard.jsx";
import Equipment from "./pages/Equipment.jsx";
import Bookings from "./pages/Bookings.jsx";
import Usage from "./pages/Usage.jsx";
import Maintenance from "./pages/Maintenance.jsx";
import Calibration from "./pages/Calibration.jsx";
import WorkOrders from "./pages/WorkOrders.jsx";
import Analytics from "./pages/Analytics.jsx";
import Notifications from "./pages/Notifications.jsx";
import Admin from "./pages/Admin.jsx";

/** Navigation per role. This only hides what the backend would reject anyway; the server enforces every permission. */
const NAV = {
  ADMIN: ["dashboard", "equipment", "bookings", "usage", "maintenance", "calibration", "workorders", "analytics", "notifications", "admin"],
  ENGINEER: ["dashboard", "equipment", "maintenance", "calibration", "workorders", "analytics", "notifications"],
  STAFF: ["dashboard", "equipment", "bookings", "usage", "workorders", "notifications"],
  VIEWER: ["dashboard", "equipment", "maintenance", "calibration", "analytics", "notifications"],
};
const LABEL = { dashboard: "Dashboard", equipment: "Equipment", bookings: "Bookings", usage: "Usage", maintenance: "Maintenance",
  calibration: "Calibration", workorders: "Work orders", analytics: "Analytics", notifications: "Notifications", admin: "Administration" };
const PAGES = { dashboard: Dashboard, equipment: Equipment, bookings: Bookings, usage: Usage, maintenance: Maintenance,
  calibration: Calibration, workorders: WorkOrders, analytics: Analytics, notifications: Notifications, admin: Admin };
const SUBTITLE = { ADMIN: "Full operational control", ENGINEER: "Maintenance, calibration and repairs", STAFF: "Book, use and report", VIEWER: "Read-only visibility" };

function Login({ onLogin }) {
  const [u, setU] = useState("");
  const [p, setP] = useState("");
  const [err, setErr] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setCredentials(u.trim(), p);
    try {
      onLogin(await get("/api/me"));
    } catch {
      setCredentials(null);
      setErr("Those credentials were not accepted.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="login-wrap">
      <div className="login-art">
        <div className="brand"><i>E</i><b>Equipment Planner<small>Hospital engineering operations</small></b></div>
        <h1>Every device ready,<br />every repair on record.</h1>
        <p>Booking, usage, preventive maintenance, calibration and breakdowns in one place, with availability calculated from what is actually happening.</p>
        <ul><li>Conflict-free booking, enforced in the database</li><li>Maintenance and calibration with a full audit trail</li><li>Utilization reported against the department that owned the equipment at the time</li></ul>
      </div>
      <div className="login-form">
        <form onSubmit={submit}>
          <div><h2>Sign in</h2><p className="muted">Use your hospital engineering account.</p></div>
          {err && <Msg msg={{ type: "err", text: err }} />}
          <label className="field"><span>Username</span><input autoFocus autoComplete="username" value={u} onChange={(e) => setU(e.target.value)} required /></label>
          <label className="field"><span>Password</span><input type="password" autoComplete="current-password" value={p} onChange={(e) => setP(e.target.value)} required /></label>
          <button className="primary" disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
        </form>
      </div>
    </div>
  );
}

const parse = () => {
  const [page = "dashboard", id = ""] = window.location.hash.replace(/^#\/?/, "").split("/");
  return { page, id };
};

export default function App() {
  const [me, setMe] = useState(null);
  const [route, setRoute] = useState(parse());
  const [toast, setToast] = useState(null);

  useEffect(() => {
    const h = () => setRoute(parse());
    window.addEventListener("hashchange", h);
    return () => window.removeEventListener("hashchange", h);
  }, []);
  useEffect(() => {
    if (!toast) return undefined;
    const t = setTimeout(() => setToast(null), 6000);
    return () => clearTimeout(t);
  }, [toast]);

  // the server rejected our credentials mid-session (deactivated account, reset password): return to the sign-in screen
  useEffect(() => {
    if (!me) return undefined;
    setUnauthorizedHandler(() => { setCredentials(null); setMe(null); setToast({ type: "err", text: "Your session is no longer valid. Please sign in again." }); });
    return () => setUnauthorizedHandler(null);
  }, [me]);

  const signedIn = !!me;
  const unread = useData(() => (signedIn ? get("/api/notifications/unread-count") : Promise.resolve(null)), [signedIn], 15000);
  const logout = useCallback(() => { setCredentials(null); setMe(null); window.location.hash = "#/dashboard"; }, []);

  if (!me) return <Login onLogin={setMe} />;
  const nav = NAV[me.role] || NAV.VIEWER;
  const page = nav.includes(route.page) ? route.page : "dashboard";
  const Page = PAGES[page];
  const go = (p, id) => { window.location.hash = `#/${p}${id ? "/" + id : ""}`; };
  const count = unread.data?.unread || 0;
  const ctx = { me, role: me.role, go, toast: setToast, routeId: route.id, refreshUnread: unread.reload };

  return (
    <div className="shell">
      <nav className="side" aria-label="Main">
        <div className="brand"><i>E</i><b>Equipment Planner<small>{SUBTITLE[me.role]}</small></b></div>
        {nav.map((n) => (
          <a key={n} href={`#/${n}`} className={page === n ? "on" : ""} aria-current={page === n ? "page" : undefined}>
            {LABEL[n]}{n === "notifications" && count > 0 && <span className="count">{count}</span>}
          </a>
        ))}
        <div className="who">
          <b>{me.fullName || me.username}</b>
          <span style={{ opacity: .7, fontSize: 12 }}>{me.username}{me.departmentName ? ` · ${me.departmentName}` : ""}</span>
          <div><span className="badge">{me.role}</span></div>
          <button onClick={logout}>Sign out</button>
        </div>
      </nav>
      <main>
        <Page ctx={ctx} />
      </main>
      {toast && <div className="toast"><Msg msg={toast} onClose={() => setToast(null)} /></div>}
    </div>
  );
}
