import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';

// Production and local development both proxy this same-origin API path.
const API = '/api/v1';
const EMPTY_SMTP = { host: '', port: '587', user: '', password: '', from: '', security: 'starttls' };

function App() {
  const [token, setToken] = useState(localStorage.token || '');
  const [status, setStatus] = useState(null);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [setupToken, setSetupToken] = useState('');
  const [code, setCode] = useState('');
  const [mode, setMode] = useState('login');
  const [config, setConfig] = useState('{}');
  const [version, setVersion] = useState(0);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [isAdmin, setIsAdmin] = useState(false);
  const [settings, setSettings] = useState({ registrationEnabled: false, smtp: EMPTY_SMTP });
  const [passwordSet, setPasswordSet] = useState(false);
  const [settingsLoaded, setSettingsLoaded] = useState(false);
  const dirty = useRef(false);

  async function request(path, options = {}) {
    const response = await fetch(API + path, {
      ...options,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...options.headers },
    });
    const body = await response.json();
    if (!response.ok) throw Error(body.error || '请求失败');
    return body;
  }

  async function perform(action) {
    setBusy(true);
    setMessage('');
    try {
      await action();
    } catch (error) {
      setMessage(error.message);
    } finally {
      setBusy(false);
    }
  }

  function acceptLogin(body) {
    localStorage.token = body.token;
    setToken(body.token);
    setPassword('');
    setSetupToken('');
    setMessage('');
    dirty.current = false;
  }

  function logout() {
    localStorage.removeItem('token');
    setToken('');
    setIsAdmin(false);
    setSettingsLoaded(false);
    setConfig('{}');
    setVersion(0);
    setMode('login');
    dirty.current = false;
  }

  useEffect(() => {
    let cancelled = false;
    request('/setup').then((body) => { if (!cancelled) setStatus(body); })
      .catch((error) => { if (!cancelled) setMessage(error.message); });
    return () => { cancelled = true; };
  }, [token]);

  useEffect(() => {
    if (!token) return undefined;
    let cancelled = false;
    async function loadConfig() {
      const body = await request('/config');
      // Polling must not overwrite a draft while the user edits the JSON.
      if (!cancelled && !dirty.current) {
        setConfig(JSON.stringify(body.config, null, 2));
        setVersion(body.version);
      }
    }
    request('/auth/me').then(async (body) => {
      if (cancelled) return;
      setIsAdmin(body.isAdmin);
      if (body.isAdmin) {
        const result = await request('/settings');
        if (!cancelled) {
          setSettings(result.settings);
          setPasswordSet(result.smtpPasswordSet);
          setSettingsLoaded(true);
        }
      }
    }).catch((error) => { if (!cancelled) setMessage(error.message); });
    loadConfig().catch((error) => { if (!cancelled) setMessage(error.message); });
    const timer = setInterval(() => loadConfig().catch(() => {}), 30000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [token]);

  function smtpField(key, value) {
    setSettings((current) => ({ ...current, smtp: { ...current.smtp, [key]: value } }));
  }

  if (!status) return <main className="auth"><h1>太阳云配置</h1><p role="status">{message || '正在连接平台…'}</p>{message && <button onClick={() => perform(async () => setStatus(await request('/setup')))}>重试</button>}</main>;

  if (!status.initialized) return (
    <main className="auth">
      <h1>初始化太阳云配置</h1>
      <p>无需预先配置邮箱或域名。先建立管理员账号，后续在网页管理部署设置。</p>
      <p>请从平台的后端容器日志复制一次性安装码，或运行 <code>docker compose logs backend</code>。</p>
      <form onSubmit={(event) => {
        event.preventDefault();
        perform(async () => {
          const body = await request('/setup', { method: 'POST', body: JSON.stringify({ setupToken, email, password }) });
          setStatus({ initialized: true, registrationEnabled: false });
          acceptLogin(body);
        });
      }}>
        <label>一次性安装码<input type="password" value={setupToken} onChange={(event) => setSetupToken(event.target.value)} autoComplete="off" required /></label>
        <label>管理员邮箱<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="username" required /></label>
        <label>管理员密码<input type="password" minLength={8} maxLength={72} value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="new-password" required /></label>
        <small>升级已有部署时，可填写原账号邮箱和原密码，将该账号设为管理员。</small>
        <button disabled={busy}>完成初始化</button>
      </form>
      <p role="status">{message}</p>
    </main>
  );

  if (!token) return (
    <main className="auth">
      <h1>太阳云配置</h1>
      {mode !== 'verify' ? <form onSubmit={(event) => {
        event.preventDefault();
        perform(async () => {
          const body = await request(`/auth/${mode}`, { method: 'POST', body: JSON.stringify({ email, password }) });
          if (mode === 'register') {
            setMode('verify');
            setMessage(body.message);
          } else acceptLogin(body);
        });
      }}>
        <label>邮箱<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="username" required /></label>
        <label>密码<input type="password" minLength={8} maxLength={72} value={password} onChange={(event) => setPassword(event.target.value)} autoComplete={mode === 'register' ? 'new-password' : 'current-password'} required /></label>
        <button disabled={busy}>{mode === 'register' ? '注册' : '登录'}</button>
      </form> : <form onSubmit={(event) => {
        event.preventDefault();
        perform(async () => {
          await request('/auth/verify', { method: 'POST', body: JSON.stringify({ email, code }) });
          setMode('login');
          setMessage('验证成功，请登录');
        });
      }}>
        <label>邮箱验证码<input value={code} onChange={(event) => setCode(event.target.value)} autoComplete="one-time-code" required /></label>
        <button disabled={busy}>验证邮箱</button>
      </form>}
      {(status.registrationEnabled || mode !== 'login') && <button className="link" disabled={busy} onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setMessage(''); }}>{mode === 'login' ? '创建账号' : '返回登录'}</button>}
      {!status.registrationEnabled && <p>管理员尚未开放注册。</p>}
      <p role="status">{message}</p>
    </main>
  );

  return (
    <main>
      <header><div><h1>配置中心</h1><p>配置保存在远端数据库，客户端会自动同步。</p></div><button onClick={logout}>退出</button></header>
      <p className="notice" role="status">{message}</p>
      <section>
        <div className="bar"><span>当前版本 v{version}</span><button disabled={busy} onClick={() => perform(async () => {
          const parsed = JSON.parse(config);
          if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object' || Object.values(parsed).some((value) => typeof value !== 'string')) throw Error('配置必须是键和值均为字符串的 JSON 对象');
          const body = await request('/config', { method: 'PUT', body: JSON.stringify({ config: parsed }) });
          setVersion(body.version);
          dirty.current = false;
          setMessage('配置已下发');
        })}>保存并下发</button></div>
        <textarea aria-label="客户端配置 JSON" value={config} onChange={(event) => { dirty.current = true; setConfig(event.target.value); }} spellCheck="false" />
      </section>
      {isAdmin && settingsLoaded && <section className="settings">
        <h2>部署设置</h2>
        <p>保存后立即生效，无需重启容器。管理员无需 SMTP 即可使用配置中心。</p>
        <form onSubmit={(event) => {
          event.preventDefault();
          perform(async () => {
            const body = await request('/settings', { method: 'PUT', body: JSON.stringify(settings) });
            setSettings(body.settings);
            setPasswordSet(body.smtpPasswordSet);
            setStatus(await request('/setup'));
            setMessage('部署设置已保存');
          });
        }}>
          <div className="settings-grid">
            <label>SMTP 主机<input placeholder="smtp.example.com" value={settings.smtp.host} onChange={(event) => smtpField('host', event.target.value)} /></label>
            <label>SMTP 端口<input type="number" min="1" max="65535" value={settings.smtp.port} onChange={(event) => smtpField('port', event.target.value)} required /></label>
            <label>加密方式<select value={settings.smtp.security} onChange={(event) => smtpField('security', event.target.value)}><option value="starttls">STARTTLS（通常为 587）</option><option value="tls">TLS（通常为 465）</option></select></label>
            <label>SMTP 用户名<input value={settings.smtp.user} onChange={(event) => smtpField('user', event.target.value)} autoComplete="off" /></label>
            <label>SMTP 密码 / 授权码<input type="password" placeholder={passwordSet ? '已保存，留空保持不变' : '尚未设置'} value={settings.smtp.password} onChange={(event) => smtpField('password', event.target.value)} autoComplete="new-password" /></label>
            <label>发件人邮箱<input type="email" placeholder="默认使用 SMTP 用户名" value={settings.smtp.from} onChange={(event) => smtpField('from', event.target.value)} /></label>
          </div>
          <label className="checkbox"><input type="checkbox" checked={settings.registrationEnabled} onChange={(event) => setSettings({ ...settings, registrationEnabled: event.target.checked })} />开放用户注册（需要完整 SMTP 配置）</label>
          <div className="actions"><button disabled={busy}>保存部署设置</button><button type="button" disabled={busy} onClick={() => perform(async () => {
            const body = await request('/settings/test-email', { method: 'POST' });
            setMessage(body.message);
          })}>使用已保存设置发送测试邮件</button></div>
        </form>
      </section>}
    </main>
  );
}

createRoot(document.getElementById('root')).render(<App />);
