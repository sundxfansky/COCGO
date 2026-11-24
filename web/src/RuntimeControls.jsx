import React, { useEffect, useRef, useState } from 'react';
import { Card, Modal } from './components.jsx';
import { apiRequest, nativeRequest } from './transport.js';

const actionNames = { start: '开始', pause: '暂停', end: '结束', switch: '快速切号' };
const completed = { start: '已开始运行', pause: '辅助已暂停', end: '辅助已结束', switch: '已切号，辅助保持暂停' };

function SwitchButtons({ runtime, disabled, onSwitch }) {
  return <>
    {[[runtime?.previousAccount, '上一个账号'], [runtime?.nextAccount, '下一个账号']].map(([account, label]) =>
      <button key={label} type="button" className="secondary" disabled={disabled || !account}
        onClick={() => onSwitch(account)}>{label}{account ? ` · ${account}` : ''}</button>)}
  </>;
}

export function LocalQuickSwitch() {
  const [runtime, setRuntime] = useState(null);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    let timer;
    async function poll() {
      try {
        const result = await nativeRequest('getRuntimeControls');
        if (active) setRuntime(result);
      } catch {
        if (active) setRuntime(null);
      } finally {
        if (active) timer = setTimeout(poll, 2500);
      }
    }
    poll();
    return () => { active = false; clearTimeout(timer); };
  }, []);
  async function change(account) {
    setError('');
    setWorking(true);
    try {
      // The native session closes settings once the adjacent account has loaded.
      await nativeRequest('switchRuntimeAccount', { account: String(account) }, 120000);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setWorking(false);
    }
  }
  return <Card title="快速切号" icon="users" className="runtime-card">
    <div className="actions"><SwitchButtons runtime={runtime} disabled={working || !runtime?.ready || runtime.busy} onSwitch={change}/></div>
    {working && <p role="status">正在切号…</p>}
    {!working && runtime?.ready && !runtime.previousAccount && !runtime.nextAccount && <p>暂无可切换的相邻账号存档。</p>}
    {error && <p role="alert">{error}</p>}
  </Card>;
}

export default function RemoteRuntimeControls({ device, token }) {
  const [state, setState] = useState(null);
  const [sending, setSending] = useState(false);
  const [confirmEnd, setConfirmEnd] = useState(false);
  const [pollError, setPollError] = useState('');
  const [actionError, setActionError] = useState('');
  const revision = useRef(0);
  const path = `/devices/${encodeURIComponent(device.id)}/control`;

  useEffect(() => {
    let active = true;
    let timer;
    const controller = new AbortController();
    async function poll() {
      const started = revision.current;
      try {
        const result = await apiRequest(token)(path, { signal: controller.signal });
        if (active && started === revision.current) { setState(result); setPollError(''); }
      } catch (error) {
        if (active && started === revision.current) setPollError(error.message);
      } finally {
        if (active) timer = setTimeout(poll, 1500);
      }
    }
    poll();
    return () => { active = false; controller.abort(); clearTimeout(timer); };
  }, [path, token]);

  async function send(action, account) {
    setConfirmEnd(false);
    setSending(true);
    setActionError('');
    revision.current++;
    try {
      const next = await apiRequest(token)(path, {
        method: 'POST', body: JSON.stringify(account ? { action, account } : { action }),
      });
      revision.current++;
      setState(next);
    } catch (error) {
      setActionError(error.message);
    } finally {
      setSending(false);
    }
  }

  const { runtime, command, online } = state || {};
  const pending = command?.status === 'pending' || command?.status === 'running';
  const disabled = Boolean(pollError || sending || !online || !runtime?.ready || runtime.busy || pending);
  const status = !state ? '正在读取设备状态…' : !online ? '设备离线或客户端尚未接入'
    : !runtime.ready ? '客户端正在初始化' : runtime.busy ? '设备忙'
    : runtime.playing ? '运行中' : runtime.autoRun ? '等待自动开始' : '已暂停';
  const outcome = command && {
    pending: `等待设备接收「${actionNames[command.action]}」…`,
    running: `正在执行「${actionNames[command.action]}」…`,
    completed: completed[command.action],
    failed: command.message || '设备操作失败',
    expired: '设备操作超时，请检查当前状态后重试',
  }[command.status];
  return <Card title="运行控制" icon="bolt" className="runtime-card">
    <p role="status">{status}</p>
    <div className="actions">
      <button type="button" disabled={disabled || runtime?.playing} onClick={() => send('start')}>开始</button>
      <button type="button" className="secondary" disabled={disabled || (!runtime?.playing && !runtime?.autoRun)} onClick={() => send('pause')}>暂停</button>
      <button type="button" className="secondary" disabled={disabled} onClick={() => setConfirmEnd(true)}>结束</button>
      <div className="runtime-switch" role="group" aria-label="快速切号">
        <span>快速切号</span>
        <SwitchButtons runtime={runtime} disabled={disabled} onSwitch={(account) => send('switch', account)}/>
      </div>
    </div>
    {(pollError || actionError) && <p role="alert">{pollError || actionError}</p>}
    {outcome && <p role={command.status === 'failed' ? 'alert' : 'status'}>{outcome}</p>}
    {confirmEnd && <Modal title="结束辅助" onClose={() => setConfirmEnd(false)}>
      <p>结束后，{device.name}上的客户端会退出。重新打开客户端后可以继续远程操作。</p>
      <div className="actions"><button type="button" className="secondary" onClick={() => setConfirmEnd(false)}>取消</button>
        <button type="button" disabled={disabled} onClick={() => send('end')}>确认结束</button></div>
    </Modal>}
  </Card>;
}
