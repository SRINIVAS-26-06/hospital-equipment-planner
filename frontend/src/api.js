let authHeader = null; // kept in memory only, never written to storage

export function setCredentials(user, pass) {
  authHeader = user ? "Basic " + btoa(`${user}:${pass}`) : null;
}

let onUnauthorized = null;

/** Called when a signed-in session gets a 401 (e.g. the account was deactivated or the password was reset). */
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn;
}

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `HTTP ${status}`);
    this.status = status;
    this.code = body?.code || "ERROR";
    this.fieldErrors = body?.fieldErrors || {};
  }
}

export const qs = (params = {}) => {
  const p = Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== "" && v !== false);
  return p.length ? "?" + p.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join("&") : "";
};

async function request(method, path, body, form) {
  const headers = { ...(authHeader ? { Authorization: authHeader } : {}) };
  if (body !== undefined && !form) headers["Content-Type"] = "application/json";
  const res = await fetch(path, { method, headers, body: form ? form : body !== undefined ? JSON.stringify(body) : undefined });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!res.ok) {
    if (res.status === 401 && authHeader && onUnauthorized) onUnauthorized();
    throw new ApiError(res.status, data);
  }
  return data;
}

export const get = (path, params) => request("GET", path + qs(params));
export const post = (path, body) => request("POST", path, body ?? {});
export const put = (path, body) => request("PUT", path, body);
export const patch = (path, body) => request("PATCH", path, body);
export const upload = (path, formData) => request("POST", path, undefined, formData);

export async function download(path, filename) {
  const res = await fetch(path, { headers: authHeader ? { Authorization: authHeader } : {} });
  if (!res.ok) throw new ApiError(res.status, null);
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}
