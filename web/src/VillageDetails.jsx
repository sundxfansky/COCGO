import React, { useEffect, useId, useMemo, useState } from 'react';
import catalog from './coc-catalog.json';
import { Card, EmbeddedSelect, Icon } from './components.jsx';
import { apiRequest, nativeRequest } from './transport.js';

const groupLabels = {
  buildings: '主世界建筑', units: '主世界兵种', heroes: '主世界英雄', spells: '法术',
  traps: '主世界陷阱', siege_machines: '攻城机器', pets: '战宠', equipment: '英雄装备',
  helpers: '助手', guardians: '守护者', buildings2: '夜世界建筑', units2: '夜世界兵种',
  heroes2: '夜世界英雄', traps2: '夜世界陷阱', decos: '主世界装饰', obstacles: '主世界障碍物',
  decos2: '夜世界装饰', obstacles2: '夜世界障碍物', house_parts: '部落小屋部件',
  skins: '主世界皮肤', skins2: '夜世界皮肤', sceneries: '主世界场景', sceneries2: '夜世界场景',
};

const boostLabels = {
  builder_boost: '建筑工人药水', builder_consumable: '建筑工人零食', lab_boost: '研究药水',
  lab_consumable: '研究零食', pet_boost: '战宠药水', clocktower_boost: '时光钟楼加速',
  clocktower_cooldown: '时光钟楼冷却',
};

const slotKey = ({ account, region }) => `${region}/${account}`;

function formatDuration(seconds) {
  const units = [[86400, '天'], [3600, '小时'], [60, '分钟'], [1, '秒']];
  return units.map(([size, unit], index) => {
    const count = Math.floor(seconds / size) % (index ? units[index - 1][0] / size : Infinity);
    return count ? `${count}${unit}` : '';
  }).filter(Boolean).slice(0, 2).join(' ');
}

function artwork(item, level) {
  // A level that lacks art keeps the nearest available illustration for the same item.
  if (!item) return undefined;
  if (item.levels?.[level] !== undefined) return item.levels[level];
  if (item.icon !== undefined) return item.icon;
  if (!item.levels) return undefined;
  const levels = Object.keys(item.levels).map(Number);
  const closest = levels.reduce((best, next) => Math.abs(next - level) < Math.abs(best - level) ? next : best);
  return item.levels[closest];
}

function Artwork({ tile }) {
  if (tile === undefined) return <span className="village-art village-art-missing"><Icon name="home" size={23}/></span>;
  const grid = catalog.grid;
  const cell = tile % (grid * grid);
  const path = `${import.meta.env.BASE_URL}coc-village/${catalog.edition}/${Math.floor(tile / (grid * grid))}.webp`;
  return <span className="village-art" aria-hidden="true" style={{
    backgroundImage: `url("${path}")`, backgroundSize: `${grid * 100}%`,
    backgroundPosition: `${(cell % grid) * 100 / (grid - 1)}% ${Math.floor(cell / grid) * 100 / (grid - 1)}%`,
  }}/>;
}

function itemName(id) {
  return catalog.items[id]?.zh || catalog.items[id]?.name || `未知项目 #${id}`;
}

function VillageItem({ item }) {
  const identity = catalog.items[item.id];
  const info = [
    item.level !== undefined && `${item.level} 级`,
    item.count > 1 && `×${item.count}`,
    item.weapon && `武器 ${item.weapon} 级`,
    item.gearUp && '已改装',
    item.parent && itemName(item.parent),
  ].filter(Boolean);
  return <li className="village-item">
    <Artwork tile={artwork(identity, item.level ?? 1)}/>
    <div><strong>{itemName(item.id)}</strong>
      {info.length > 0 && <small>{info.join(' · ')}</small>}
      {item.seconds > 0 && <small className="village-timer">导出时剩余 {formatDuration(item.seconds)}</small>}
      {item.cooldown > 0 && <small className="village-timer">导出时冷却 {formatDuration(item.cooldown)}</small>}
    </div>
  </li>;
}

function VillageSnapshot({ snapshot }) {
  const [group, setGroup] = useState('');
  const [search, setSearch] = useState('');
  const filterId = useId();
  const query = search.trim().toLowerCase();
  const groups = useMemo(() => {
    const entries = new Map();
    for (const item of snapshot.items) {
      if (!entries.has(item.group)) entries.set(item.group, []);
      entries.get(item.group).push(item);
    }
    return entries;
  }, [snapshot]);
  const upgrades = snapshot.items.filter((item) => item.seconds > 0).length;
  const options = [
    { value: '', label: '全部分类' },
    ...(upgrades ? [{ value: 'upgrades', label: `升级中（${upgrades}）` }] : []),
    ...Array.from(groups, ([key, items]) => ({ value: key, label: `${groupLabels[key] || key}（${items.length}）` })),
  ];
  const filtered = [];
  for (const [key, values] of groups) {
    if (group && group !== 'upgrades' && group !== key) continue;
    const visible = values.filter((item) => {
      if (group === 'upgrades' && !item.seconds) return false;
      const name = catalog.items[item.id];
      return !query || `${item.id} ${name?.name || ''} ${name?.zh || ''}`.toLowerCase().includes(query);
    });
    if (visible.length) filtered.push([key, visible]);
  }
  return <div className="village-snapshot">
    <dl className="village-summary">
      {snapshot.tag && <div><dt>玩家标签</dt><dd>{snapshot.tag}</dd></div>}
      {snapshot.townHall > 0 && <div><dt>大本营</dt><dd>{snapshot.townHall} 级</dd></div>}
      {snapshot.builderHall > 0 && <div><dt>建筑大师大本营</dt><dd>{snapshot.builderHall} 级</dd></div>}
      {snapshot.timestamp > 0 && <div><dt>导出时间</dt><dd>
        <time dateTime={new Date(snapshot.timestamp * 1000).toISOString()}>
          {new Date(snapshot.timestamp * 1000).toLocaleString('zh-CN', { hour12: false })}
        </time>
      </dd></div>}
    </dl>
    {snapshot.boosts && <details className="village-boosts"><summary>导出时的加速与冷却</summary>
      <ul>{Object.entries(snapshot.boosts).map(([key, seconds]) =>
        <li key={key}>{boostLabels[key] || key} · {formatDuration(seconds)}</li>)}</ul>
    </details>}
    <div className="field-grid village-filters">
      <div><label htmlFor={filterId}>分类</label><EmbeddedSelect id={filterId} label="村庄分类"
        value={group} options={options} onChange={setGroup}/></div>
      <label>搜索<input type="search" value={search} placeholder="名称或游戏 ID"
        onChange={(event) => setSearch(event.target.value)}/></label>
    </div>
    {filtered.map(([key, values]) => <section className="village-group" key={key}>
      <h3>{groupLabels[key] || key}</h3>
      <ul className="village-items">{values.map((item, index) =>
        <VillageItem key={`${item.id}-${index}`} item={item}/>)}</ul>
    </section>)}
    {!filtered.length && <p role="status">没有匹配的村庄项目。</p>}
  </div>;
}

export default function VillageDetails({ config, exportLabel, canPublish, deviceId, token }) {
  const local = !deviceId;
  const endpoint = local ? null : `/devices/${encodeURIComponent(deviceId)}/villages`;
  const pickerId = useId();
  const [options, setOptions] = useState([]);
  const [selected, setSelected] = useState('');
  const [snapshot, setSnapshot] = useState(null);
  const [revision, setRevision] = useState(0);
  const [loadingList, setLoadingList] = useState(true);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [listError, setListError] = useState('');
  const [detailMessage, setDetailMessage] = useState(null);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setLoadingList(true);
    setListError('');
    const load = local ? nativeRequest('listVillageExports') : apiRequest(token)(endpoint, { signal: controller.signal });
    load.then(({ exports }) => {
      if (!active) return;
      setOptions(exports);
      setSelected((previous) => exports.some((slot) => slotKey(slot) === previous)
        ? previous : (exports.length ? slotKey(exports[0]) : ''));
    }).catch((error) => {
      if (active) setListError(error.status === 404 && !local ? '无法读取村庄列表，请确认远程服务已更新。' : error.message);
    }).finally(() => { if (active) setLoadingList(false); });
    return () => { active = false; controller.abort(); };
  }, [local, token, endpoint, revision]);

  useEffect(() => {
    setSnapshot(null);
    setDetailMessage(null);
    if (!selected) {
      setLoadingDetail(false);
      return;
    }
    let active = true;
    const controller = new AbortController();
    const [region, account] = selected.split('/');
    setLoadingDetail(true);
    const load = local ? nativeRequest('getVillageExport', { region, account })
      : apiRequest(token)(`${endpoint}/${selected}`, { signal: controller.signal });
    load.then((result) => { if (active) setSnapshot(result); })
      .catch((error) => { if (active) setDetailMessage({ text: error.message, error: true }); })
      .finally(() => { if (active) setLoadingDetail(false); });
    return () => { active = false; controller.abort(); };
  }, [selected, local, endpoint, token, revision]);

  async function publish() {
    const [region, account] = selected.split('/');
    setPublishing(true);
    setDetailMessage(null);
    try {
      setSnapshot(await nativeRequest('publishVillageExport', { region, account }));
      setDetailMessage({ text: '村庄详情已发布到远程配置页面。' });
    } catch (error) {
      setDetailMessage({ text: error.status === 404 ? '发布失败，请检查本地导出和服务端版本。' : error.message, error: true });
    } finally {
      setPublishing(false);
    }
  }

  const labels = options.map((slot) => {
    const name = config[`remark${slot.account}`]?.trim();
    return { value: slotKey(slot), label: `${slot.region === 'cn' ? '国服' : '国际服'} · 账号 ${slot.account}${name ? ` · ${name}` : ''}` };
  });
  return <Card title="村庄详情" icon="home" accent="amber" className="village-card">
    <div className="village-controls">
      {labels.length > 0 && <div><label htmlFor={pickerId}>选择游戏账号</label><EmbeddedSelect id={pickerId}
        label="选择游戏账号" value={selected} options={labels} disabled={publishing || loadingList}
        onChange={(slot) => { setSnapshot(null); setSelected(slot); }}/></div>}
      <div className="village-actions">
        <button type="button" className="secondary" disabled={loadingList || publishing}
          onClick={() => setRevision((value) => value + 1)}><Icon name="refresh" size={15}/>刷新</button>
        {local && snapshot && canPublish && <button type="button" disabled={publishing || loadingDetail}
          onClick={publish}>{publishing ? '正在发布…' : '发布到远程'}</button>}
      </div>
    </div>
    {loadingList && <p role="status">正在读取游戏导出列表…</p>}
    {listError && <p role="alert">{listError}</p>}
    {!loadingList && !listError && !labels.length && <p>{local
      ? (exportLabel ? `还没有村庄数据。请在游戏账号设置中启用「${exportLabel}」，并运行该账号。` : '还没有村庄数据。')
      : '还没有已发布的村庄数据。请在设备的「设备工具 → 村庄详情」选择账号并发布。'}</p>}
    {selected && loadingDetail && <p role="status">正在读取村庄详情…</p>}
    {detailMessage && <p role={detailMessage.error ? 'alert' : 'status'}>{detailMessage.text}</p>}
    {selected && snapshot && !loadingDetail && <VillageSnapshot key={selected} snapshot={snapshot}/>}
  </Card>;
}
