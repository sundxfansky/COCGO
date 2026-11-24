import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import schema from './schema.json';
import SettingsEditor, { acceptRuntimeSchema } from './SettingsEditor.jsx';
import DeviceTools, { ServerDeviceTools } from './DeviceTools.jsx';
import RemoteRuntimeControls from './RuntimeControls.jsx';
import { Help, Icon, Modal, Toggle } from './components.jsx';
import { canApplySnapshot, countAccounts, fetchChangedConfig, normalizeConfig, validateConfig } from './model.js';
import { apiRequest, isLocal, nativeRequest } from './transport.js';
import './style.css';

const LOCAL = isLocal();
const SURFACE = location.origin === 'https://appassets.androidplatform.net'
  ? new URLSearchParams(location.search).get('surface')
  : '';
const readToken = () => { try { return localStorage.getItem('token') || ''; } catch { return ''; } };

function Brand({ compact = false }) {
  return <div className={`brand ${compact ? 'compact' : ''}`}><span className="brand-mark"><Icon name="sun" size={25}/></span><div><strong>太阳 <span>控制台</span></strong>{!compact && <small>SUN CONFIGURATION</small>}</div></div>;
}

function Auth({ onLogin }) {
  const [status, setStatus] = useState(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [setupToken, setSetupToken] = useState('');
  const request = apiRequest('');
  async function load() { try { setStatus(await request('/setup')); setMessage(''); } catch (error) { setMessage(error.message); } }
  useEffect(() => { load(); }, []);
  const setup = status && !status.initialized;
  return <main className="auth-layout"><div className="auth-art"><Brand/><div className="auth-intro"><span className="eyebrow">每台设备，各自井然有序</span><h1>设置很轻。<br/>掌控很简单。</h1><p>一个工作台，管理你的所有设备。</p><div className="illustration" aria-hidden="true"><div className="illustration-card one"><Icon name="cloud" size={29}/><span>连接就绪</span><i/></div><div className="illustration-card two"><span className="illustration-avatar">01</span><div><b>我的设备</b><span className="illustration-lines"/></div><Icon name="check"/></div><div className="illustration-card three"><Icon name="settings"/><span>每个账号，独立配置</span></div></div></div><small className="auth-footnote">让重复变简单，让设置更清晰。</small></div><section className="auth-panel"><Brand compact/><div className="auth-form-wrap"><span className="eyebrow">{setup ? '开始使用' : '欢迎回来'}</span><h2>{setup ? '创建管理账号' : '登录你的工作台'}</h2><p>{setup ? '完成一次初始化，即可接入设备。' : '继续管理设备与自动化设置。'}</p>
    {!status ? <div className="loading-state" role="status"><span className="spinner"/>{message || '正在连接服务…'}{message && <button type="button" onClick={load}>重试</button>}</div> : <form onSubmit={async (event) => { event.preventDefault(); setBusy(true); setMessage(''); try { const body = await request(setup ? '/setup' : '/auth/login', { method: 'POST', body: JSON.stringify({ email, password, ...(setup ? { setupToken } : {}) }) }); onLogin(body.token); } catch (error) { setMessage(error.message); } finally { setBusy(false); } }}>
      {setup && <label><span className="inline-label">一次性安装码<Help>在服务端启动日志中查看一次性安装码。升级已有部署时，可填写原账号邮箱和密码，保留原有配置。</Help></span><input type="password" value={setupToken} onChange={(event) => setSetupToken(event.target.value)} required autoComplete="off"/></label>}
      <label>邮箱<input type="email" placeholder="name@example.com" value={email} onChange={(event) => setEmail(event.target.value)} required autoComplete="username"/></label>
      <label>密码<input type="password" placeholder="输入管理账号密码" minLength={8} maxLength={72} value={password} onChange={(event) => setPassword(event.target.value)} required autoComplete={setup ? 'new-password' : 'current-password'}/></label>
      <button className="auth-submit" disabled={busy}>{busy ? '正在连接…' : setup ? '创建并进入工作台' : '进入工作台'}<Icon name="arrow" size={18}/></button>
    </form>}{message && status && <p className="notice error" role="alert">{message}</p>}<div className="auth-security"><Icon name="shield" size={16}/><span>设备与配置，只在你的账号内共享</span></div></div></section></main>;
}

function ConnectionCard({ connection, sync, busy, dirty, onSave }) {
  const [endpoint, setEndpoint] = useState(connection?.endpoint || '');
  const [deviceName, setDeviceName] = useState(connection?.deviceName || '');
  const [token, setToken] = useState('');
  const [enabled, setEnabled] = useState(Boolean(connection?.enabled));
  const [confirming, setConfirming] = useState(false);
  const [editing, setEditing] = useState(false);
  useEffect(() => { if (!editing) { setEndpoint(connection?.endpoint || ''); setDeviceName(connection?.deviceName || ''); setEnabled(Boolean(connection?.enabled)); } }, [connection?.endpoint, connection?.deviceName, connection?.enabled, editing]);
  async function submit() { if (await onSave({ endpoint, token, enabled, deviceName })) { setToken(''); setEditing(false); setConfirming(false); } }
  return <details className="card connection-card accent-blue"><summary><span className="card-icon"><Icon name="cloud"/></span><div><h3>远端同步</h3><span className="muted">{connection?.enabled ? sync?.message || '由服务端管理配置' : '使用本地设置'}</span></div><span className={`status-pill ${connection?.enabled ? 'teal' : 'neutral'}`}>{connection?.enabled ? '已启用' : '未启用'}</span><Icon name="arrow" size={16}/></summary><div className="module-body"><form onSubmit={(event) => { event.preventDefault(); if (dirty) setConfirming(true); else submit(); }}>
    <div className="field-grid"><label>服务端地址<input type="url" placeholder="https://config.example.com" value={endpoint} disabled={busy} onChange={(event) => { setEditing(true); setEndpoint(event.target.value); }} required={enabled}/></label><label>设备名称<input maxLength={80} value={deviceName} disabled={busy} onChange={(event) => { setEditing(true); setDeviceName(event.target.value); }} placeholder="例如：客厅手机"/></label><label className="full-width"><span className="inline-label">访问 Token<Help>在服务端工作台的“接入设备”中复制访问 Token。同一服务端留空可保留现有 Token；换服务端时请重新填写。</Help></span><input type="password" value={token} disabled={busy} placeholder={connection?.tokenSet ? '已设置，留空保持不变' : '粘贴服务端访问 Token'} autoComplete="off" onChange={(event) => { setEditing(true); setToken(event.target.value); }}/></label></div>
    <div className="connection-actions"><span className="inline-label">启用远端配置<Help>启用后本地设置只读，客户端定时检查服务端版本并应用新配置。请在服务端对应设备下编辑和保存。</Help></span><Toggle label="启用远端配置" checked={enabled} disabled={busy} onChange={(value) => { setEditing(true); setEnabled(value); }}/><button disabled={busy}>保存连接</button></div>
    {connection?.deviceId && <div className="device-code"><span>设备标识</span><code>{connection.deviceId}</code></div>}
  </form></div>{confirming && <Modal title="切换配置来源" onClose={() => setConfirming(false)}><p>当前有未保存的本地草稿。保存连接将重新加载配置，并丢弃这份草稿。</p><div className="actions"><button type="button" className="secondary" onClick={() => setConfirming(false)}>取消</button><button type="button" disabled={busy} onClick={submit}>保存连接并重新加载</button></div></Modal>}</details>;
}

function DeviceWorkspace({ device, token, active, onDeviceState, showTools }) {
  const [serverTools, setServerTools] = useState(false);
  const toolsVisible = showTools || serverTools;
  const [catalog, setCatalog] = useState(schema);
  const [config, setConfig] = useState(null);
  const [version, setVersion] = useState(null);
  const [metadata, setMetadata] = useState({});
  const [dirty, setDirty] = useState(false);
  const [conflict, setConflict] = useState(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [reloadConfirm, setReloadConfirm] = useState(false);
  const [lastChecked, setLastChecked] = useState(null);
  const revision = useRef(0);
  const fetchedVersion = useRef(null);
  const current = useRef({ config, version, dirty, metadata, busy, catalog });
  current.current = { config, version, dirty, metadata, busy, catalog };
  const baseRequest = apiRequest(token);
  const request = (path, options) => baseRequest(path.replace(/^\/config/, `/devices/${encodeURIComponent(device.id)}/config`), options);
  function apply(body) {
    const nextCatalog = body.schema ? acceptRuntimeSchema(body.schema) : current.current.catalog;
    setCatalog(nextCatalog);
    setConfig(normalizeConfig(body.config, nextCatalog));
    setVersion(body.version); setMetadata(body); setDirty(false); setConflict(null); revision.current++;
    fetchedVersion.current = body.version;
    onDeviceState?.(device.id, { version: body.version, ...(body.connection?.deviceName ? { name: body.connection.deviceName } : {}) });
  }
  async function load(force = false) {
    if (LOCAL) {
      const summary = force || current.current.config === null ? null : await nativeRequest('getVersion');
      if (summary) {
        if (String(summary.version) === String(fetchedVersion.current ?? current.current.version) && summary.readOnly === current.current.metadata.readOnly) return { ...summary, unchanged: true };
      }
      return nativeRequest('getState');
    }
    return fetchChangedConfig(request, fetchedVersion.current ?? current.current.version, force || current.current.config === null);
  }
  useEffect(() => {
    if (!active) return undefined;
    let cancelled = false;
    let timer;
    async function poll() {
      const started = revision.current;
      try {
        if (current.current.busy) return;
        const body = await load();
        if (cancelled || started !== revision.current) return;
        setLastChecked(Date.now());
        if (!body) return;
        if (body.unchanged) { setMetadata((prior) => ({ ...prior, ...body })); return; }
        fetchedVersion.current = body.version;
        // Remote mode is authoritative even if another native control changed the source mid-draft.
        if ((LOCAL && body.readOnly) || canApplySnapshot(current.current.dirty, started, revision.current)) apply(body);
        else if (String(body.version) !== String(current.current.version) || body.readOnly) setConflict(body);
        if (LOCAL) setMetadata(body);
      } catch (error) { if (!cancelled) setMessage(error.message); }
      finally { if (!cancelled) timer = setTimeout(poll, LOCAL ? 5000 : 30000); }
    }
    poll();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [active, token, device.id]);
  useEffect(() => { if (!dirty) return undefined; const warn = (event) => { event.preventDefault(); event.returnValue = ''; }; window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn); }, [dirty]);
  function edit(next) { if (current.current.metadata.readOnly || current.current.busy) return; setConfig(next); setDirty(true); revision.current++; }
  async function perform(action) { setBusy(true); setMessage(''); try { await action(); return true; } catch (error) { setMessage(error.message); return false; } finally { setBusy(false); } }
  async function save() {
    await perform(async () => {
      const submitted = normalizeConfig(validateConfig(current.current.config), current.current.catalog);
      const started = revision.current;
      try {
        const body = LOCAL ? await nativeRequest('saveConfig', { config: submitted, version: current.current.version }) : await request('/config', { method: 'PUT', headers: { 'If-Match': String(current.current.version) }, body: JSON.stringify({ config: submitted }) });
        const nextCatalog = body.schema ? acceptRuntimeSchema(body.schema) : current.current.catalog;
        setCatalog(nextCatalog);
        if (started === revision.current) { setConfig(body.config ? normalizeConfig(body.config, nextCatalog) : submitted); setDirty(false); }
        setVersion(body.version); setConflict(null); revision.current++;
        fetchedVersion.current = body.version;
        if (LOCAL) setMetadata(body);
        onDeviceState?.(device.id, { version: body.version });
        setMessage(LOCAL ? '设置已保存到此设备' : '配置已发布，设备将在下次检查时应用');
      } catch (error) {
        if (error.status === 409) { const latest = await load(true); fetchedVersion.current = latest.version; setConflict(latest); }
        throw error;
      }
    });
  }
  async function reload() { await perform(async () => { const body = await load(true); if (body) apply(body); setReloadConfirm(false); }); }
  const readOnly = Boolean(metadata.readOnly);
  const accounts = config ? countAccounts(config, catalog) : 0;
  const enabledCount = config ? Array.from({ length: accounts }, (_, index) => config[`isopen${index + 1}`]).filter((value) => value === '1').length : 0;
  return <section className="workspace" id={`device-panel-${device.id}`} role={LOCAL ? undefined : 'tabpanel'} aria-labelledby={LOCAL ? undefined : `device-tab-${device.id}`} hidden={!active}>
    <div className="workspace-heading" hidden={showTools}>
      <div>{!LOCAL && <span className="eyebrow">CONNECTED WORKSPACE</span>}
        <h1>{metadata.connection?.deviceName || device.name}<span className="device-dot"/></h1>
        {!LOCAL && <p>{readOnly ? '远端管理中 · 本地自动接收更新' : '独立配置，轻松同步。'}</p>}
      </div>
      <div className="workspace-tools"><span className={`status-pill ${readOnly ? 'violet' : 'teal'}`}><span/>{readOnly ? '远端只读' : LOCAL ? '本地编辑' : '云端编辑'}</span>
        {!LOCAL && config && <button type="button" className="secondary" aria-pressed={serverTools}
          onClick={() => setServerTools(!serverTools)}>{serverTools ? '返回设置' : '设备工具'}</button>}
        <button type="button" className="icon-button" disabled={busy} aria-label="刷新当前设备配置" title="刷新配置" onClick={() => dirty ? setReloadConfirm(true) : reload()}><Icon name="refresh"/></button></div>
    </div>
    {!LOCAL && active && <RemoteRuntimeControls device={device} token={token}/>}
    {!config ? <div className="loading-state" role="status"><span className="spinner"/>{message || '正在读取设备设置…'}{message && <button type="button" onClick={reload}>重试</button>}</div> : <>
      <div className="overview-grid" hidden={showTools}><div className="overview-card"><span className="stat-icon blue"><Icon name="users"/></span><div><span>独立账号</span><strong>{accounts}<small>个</small></strong></div><span className="stat-extra">按账号管理</span></div><div className="overview-card"><span className="stat-icon teal"><Icon name="bolt"/></span><div><span>已开启</span><strong>{enabledCount}<small>个</small></strong></div><span className="stat-extra">随时调整</span></div><div className="overview-card"><span className="stat-icon violet"><Icon name="cloud"/></span><div><span>{LOCAL ? '同步状态' : '配置版本'}</span><strong className="status-value">{LOCAL ? metadata.connection?.enabled ? '远端同步' : '本地模式' : `v${version ?? 0}`}</strong></div><span className="stat-extra">{lastChecked ? `${new Date(lastChecked).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })} 检查` : '等待检查'}</span></div></div>
      {readOnly && <div className="readonly-banner"><Icon name="cloud" size={19}/><span>配置由服务端管理</span><Help>请在服务端选择这台设备进行修改。本地页面不再保存设置，服务端有新版本时会自动更新。关闭远端同步后可恢复本地编辑。</Help>{metadata.sync?.message && <small>{metadata.sync.message}</small>}</div>}
      {message && <div className="notice" role="status"><Icon name="check" size={17}/><span>{message}</span><button type="button" className="icon-button" aria-label="关闭提示" onClick={() => setMessage('')}><Icon name="close" size={15}/></button></div>}
      {conflict && <div className="conflict-banner" role="alert"><div><strong>已有更新，草稿已保留</strong><p>先查看服务端的新版本，再决定如何继续。</p></div><button type="button" className="secondary" onClick={() => setReloadConfirm(true)}>查看并处理</button></div>}
      {/* Keep settings and account navigation mounted while the tools page is open. */}
      <div hidden={toolsVisible}><SettingsEditor catalog={catalog} config={config} onChange={edit} readOnly={readOnly} busy={busy}
        deviceTools={!LOCAL || ['activity', 'overlay', 'settings'].includes(SURFACE)} onMessage={setMessage}
        homeContent={LOCAL ? <ConnectionCard connection={metadata.connection} sync={metadata.sync} busy={busy} dirty={dirty} onSave={async (params) => perform(async () => { const result = await nativeRequest('setConnection', params); apply(result); setMessage('连接设置已保存'); })}/> : null}/></div>
      {showTools && <DeviceTools catalog={catalog} config={config} onChange={edit} readOnly={readOnly}
        busy={busy} activity={SURFACE === 'activity'} version={version} onVersion={(nextVersion) => {
          // The host wrote just the authorization reference. Keep this draft and advance its base revision.
          revision.current++;
          fetchedVersion.current = nextVersion;
          setVersion(nextVersion);
          setConflict(null);
        }}/>}
      {active && serverTools && <ServerDeviceTools catalog={catalog} config={config} onChange={edit} busy={busy}
        deviceId={device.id} token={token}/>}
      <footer className="save-bar"><div><span className={`save-indicator ${dirty ? 'dirty' : ''}`}/><strong>{readOnly ? '服务端配置自动同步' : dirty ? '有未保存的修改' : !LOCAL && Number(version) === 0 ? '尚未发布初始配置' : '设置已同步'}</strong><span className="save-caption">{readOnly ? '此设备本地设置为只读' : LOCAL ? '仅保存当前设备' : '仅发布到当前设备'}</span></div><button type="button" disabled={readOnly || busy || (!dirty && (LOCAL || Number(version) !== 0)) || Boolean(conflict)} onClick={save}><Icon name="save" size={18}/>{busy ? '正在保存…' : LOCAL ? '保存设置' : Number(version) === 0 ? '发布初始配置' : '保存并发布'}</button></footer>
    </>}
    {reloadConfirm && <Modal title={conflict ? '配置版本已更新' : '重新加载配置'} onClose={() => setReloadConfirm(false)}>{conflict ? <><p>当前草稿不会自动被覆盖。可以载入最新配置，或对照最新内容手动修改草稿。</p><details className="json-details"><summary>查看最新配置</summary><pre>{JSON.stringify(conflict.config, null, 2)}</pre></details></> : <p>重新加载会放弃当前未保存的修改。</p>}<div className="actions"><button type="button" className="secondary" onClick={() => setReloadConfirm(false)}>继续编辑</button>{conflict && !readOnly && <button type="button" className="secondary" onClick={() => { setVersion(conflict.version); setConflict(null); revision.current++; setReloadConfirm(false); setMessage('已采用新版本号，请核对草稿后重新保存'); }}>已手动合并</button>}<button type="button" disabled={busy} onClick={() => conflict ? (apply(conflict), setReloadConfirm(false)) : reload()}>载入最新配置</button></div></Modal>}
  </section>;
}

function App({ showTools = false }) {
  const [token, setToken] = useState(LOCAL ? '' : readToken);
  const [devices, setDevices] = useState(LOCAL ? [{ id: 'local', name: '我的设备' }] : []);
  const [activeDevice, setActiveDevice] = useState(LOCAL ? 'local' : null);
  const [visited, setVisited] = useState(LOCAL ? ['local'] : []);
  const [loaded, setLoaded] = useState(LOCAL);
  const [message, setMessage] = useState('');
  const [connect, setConnect] = useState(false);
  const [showToken, setShowToken] = useState(false);
  const [deviceToken, setDeviceToken] = useState('');
  const [tokenBusy, setTokenBusy] = useState(false);
  const [connectMessage, setConnectMessage] = useState('');
  const [rename, setRename] = useState(null);
  const [renameValue, setRenameValue] = useState('');
  const [busy, setBusy] = useState(false);
  const [logoutConfirm, setLogoutConfirm] = useState(false);
  const currentToken = useRef(token);
  currentToken.current = token;
  const request = apiRequest(token);
  const loadDevices = useCallback(async () => {
    try { const body = await apiRequest(token)('/devices'); if (currentToken.current !== token) return; setDevices(body.devices); setLoaded(true); setMessage(''); setActiveDevice((value) => value || body.devices[0]?.id || null); }
    catch (error) { if (currentToken.current !== token) return; setLoaded(true); setMessage(error.message); }
  }, [token]);
  useEffect(() => { if (LOCAL || !token) return undefined; loadDevices(); const timer = setInterval(loadDevices, 30000); return () => clearInterval(timer); }, [token, loadDevices]);
  useEffect(() => { if (activeDevice) setVisited((list) => list.includes(activeDevice) ? list : [...list, activeDevice]); }, [activeDevice]);
  function login(value) { try { localStorage.setItem('token', value); } catch { /* Private browser sessions may disallow persistence. */ } setToken(value); setLoaded(false); setDevices([]); setVisited([]); setActiveDevice(null); setDeviceToken(''); setShowToken(false); setConnect(false); }
  async function logout() { setBusy(true); try { await request('/auth/logout', { method: 'POST' }); } catch (error) { if (error.status !== 401) { setMessage(error.message); setBusy(false); return; } } try { localStorage.removeItem('token'); } catch { /* The in-memory session is still cleared. */ } setToken(''); setDevices([]); setVisited([]); setActiveDevice(null); setDeviceToken(''); setShowToken(false); setConnect(false); setLogoutConfirm(false); setBusy(false); }
  async function generateDeviceToken() { setTokenBusy(true); setConnectMessage(''); try { const result = await request('/auth/device-token', { method: 'POST' }); setDeviceToken(result.token); setConnectMessage('设备访问 Token 已生成'); } catch (error) { setConnectMessage(error.message); } finally { setTokenBusy(false); } }
  async function copyToken() { try { await navigator.clipboard.writeText(deviceToken); setConnectMessage('访问 Token 已复制'); } catch { setShowToken(true); setConnectMessage('请选中文本并手动复制'); } }
  function openConnect() { setConnect(true); setShowToken(false); setConnectMessage(''); }
  if (!LOCAL && !token) return <Auth onLogin={login}/>;
  return <div className={`app-shell${LOCAL ? ' embedded-settings' : ''}`}>
    <header className="app-header"><Brand compact/><div className="header-actions"><span className="header-mode"><span/>{LOCAL ? '设备设置' : '设备管理'}</span>{!LOCAL && <button type="button" className="text-button" onClick={() => setLogoutConfirm(true)}>退出登录</button>}</div></header>
    <main className="dashboard">
    {!LOCAL && <>
      <div className="device-section-heading"><span className="eyebrow">你的设备</span><button type="button" className="text-button" onClick={openConnect}><span className="plus">+</span>接入设备</button></div>
      <nav className="device-tabs" role="tablist" aria-label="选择设备">{devices.map((device, index) => <div key={device.id} role="presentation" className={`device-tab ${activeDevice === device.id ? 'selected' : ''}`}><button type="button" id={`device-tab-${device.id}`} role="tab" aria-selected={activeDevice === device.id} aria-controls={`device-panel-${device.id}`} tabIndex={activeDevice === device.id ? 0 : -1} onClick={() => setActiveDevice(device.id)} onKeyDown={(event) => { if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) { event.preventDefault(); const next = event.key === 'Home' ? 0 : event.key === 'End' ? devices.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + devices.length) % devices.length; setActiveDevice(devices[next].id); document.getElementById(`device-tab-${devices[next].id}`)?.focus(); } }}><span className="device-avatar"><Icon name="settings" size={17}/></span><span>{device.name}</span>{activeDevice === device.id && <span className="selected-dot"/>}</button>{activeDevice === device.id && <button type="button" className="rename-button" aria-label={`重命名${device.name}`} title="重命名设备" onClick={() => { setRename(device); setRenameValue(device.name); }}>···</button>}</div>)}</nav>
    </>}
    {message && <div className="notice" role="status">{message}<button type="button" className="text-button" onClick={loadDevices}>刷新设备</button></div>}
    {!loaded && <div className="loading-state"><span className="spinner"/>正在连接设备工作台…</div>}
    {loaded && devices.length === 0 && <div className="empty-devices"><span className="empty-orbit"><Icon name="cloud" size={48}/></span><span className="eyebrow">准备好，让设备加入</span><h1>这里是设备的工作台</h1><p>在客户端启用远端同步，设备就会出现在这里。</p><button type="button" onClick={openConnect}>接入第一台设备<Icon name="arrow" size={18}/></button><button type="button" className="text-button" onClick={loadDevices}><Icon name="refresh" size={16}/>刷新设备列表</button></div>}
    {visited.map((id) => { const device = devices.find((item) => item.id === id); return device ? <DeviceWorkspace key={`${token}-${id}`} device={device} token={token} active={id === activeDevice} showTools={showTools} onDeviceState={(deviceId, update) => setDevices((list) => list.map((item) => item.id === deviceId ? { ...item, ...update } : item))}/> : null; })}
    {!LOCAL && <div className="page-footnote"><Icon name="sun" size={14}/><span>太阳 · 每台设备的设置独立保存</span></div>}
  </main>
    {connect && <Modal title="接入你的设备" onClose={() => setConnect(false)}><p>在客户端“主页设置 → 远端同步”中填写以下信息。保存并启用后，设备会自动加入工作台。</p><div className="connect-step"><span>1</span><label>服务端地址<input readOnly value={`${location.origin}/api/v1`} onFocus={(event) => event.target.select()}/></label></div><div className="connect-step"><span>2</span><div><div className="inline-label"><label>访问 Token</label><Help>设备 Token 有效期 30 天。它独立于网页登录，退出浏览器账号不会断开设备。到期后生成新 Token 并在客户端更新。</Help></div><div className="actions">{!deviceToken ? <button type="button" disabled={tokenBusy} onClick={generateDeviceToken}>{tokenBusy ? '正在生成…' : '生成设备 Token'}</button> : <><button type="button" onClick={copyToken}><Icon name="copy" size={16}/>复制访问 Token</button><button type="button" className="text-button" onClick={() => setShowToken(!showToken)}>{showToken ? '隐藏' : '手动查看'}</button><button type="button" className="text-button" disabled={tokenBusy} onClick={generateDeviceToken}>生成新的</button></>}</div>{showToken && <textarea className="token-value" aria-label="访问 Token" readOnly value={deviceToken} onFocus={(event) => event.target.select()}/>}</div></div><div className="connect-step"><span>3</span><div><strong>设置设备名称，启用远端配置</strong><p>客户端将自动接收此设备的新配置。</p></div></div>{connectMessage && <p className="notice" role="status">{connectMessage}</p>}<div className="actions"><button type="button" className="secondary" onClick={() => setConnect(false)}>完成</button><button type="button" onClick={loadDevices}><Icon name="refresh" size={16}/>刷新设备</button></div></Modal>}
    {rename && <Modal title="重命名设备" onClose={() => setRename(null)}><form onSubmit={async (event) => { event.preventDefault(); setBusy(true); try { await request(`/devices/${encodeURIComponent(rename.id)}`, { method: 'PATCH', body: JSON.stringify({ name: renameValue.trim() }) }); setDevices((list) => list.map((item) => item.id === rename.id ? { ...item, name: renameValue.trim() } : item)); setRename(null); } catch (error) { setMessage(error.message); } finally { setBusy(false); } }}><label>设备名称<input maxLength={80} required value={renameValue} onChange={(event) => setRenameValue(event.target.value)}/></label><div className="actions"><button type="button" className="secondary" onClick={() => setRename(null)}>取消</button><button disabled={busy || !renameValue.trim()}>保存名称</button></div></form></Modal>}
    {logoutConfirm && <Modal title="退出管理账号" onClose={() => setLogoutConfirm(false)}><p>未保存的草稿将丢失。已使用独立设备 Token 接入的设备会继续同步。</p><div className="actions"><button type="button" className="secondary" onClick={() => setLogoutConfirm(false)}>取消</button><button disabled={busy} onClick={logout}>退出登录</button></div></Modal>}
  </div>;
}

function useNativeStatus(method, interval, options = {}) {
  const [state, setState] = useState(null);
  const { enabled = true, stopWhenReady = false } = options;
  useEffect(() => {
    if (!enabled) return;
    let active = true;
    let timer;
    async function poll() {
      let result;
      try {
        result = await nativeRequest(method);
        if (active) setState(result);
      } catch (error) {
        if (active) setState({ message: error.message });
      } finally {
        if (active && !(stopWhenReady && result?.ready)) timer = setTimeout(poll, interval);
      }
    }
    poll();
    return () => { active = false; clearTimeout(timer); };
  }, [method, interval, enabled, stopWhenReady]);
  return state;
}

function LocalSettingsApp() {
  const startup = useNativeStatus('getStartupState', 300, { enabled: SURFACE !== 'settings', stopWhenReady: true });
  const ready = SURFACE === 'settings' || Boolean(startup?.ready);
  const controls = useNativeStatus('getSettingsControls', 1000, { enabled: ready });
  const [showTools, setShowTools] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const scroll = useRef(null);
  const settingsScrollTop = useRef(0);
  useLayoutEffect(() => {
    if (scroll.current) scroll.current.scrollTop = showTools ? 0 : settingsScrollTop.current;
  }, [showTools]);

  async function action(method) {
    setBusy(true);
    setError('');
    try {
      await nativeRequest(method);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  }

  const startupMessages = {
    ROOT_DENIED: '请在 Root 管理器中允许「太阳」，然后重新检测。',
    PERMISSION_DENIED: '自动授权未完成，请重试。',
    LOCAL_ERROR: '本地设置初始化失败。',
    INITIALIZING_LOCAL: '正在读取本地设置…',
  };
  const canRetry = ['ROOT_DENIED', 'PERMISSION_DENIED', 'LOCAL_ERROR'].includes(startup?.status);
  return <div className={`local-window${SURFACE === 'overlay' ? ' local-overlay' : ''}`}>
    {ready && <div className="local-window-header">
      {showTools ? <button type="button" className="text-button" onClick={() => setShowTools(false)}><Icon name="back" size={15}/>返回设置</button>
        : <span>{controls?.autoRun ? `${controls.seconds} 秒后运行已保存配置，触摸可停止` : ''}</span>}
      <button type="button" className="icon-button" aria-label="关闭设置" disabled={busy} onClick={() => action('closeSettings')}><Icon name="close" size={20}/></button>
    </div>}
    <div className="local-window-body" ref={scroll}>
      {ready ? <App showTools={showTools}/> : <main className="local-status">
        <Brand compact/>
        <p role="status">{startup?.message || startupMessages[startup?.status] || '正在启动…'}</p>
        {canRetry && <button type="button" disabled={busy} onClick={() => action('retryStartup')}>重新检测</button>}
      </main>}
    </div>
    {error && <div className="local-window-error" role="alert">{error}</div>}
    {ready && !showTools && <footer className="local-window-actions">
      <button type="button" disabled={busy} onClick={() => action('runSettings')}>运行已保存配置</button>
      <button type="button" disabled={busy} onClick={() => action('pauseSettings')}>暂停</button>
      <button type="button" disabled={busy} onClick={() => {
        settingsScrollTop.current = scroll.current.scrollTop;
        setShowTools(true);
      }}>设备工具</button>
      <button type="button" disabled={busy} onClick={() => action('exitSettings')}>退出</button>
    </footer>}
  </div>;
}

function AuthorizationActivity() {
  const status = useNativeStatus('getAuthorizationActivityStatus', 500);
  return <main className="local-status">
    <h1>QQ 授权</h1>
    <p role="status">{status?.message || '正在准备授权…'}</p>
    <button type="button" onClick={() => nativeRequest('closeAuthorizationActivity').catch(() => {})}>取消并返回</button>
  </main>;
}

function FloatingLogs() {
  const [entries, setEntries] = useState([]);
  const [interactive, setInteractive] = useState(false);
  const [follow, setFollow] = useState(true);
  const scroll = useRef(null);
  useEffect(() => {
    let active = true;
    let timer;
    let after = 0;
    async function poll() {
      try {
        const result = await nativeRequest('getLogState', { after });
        if (!active) return;
        setInteractive(result.interactive);
        if (!result.interactive) setFollow(true);
        if (result.entries.length) after = result.entries[result.entries.length - 1].id;
        setEntries((previous) => {
          if (!result.first) return previous.length ? [] : previous;
          if (!result.entries.length && previous[0]?.id === result.first) return previous;
          return [...previous.filter((entry) => entry.id >= result.first), ...result.entries];
        });
      } catch {
        // The floating surface can be detached while a poll is in flight.
      } finally {
        if (active) timer = setTimeout(poll, 250);
      }
    }
    // Keep the tail current without retransmitting old log entries each time.
    poll();
    return () => { active = false; clearTimeout(timer); };
  }, []);
  useLayoutEffect(() => {
    if (follow && scroll.current) scroll.current.scrollTop = scroll.current.scrollHeight;
  }, [follow, entries]);

  return <main className="floating-logs">
    <header><h1>运行日志</h1>{interactive && <span>浏览</span>}
      <button type="button" aria-pressed={follow} aria-label="跟随最新日志" disabled={!interactive} onClick={() => setFollow(!follow)}>↓</button>
      <button type="button" aria-label="清空日志" disabled={!interactive}
        onClick={() => { nativeRequest('clearLogs').then(() => setFollow(true)).catch(() => {}); }}>清空</button>
      <button type="button" aria-label="关闭日志" disabled={!interactive}
        onClick={() => nativeRequest('closeLogs').catch(() => {})}><Icon name="close" size={15}/></button>
    </header>
    <div className="floating-log-lines" ref={scroll} onWheel={() => setFollow(false)} onTouchMove={() => setFollow(false)}>
      {entries.length ? entries.map((entry) => <div key={entry.id}>{entry.line}</div>) : <p>暂无日志</p>}
    </div>
  </main>;
}

function Advertisement() {
  const state = useNativeStatus('getAdvertisement', 1000);
  return <main className="local-status">
    <h1>广告剩余 {state?.seconds ?? 0} 秒</h1>
    {state?.items?.map((item, index) => item.hasLink
      ? <button type="button" key={index} className="text-button"
          onClick={() => nativeRequest('openAdvertisementLink', { index }).catch(() => {})}>{item.content}</button>
      : <p key={index}>{item.content}</p>)}
    <button type="button" onClick={() => nativeRequest('exitAdvertisement').catch(() => {})}>退出辅助</button>
  </main>;
}

document.documentElement.dataset.surface = SURFACE || '';
const root = createRoot(document.getElementById('root'));
if (SURFACE === 'unsupported') root.render(<main className="local-status"><p>请更新 Android System WebView 后使用设置。</p></main>);
else if (LOCAL && SURFACE === 'authorization') root.render(<AuthorizationActivity/>);
else if (LOCAL && SURFACE === 'logs') root.render(<FloatingLogs/>);
else if (LOCAL && SURFACE === 'advertisement') root.render(<Advertisement/>);
else if (LOCAL && ['activity', 'overlay', 'settings'].includes(SURFACE)) root.render(<LocalSettingsApp/>);
else root.render(<App/>);
