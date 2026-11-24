import React, { useEffect, useId, useRef, useState } from 'react';
import { Card, EmbeddedSelect, Modal } from './components.jsx';
import { settingValue } from './model.js';
import { apiRequest, nativeRequest } from './transport.js';
import VillageDetails from './VillageDetails.jsx';
import { LocalQuickSwitch } from './RuntimeControls.jsx';

function AuthorizationLibrary({ setting, config, onChange, disabled, version, onVersion }) {
  const slot = settingValue(config, setting);
  const [status, setStatus] = useState(null);
  const [pending, setPending] = useState(false);
  const valid = /^\d+$/.test(slot) && Number(slot) >= 1 && Number(slot) <= 10000;

  useEffect(() => {
    if (!valid) return;
    let active = true;
    let timer;
    async function refresh() {
      try {
        const result = await nativeRequest('getQqAuthorizationStatus', { slot });
        if (active) setStatus({ ...result, slot });
      } catch (error) {
        if (active) setStatus({ message: error.message, slot });
      } finally {
        if (active) timer = setTimeout(refresh, 1500);
      }
    }
    refresh();
    return () => { active = false; clearTimeout(timer); };
  }, [slot, valid]);

  async function authorize(operation) {
    setPending(true);
    try {
      const result = await nativeRequest('startQqAuthorization', { slot, operation, version });
      if (result.version) onVersion(result.version);
      setStatus({ ...result, slot });
    } catch (error) {
      setStatus({ slot, message: error.message });
    } finally {
      setPending(false);
    }
  }

  const current = status?.slot === slot ? status : null;
  const locked = disabled || pending || current?.busy;
  return <Card title="QQ 全局授权库" icon="users" accent="teal">
    <div className="field-grid">
      <label>{setting.displayName}<input type="number" inputMode="numeric" min="1" max="10000" step="1"
        value={slot} disabled={locked} onChange={(event) => onChange(setting.key, event.target.value)}/></label>
    </div>
    <p role="status">{!valid ? '请输入 1 至 10000 的授权序号' : current?.summary}</p>
    {current?.message && <p role="status">{current.message}</p>}
    <div className="actions">
      <button type="button" disabled={!valid || locked} onClick={() => authorize('capture')}>获取 QQ 授权</button>
      <button type="button" className="secondary" disabled={!valid || locked} onClick={() => authorize('login')}>使用此授权登录</button>
    </div>
    <p>账号 N 使用序号 N 的授权。登录会重置国服账号存档并保留资源文件。</p>
  </Card>;
}

function FeaturePlugins({ configRevision }) {
  const id = useId();
  const [plugins, setPlugins] = useState(null);
  const [selected, setSelected] = useState('login');
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  async function load() {
    setBusy(true);
    try {
      const result = await nativeRequest('getFeaturePlugins');
      setPlugins(result.plugins);
      setSelected((value) => result.plugins.some((item) => item.id === value) ? value : result.plugins[0]?.id || '');
      setMessage('');
    } catch (error) {
      setMessage(error.message);
    } finally {
      setBusy(false);
    }
  }
  useEffect(() => { load(); }, [configRevision]);

  async function change(id, operation) {
    setBusy(true);
    setMessage('');
    try {
      const result = await nativeRequest('changeFeaturePlugin', { id, operation, url: url.trim() }, 150000);
      setPlugins(result.plugins);
      setMessage(result.message);
    } catch (error) {
      setMessage(error.message);
    } finally {
      setBusy(false);
    }
  }

  return <Card title="功能插件" accent="violet">
    {plugins ? <>
      <div className="device-plugin-list">
        {plugins.map((plugin) => <div className="device-plugin" key={plugin.id}>
          <span>{plugin.name}<small>{plugin.version}{plugin.managed ? ' · 服务端配置' : ''}</small></span>
          <button type="button" className="secondary" aria-label={`${plugin.enabled ? '停用' : '启用'}${plugin.name}`}
            disabled={busy || plugin.managed} onClick={() => change(plugin.id, plugin.enabled ? 'disable' : 'enable')}>{plugin.enabled ? '停用' : '启用'}</button>
        </div>)}
      </div>
      {plugins.length > 0 && <>
        <div className="field-grid">
          <div><label htmlFor={id}>选择插件</label><EmbeddedSelect id={id} label="选择插件" value={selected}
            options={plugins.map((plugin) => ({ value: plugin.id, label: plugin.name }))} disabled={busy} onChange={setSelected}/></div>
          <label>插件下载地址<input type="url" value={url} disabled={busy} placeholder="https://…"
            onChange={(event) => setUrl(event.target.value)}/></label>
        </div>
        <div className="actions">
          <button type="button" disabled={busy || !selected || !url.trim()} onClick={() => change(selected, 'install')}>安装插件</button>
          <button type="button" className="secondary" disabled={busy || !selected} onClick={() => change(selected, 'restore')}>恢复内置版本</button>
        </div>
        <p>安装需要 HTTPS 地址及对应签名。插件变更后辅助保持暂停，停用登录插件会影响自动登录和切号。</p>
      </>}
    </> : busy ? <p role="status">正在读取插件…</p> : <button type="button" className="secondary" onClick={load}>重新读取</button>}
    {message && <p role="status">{message}</p>}
  </Card>;
}

function GameSaves({ settings, config, onChange, disabled, readOnly, server = false }) {
  const id = useId();
  const version = settings.extract_version;
  const region = settingValue(config, version) === '1' ? '1' : '0';
  const name = settings[region === '1' ? 'extract_global' : 'extract_cn'];
  const suffix = settingValue(config, name);
  const [confirm, setConfirm] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const options = version.options.map((label, index) => ({ value: String(index), label }));

  async function change(operation, target = { region, suffix }) {
    setConfirm(null);
    setBusy(true);
    setMessage('');
    try {
      const result = await nativeRequest('manageGameSave', { ...target, operation });
      setMessage(result.message);
    } catch (error) {
      setMessage(error.message);
    } finally {
      setBusy(false);
    }
  }

  const locked = disabled || busy;
  return <Card title="存档工具" icon="copy" accent="amber">
    <div className="field-grid">
      <div><label htmlFor={id}>{version.displayName}</label>{server ?
        <select id={id} value={region} disabled={locked} onChange={(event) => onChange(version.key, event.target.value)}>
          {options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select> : <EmbeddedSelect id={id} label={version.displayName} value={region}
          options={options} disabled={locked || readOnly} onChange={(value) => onChange(version.key, value)}/>}</div>
      <label>{name.displayName}<input value={suffix} disabled={locked || readOnly} onChange={(event) => onChange(name.key, event.target.value)}/></label>
    </div>
    {!server && <div className="actions">
      <button type="button" disabled={locked || !suffix} onClick={() => change('extract')}>提取存档</button>
      <button type="button" className="secondary" disabled={locked || !suffix} onClick={() => setConfirm({ region, suffix })}>删除现有存档</button>
    </div>}
    {busy && <p role="status">正在处理存档…</p>}
    {message && <p role="status">{message}</p>}
    {confirm && <Modal title="删除存档" onClose={() => setConfirm(null)}>
      <p>删除{options.find((option) => option.value === confirm.region)?.label}存档「{confirm.suffix}」？</p>
      <div className="actions">
        <button type="button" className="secondary" onClick={() => setConfirm(null)}>取消</button>
        <button type="button" disabled={locked} onClick={() => change('delete', confirm)}>确认删除</button>
      </div>
    </Modal>}
  </Card>;
}

function ScreenshotFeedback({ activity }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  async function submit() {
    setBusy(true);
    setMessage('');
    try {
      const result = await nativeRequest('submitFeedback', {}, 90000);
      setMessage(result.message);
    } catch (error) {
      setMessage(error.message);
    } finally {
      setBusy(false);
    }
  }
  return <Card title="截屏反馈" icon="settings">
    <p>{activity
      ? '提交时会暂时退到上一页面并截屏。请先打开要反馈的页面，提交后再返回这里查看结果。'
      : '请先打开要反馈的页面。提交时设置窗会暂时隐藏，并发送当前屏幕截图。'}</p>
    <div className="actions"><button type="button" disabled={busy} onClick={submit}>{busy ? '正在提交…' : '提交截图'}</button></div>
    {message && <p role="status">{message}</p>}
  </Card>;
}

export default function DeviceTools({ catalog, config, onChange, readOnly, busy, activity, version, onVersion }) {
  const settings = Object.fromEntries(catalog.modules.find((module) => module.scope === 'GLOBAL').settings.map((setting) => [setting.key, setting]));
  const edit = (key, value) => onChange({ ...config, [key]: value });
  const featureRevision = readOnly ? version : null;
  const exportLabel = catalog.modules.flatMap((module) => module.settings)
    .find((setting) => setting.key === 'export_game_json')?.displayName;
  return <div className="device-tools">
    <LocalQuickSwitch/>
    <VillageDetails config={config} exportLabel={exportLabel} canPublish={readOnly}/>
    {!readOnly && settings.qq_account_slot && <AuthorizationLibrary setting={settings.qq_account_slot} config={config}
      onChange={edit} disabled={busy} version={version} onVersion={onVersion}/>}
    <FeaturePlugins configRevision={featureRevision}/>
    {settings.extract_version?.options?.length && settings.extract_cn && settings.extract_global &&
      <GameSaves settings={settings} config={config} onChange={edit} disabled={busy} readOnly={readOnly}/>}
    <ScreenshotFeedback activity={activity}/>
  </div>;
}

const remoteAuthorizationMessages = {
  pending: '正在等待设备接收授权请求…',
  running: '授权工具正在准备二维码…',
  completed: 'QQ 授权已保存在设备。',
  failed: '设备未完成 QQ 授权，请查看设备后重试。',
  expired: 'QQ 授权请求已超时，请重新发起。',
};

function RemoteAuthorization({ setting, config, onChange, disabled, deviceId, token }) {
  const slot = settingValue(config, setting);
  const valid = /^\d+$/.test(slot) && Number(slot) >= 1 && Number(slot) <= 10000;
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState('');
  const [pollError, setPollError] = useState('');
  const endpoint = `/devices/${encodeURIComponent(deviceId)}/qq-authorization`;
  const active = status?.status === 'pending' || status?.status === 'running';
  const qr = !!status?.qr && status.qrUntil * 1000 > Date.now();
  const replyOrder = useRef(0);

  useEffect(() => {
    let mounted = true;
    let timer;
    const controller = new AbortController();
    async function poll() {
      const order = ++replyOrder.current;
      try {
        const result = await apiRequest(token)(endpoint, { signal: controller.signal });
        if (mounted && order === replyOrder.current) {
          setStatus(result);
          setPollError('');
        }
        if (mounted) setLoading(false);
      } catch (failure) {
        if (mounted && order === replyOrder.current) {
          setStatus(null);
          setPollError(failure.message);
          setLoading(false);
        }
      } finally {
        if (mounted) timer = setTimeout(poll, 1500);
      }
    }
    poll();
    return () => { mounted = false; controller.abort(); clearTimeout(timer); };
  }, [token, endpoint]);

  async function start() {
    setStarting(true);
    setError('');
    replyOrder.current++;
    try {
      const result = await apiRequest(token)(endpoint, { method: 'POST', body: JSON.stringify({ slot: Number(slot) }) });
      replyOrder.current++;
      setStatus(result);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setStarting(false);
    }
  }

  const progress = status?.status === 'running' && !qr && status?.qrUntil
    ? '二维码已过期，请在设备上刷新。'
    : remoteAuthorizationMessages[status?.status];
  return <Card title="QQ 授权" icon="users" accent="teal">
    <div className="field-grid">
      <label>{setting.displayName}<input type="number" inputMode="numeric" min="1" max="10000" step="1"
        disabled={disabled || starting} value={slot} onChange={(event) => onChange(setting.key, event.target.value)}/></label>
    </div>
    <div className="actions">
      <button type="button" disabled={!valid || disabled || loading || !!pollError || starting || active} onClick={start}>
        {starting ? '正在发起…' : '获取 QQ 授权'}
      </button>
    </div>
    {qr && <figure className="device-authorization-qr">
      <img src={`data:image/png;base64,${status.qr}`} alt={`授权序号 ${status.slot} 的 QQ 授权二维码`} width="256" height="256"/>
      <figcaption>使用 QQ 扫码，授权将保存到设备序号 {status.slot}。</figcaption>
    </figure>}
    {!qr && progress && <p role="status">{progress}{status?.slot ? `（序号 ${status.slot}）` : ''}</p>}
    {(error || pollError) && <p role="alert">{error || pollError}</p>}
  </Card>;
}

export function ServerDeviceTools({ catalog, config, onChange, busy, deviceId, token }) {
  const settings = Object.fromEntries(catalog.modules.find((module) => module.scope === 'GLOBAL').settings.map((setting) => [setting.key, setting]));
  function edit(key, value) {
    const next = { ...config };
    if (value === undefined) delete next[key];
    else next[key] = value;
    onChange(next);
  }
  return <div className="device-tools">
    <VillageDetails config={config} deviceId={deviceId} token={token}/>
    {catalog.featurePlugins?.length > 0 && <Card title="功能插件" accent="violet">
      <div className="field-grid">
        {catalog.featurePlugins.map((plugin) => {
          const key = `feature_plugin_enabled_${plugin.id}`;
          return <label key={key}>{plugin.name}<select disabled={busy} value={config[key] ?? ''}
            onChange={(event) => edit(key, event.target.value || undefined)}>
            <option value="">沿用设备设置</option><option value="1">启用</option><option value="0">停用</option>
          </select></label>;
        })}
      </div>
    </Card>}
    {settings.extract_version?.options?.length && settings.extract_cn && settings.extract_global &&
      <GameSaves settings={settings} config={config} onChange={edit} disabled={busy} server/>}
    {settings.qq_account_slot &&
      <RemoteAuthorization setting={settings.qq_account_slot} config={config} onChange={edit}
        disabled={busy} deviceId={deviceId} token={token}/>}
  </div>;
}
