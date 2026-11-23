const pending = new Map();
let nextId = 0;
let installed = false;

export const isLocal = () => typeof window.SunSettings?.postMessage === 'function';

export function nativeRequest(method, params = {}, timeout = 45000) {
  if (!installed) {
    // Only the host's asset-origin bridge supplies this channel; there is no network fallback.
    window.SunSettings.onmessage = (event) => {
      let body;
      try { body = typeof event.data === 'string' ? JSON.parse(event.data) : event.data; } catch { return; }
      const entry = pending.get(String(body.id));
      if (!entry) return;
      clearTimeout(entry.timer);
      pending.delete(String(body.id));
      if (body.error) {
        const error = Error(body.error.message || '操作失败');
        error.status = body.error.status;
        entry.reject(error);
      } else entry.resolve(body.result);
    };
    installed = true;
  }
  return new Promise((resolve, reject) => {
    const id = String(++nextId);
    const timer = setTimeout(() => { pending.delete(id); reject(Error('客户端响应超时，请重试')); }, timeout);
    pending.set(id, { resolve, reject, timer });
    try { window.SunSettings.postMessage(JSON.stringify({ id, method, params })); }
    catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
  });
}

export function apiRequest(token) {
  return async (path, options = {}) => {
    const response = await fetch(`/api/v1${path}`, {
      ...options,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...options.headers },
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      const error = Error(body.error || `请求失败（${response.status}）`);
      error.status = response.status;
      throw error;
    }
    return body;
  };
}
