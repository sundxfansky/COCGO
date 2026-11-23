import React, { createContext, useContext, useEffect, useId, useMemo, useState } from 'react';
import schema from './schema.json';
import { Card, EmbeddedSelect, Help, Icon, Modal, Toggle } from './components.jsx';
import { copyProfiles, countAccounts, movePriority, normalizeConfig, settingKey, settingValue, updateSetting } from './model.js';
import { isLocal } from './transport.js';

const SchemaContext = createContext(schema);
function useSchema() {
  const catalog = useContext(SchemaContext);
  return useMemo(() => ({
    schema: catalog,
    globalSettings: catalog.modules.find((module) => module.scope === 'GLOBAL').settings,
    accountModule: catalog.modules.find((module) => module.scope === 'ACCOUNT'),
    byKey: Object.fromEntries(catalog.modules.flatMap((module) => module.settings).map((setting) => [setting.key, setting])),
  }), [catalog]);
}

export function acceptRuntimeSchema(runtime) {
  if (!runtime?.modules?.length) return schema;
  // The loaded JAR owns canonical names/defaults; the web bundle supplies presentation metadata.
  const byKey = Object.fromEntries(schema.modules.flatMap((module) => module.settings).map((setting) => [setting.key, setting]));
  return { ...schema, modules: runtime.modules.map((module) => ({ ...module, settings: module.settings.map((setting) => ({ ...byKey[setting.key], ...setting, kind: byKey[setting.key]?.kind || 'text' })) })) };
}
const INTERNAL_GLOBALS = new Set(['config_count', 'account_profiles_version', 'account_count', 'email', 'password', 'sync_source_account', 'sync_target_start', 'sync_target_end', 'sync_include_builder_base']);
const DEVICE_TOOL_KEYS = new Set(['qq_account_slot', 'extract_version', 'extract_cn', 'extract_global']);
const GENERAL_KEYS = new Set(['delay_multiplier', 'enter_game_timer', 'record_progress', 'device_remark', 'runtime_screenshot', 'debug_mode']);
const START_KEYS = new Set(['auto_start', 'auto_update', 'after_kick_option', 'after_kick_cooldown_minutes']);
const BATCH_KEYS = new Set(['batch_create_account', 'create_start_id', 'create_end_id', 'create_prefix', 'add_suffix_setting', 'create_gem_build', 'gem_count']);
const moduleTitles = {
  MAIN_BASE_SETTINGS: ['日常与对战', 'blue'], MAIN_BASE_TROOPS_AND_SPELLS: ['兵种与法术', 'violet'],
  MAIN_BASE_BUILDINGS: ['建筑升级', 'amber'], MAIN_BASE_PETS: ['战宠', 'rose'],
  MAIN_BASE_BUILDING_PRIORITIES: ['建筑优先级', 'amber'], MAIN_BASE_RESEARCH_PRIORITIES: ['研究优先级', 'violet'],
  MAIN_BASE_PET_PRIORITIES: ['战宠优先级', 'rose'], MAIN_BASE_EQUIPMENT_PRIORITIES: ['装备优先级', 'amber'], BUILDER_BASE_SETTINGS: ['夜世界日常', 'violet'],
  BUILDER_BASE_TROOPS: ['夜世界兵种', 'teal'], BUILDER_BASE_BUILDINGS: ['夜世界建筑', 'amber'],
  BUILDER_BASE_BUILDINGS_PRIORITY: ['夜世界建筑优先级', 'amber'],
};
const pairedModules = { MAIN_BASE_BUILDING_PRIORITIES: 'MAIN_BASE_BUILDINGS', MAIN_BASE_RESEARCH_PRIORITIES: 'MAIN_BASE_TROOPS_AND_SPELLS', MAIN_BASE_PET_PRIORITIES: 'MAIN_BASE_PETS', MAIN_BASE_EQUIPMENT_PRIORITIES: 'MAIN_BASE_EQUIPMENTS', BUILDER_BASE_BUILDINGS_PRIORITY: 'BUILDER_BASE_BUILDINGS' };
const equipmentHeroes = [
  ['barbarian_king_', '野蛮人之王'], ['archer_queen_', '弓箭女皇'], ['minion_prince_', '亡灵王子'],
  ['grand_warden_', '大守护者'], ['royal_champion_', '飞盾战神'], ['dragon_duke_', '飞龙公爵'],
];
const pairedNames = new Set(Object.values(pairedModules));
const isPriority = (module) => Boolean(pairedModules[module.name]);
const matches = (setting, search) => !search || `${setting.displayName} ${setting.help || ''}`.toLowerCase().includes(search.toLowerCase());

function Field({ setting, scope = 'GLOBAL', account = 1, config, onChange, disabled }) {
  const fieldId = useId();
  const key = settingKey(setting, scope, account);
  const value = settingValue(config, setting, scope, account);
  const fixed = scope === 'ACCOUNT' && ['account_config', 'qq_authorization_slot'].includes(setting.key);
  const label = setting.displayName;
  const change = (next) => onChange(updateSetting(config, key, next));
  const help = fixed ? '该序号与当前账号保持一致，由客户端自动管理。' : setting.help;
  const options = setting.key === 'start_method' && config[`game_version${account}`] === '1' ? setting.options?.slice(0, 2) : setting.options;
  const choices = options?.map((option, index) => ({ value: String(index), label: typeof option === 'string' ? option : option.label }));
  if (choices?.length && !choices.some((option) => option.value === value)) {
    choices.push({ value, label: `自定义值：${value}` });
  }
  return <div className={`field field-${setting.kind}`}>
    <div className="field-label"><label htmlFor={fieldId}>{label}</label><Help label={`${label}说明`}>{help}</Help></div>
    {setting.kind === 'toggle' ? <Toggle label={label} checked={value === '1'} disabled={disabled || fixed} onChange={(next) => change(next ? '1' : '0')}/> :
      setting.kind === 'select' && choices?.length ? (isLocal() ?
        <EmbeddedSelect id={fieldId} label={label} value={value} options={choices} disabled={disabled || fixed} onChange={change}/> :
        <select id={fieldId} value={value} disabled={disabled || fixed} onChange={(event) => change(event.target.value)}>
          {choices.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>) : <input id={fieldId} type={setting.kind === 'number' ? 'number' : setting.kind === 'password' ? 'password' : 'text'} step="any" value={value} disabled={disabled || fixed} autoComplete="off" onChange={(event) => change(event.target.value)}/>
    }
  </div>;
}

function FieldGrid({ settings, search = '', ...props }) {
  const filtered = settings.filter((setting) => matches(setting, search));
  return <div className="field-grid">{filtered.map((setting) => <Field key={setting.key} setting={setting} {...props}/>)}</div>;
}

function PriorityList({ module, config, onChange, account, disabled, search, equipmentHero }) {
  const { schema } = useSchema();
  const pairedSettings = schema.modules.find((item) => item.name === pairedModules[module.name])?.settings || [];
  const toggles = equipmentHero ? pairedSettings.filter((item) => item.key.startsWith(equipmentHero)) : pairedSettings;
  const ordered = useMemo(() => [...module.settings].sort((a, b) => Number(settingValue(config, a, 'PROFILE', account)) - Number(settingValue(config, b, 'PROFILE', account))), [module, config, account]);
  const selectedToggleKeys = new Set();
  const rows = ordered.map((setting, index) => {
    const toggle = toggles.find((item) => item.key === setting.key.replace(/_priority$/, '') || item.displayName === setting.displayName);
    if (toggle) selectedToggleKeys.add(toggle.key);
    if (!matches(setting, search)) return null;
    return <div className="priority-row" key={setting.key}>
      <span className="rank">{index + 1}</span><span className="priority-name">{setting.displayName}</span>
      {toggle && <Toggle label={`${toggle.displayName}开启状态`} disabled={disabled} checked={settingValue(config, toggle, 'PROFILE', account) === '1'} onChange={(value) => onChange(updateSetting(config, settingKey(toggle, 'PROFILE', account), value ? '1' : '0'))}/>}
      <div className="reorder-actions"><button type="button" className="icon-button" aria-label={`上移${setting.displayName}`} title="上移" disabled={disabled || index === 0} onClick={() => onChange(movePriority(config, module.settings, account, index, index - 1))}>↑</button><button type="button" className="icon-button" aria-label={`下移${setting.displayName}`} title="下移" disabled={disabled || index === ordered.length - 1} onClick={() => onChange(movePriority(config, module.settings, account, index, index + 1))}>↓</button></div>
    </div>;
  });
  return <><div className="priority-list">{rows}</div><FieldGrid settings={toggles.filter((setting) => !selectedToggleKeys.has(setting.key))} search={search} scope="PROFILE" account={account} config={config} onChange={onChange} disabled={disabled}/></>;
}

function EquipmentPriorityList({ module, search, ...props }) {
  return equipmentHeroes.map(([prefix, hero]) => {
    const settings = module.settings.filter((setting) => setting.key.startsWith(prefix));
    if (!settings.some((setting) => matches(setting, search))) return null;
    return <section key={prefix}>
      <h4>{hero}</h4>
      <PriorityList module={{ ...module, settings }} search={search} equipmentHero={prefix} {...props}/>
    </section>;
  });
}

function ProfileSection({ module, ...props }) {
  const [title, accent] = moduleTitles[module.name] || [module.name, 'blue'];
  if (props.search && !module.settings.some((setting) => matches(setting, props.search))) return null;
  return <details className={`card module-card accent-${accent}`} open={props.search ? true : undefined}>
    <summary><span className="card-icon"><Icon name={isPriority(module) ? 'bolt' : 'settings'}/></span><h3>{title}</h3><span className="count-label">{module.settings.length} 项</span><Icon name="arrow" size={16}/></summary>
    <div className="module-body">{isPriority(module) ? <><p className="compact-help">越靠前越优先 <Help>用上下箭头调整顺序。开关决定该项目是否参与，保存后生效。</Help></p>{module.name === 'MAIN_BASE_EQUIPMENT_PRIORITIES' ? <EquipmentPriorityList module={module} {...props}/> : <PriorityList module={module} {...props}/>}</> : <FieldGrid settings={module.settings.filter((setting) => !['main_base_enabled', 'builder_base_enabled', 'no_builder_base'].includes(setting.key))} scope="PROFILE" {...props}/>}</div>
  </details>;
}

function AccountCopy({ config, onChange, disabled, onMessage }) {
  const { schema, byKey } = useSchema();
  const [confirming, setConfirming] = useState(false);
  const settings = ['sync_source_account', 'sync_target_start', 'sync_target_end', 'sync_include_builder_base'].map((key) => byKey[key]).filter(Boolean);
  const value = (key) => settingValue(config, byKey[key]);
  const source = Number(value('sync_source_account'));
  const start = Number(value('sync_target_start'));
  const end = Number(value('sync_target_end'));
  const builder = value('sync_include_builder_base') === '1';
  const count = countAccounts(config, schema);
  const valid = [source, start, end].every((number) => Number.isInteger(number) && number >= 1 && number <= count) && start <= end && !(start === end && source === start);
  return <details className="card copy-card accent-violet"><summary><span className="card-icon"><Icon name="copy"/></span><h3>复制账号配置</h3><Icon name="arrow" size={16}/></summary><div className="module-body">
    <div className="inline-label"><span>同步世界设置</span><Help>复制主世界设置，也可以包含夜世界。账号信息、登录方式和 QQ 授权序号保持独立。目标区间包含首尾，跳过源账号。</Help></div>
    <FieldGrid settings={settings} config={config} onChange={onChange} disabled={disabled}/>
    <button type="button" className="secondary" disabled={disabled || !valid} onClick={() => setConfirming(true)}><Icon name="copy" size={17}/>复制到目标账号</button>
    {!valid && <p className="field-error">请选择有效的账号区间（1–{count}），目标需包含其他账号。</p>}
  </div>{confirming && <Modal title="确认复制配置" onClose={() => setConfirming(false)}><p>用账号 {source} 的{builder ? '主世界与夜世界' : '主世界'}设置覆盖账号 {start}–{end}，跳过源账号。修改将在保存后生效。</p><div className="actions"><button type="button" className="secondary" onClick={() => setConfirming(false)}>取消</button><button type="button" onClick={() => { onChange(copyProfiles(config, schema, source, start, end, builder)); setConfirming(false); onMessage('配置已复制到草稿，请保存使其生效'); }}>确认复制</button></div></Modal>}</details>;
}

function AccountDetail({ account, setAccount, onBack, config, onChange, disabled, search }) {
  const { schema, byKey, accountModule } = useSchema();
  const [world, setWorld] = useState('MAIN_BASE_');
  const count = countAccounts(config, schema);
  const master = byKey[world === 'MAIN_BASE_' ? 'main_base_enabled' : 'builder_base_enabled'];
  const enabled = settingValue(config, master, 'PROFILE', account) === '1';
  const profiles = schema.modules.filter((module) => module.scope === 'PROFILE' && module.name.startsWith(world) && !pairedNames.has(module.name));
  return <>
    <div className="detail-toolbar"><button type="button" className="secondary" onClick={onBack}><Icon name="back" size={17}/>账号列表</button><div className="account-nav"><button type="button" className="icon-button" aria-label="上一个账号" disabled={account <= 1} onClick={() => setAccount(account - 1)}><Icon name="back" size={17}/></button><strong>账号 {account} <span>/ {count}</span></strong><button type="button" className="icon-button" aria-label="下一个账号" disabled={account >= count} onClick={() => setAccount(account + 1)}><Icon name="arrow" size={17}/></button></div></div>
    <Card title="账号信息" icon="users" accent="teal"><FieldGrid settings={accountModule.settings.filter((setting) => setting.key !== 'account_config')} scope="ACCOUNT" account={account} config={config} onChange={onChange} disabled={disabled} search={search}/></Card>
    <div className="world-bar"><div className="segments" aria-label="选择世界">{['MAIN_BASE_', 'BUILDER_BASE_'].map((prefix) => <button type="button" key={prefix} className={world === prefix ? 'selected' : ''} aria-pressed={world === prefix} onClick={() => setWorld(prefix)}>{byKey[prefix === 'MAIN_BASE_' ? 'main_base_enabled' : 'builder_base_enabled'].displayName}</button>)}</div><div className="inline-label"><span>{enabled ? '已开启' : '已暂停'}</span><Toggle label={`${master.displayName}总开关`} checked={enabled} disabled={disabled} onChange={(value) => onChange(updateSetting(config, settingKey(master, 'PROFILE', account), value ? '1' : '0'))}/></div></div>
    <div className="profile-grid">{profiles.map((module) => <ProfileSection key={`${account}-${module.name}`} module={module} config={config} onChange={onChange} account={account} disabled={disabled || !enabled} search={search}/>)}</div>
  </>;
}

function Accounts({ config, onChange, disabled, onMessage, search }) {
  const { schema, byKey } = useSchema();
  const [account, setAccount] = useState(null);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [countDraft, setCountDraft] = useState(config.account_count);
  const count = countAccounts(config, schema);
  useEffect(() => { setCountDraft(String(count)); if (account > count) setAccount(count); }, [count]);
  useEffect(() => { setPage(0); }, [query, count]);
  const filtered = useMemo(() => Array.from({ length: count }, (_, index) => index + 1).filter((index) => `${index} ${config[`remark${index}`] || ''}`.toLowerCase().includes(query.toLowerCase())), [count, config, query]);
  const pages = Math.max(1, Math.ceil(filtered.length / 12));
  const currentPage = Math.min(page, pages - 1);
  function selectAll(invert) {
    const next = { ...config };
    for (let index = 1; index <= count; index++) next[`isopen${index}`] = invert && config[`isopen${index}`] === '1' ? '0' : '1';
    onChange(next);
  }
  if (account) return <AccountDetail account={account} setAccount={setAccount} onBack={() => setAccount(null)} config={config} onChange={onChange} disabled={disabled} search={search}/>;
  return <>
    <Card title="管理账号" accent="teal" icon="users" help="每个账号都有独立的主世界与夜世界设置。调整账号数量不会删除隐藏账号的已有配置。">
      <div className="account-tools"><label className="count-input">{byKey.account_count.displayName}<input type="number" min="1" max="10000" step="1" value={countDraft} disabled={disabled} onChange={(event) => setCountDraft(event.target.value)}/></label><button type="button" className="secondary" disabled={disabled || !Number.isInteger(Number(countDraft)) || Number(countDraft) < 1 || Number(countDraft) > 10000 || Number(countDraft) === count} onClick={() => onChange(normalizeConfig({ ...config, account_count: countDraft }, schema))}>更新数量</button><div className="button-group"><button type="button" className="text-button" disabled={disabled} onClick={() => selectAll(false)}>全部开启</button><button type="button" className="text-button" disabled={disabled} onClick={() => selectAll(true)}>反选</button></div></div>
    </Card>
    <AccountCopy config={config} onChange={onChange} disabled={disabled} onMessage={onMessage}/>
    <div className="list-heading"><div><h2>我的账号 <span className="count-label">{count}</span></h2><span className="muted">{filtered.length} 个匹配账号</span></div><label className="search"><Icon name="search" size={18}/><input aria-label="搜索账号" placeholder="搜索序号或备注" value={query} onChange={(event) => setQuery(event.target.value)}/></label></div>
    <div className="account-grid">{filtered.slice(currentPage * 12, (currentPage + 1) * 12).map((index) => <article className={`account-card ${config[`isopen${index}`] === '1' ? 'enabled' : ''}`} key={index}>
      <div className="account-card-top"><span className="avatar">{String(index).padStart(2, '0')}</span><Toggle label={`账号 ${index}开启状态`} checked={config[`isopen${index}`] === '1'} disabled={disabled} onChange={(value) => onChange(updateSetting(config, `isopen${index}`, value ? '1' : '0'))}/></div>
      <h3>{config[`remark${index}`] || `账号 ${index}`}</h3><p>{byKey.game_version?.options?.[Number(config[`game_version${index}`] || '0')] || '独立账号配置'}</p>
      <button type="button" className="account-open" onClick={() => setAccount(index)}>{disabled ? '查看配置' : '编辑配置'}<Icon name="arrow" size={16}/></button>
    </article>)}</div>
    {filtered.length === 0 && <div className="empty-state"><Icon name="search" size={28}/><h3>没有找到账号</h3><p>试试其他序号或备注。</p></div>}
    <div className="pagination"><button type="button" className="secondary" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>上一页</button><span>{currentPage + 1} / {pages}</span><button type="button" className="secondary" disabled={currentPage + 1 >= pages} onClick={() => setPage(currentPage + 1)}>下一页</button></div>
  </>;
}

function SettingsEditorContent({ config, onChange, readOnly, busy, onMessage, homeContent, deviceTools }) {
  const { globalSettings } = useSchema();
  const tabsId = useId();
  const [tab, setTab] = useState(0);
  const [search, setSearch] = useState('');
  const disabled = readOnly || busy;
  const groups = [
    { title: '运行偏好', icon: 'settings', accent: 'blue', settings: globalSettings.filter((setting) => GENERAL_KEYS.has(setting.key)) },
    { title: '启动与更新', icon: 'bolt', accent: 'amber', settings: globalSettings.filter((setting) => START_KEYS.has(setting.key)) },
    { title: '批量创建', icon: 'users', accent: 'violet', settings: globalSettings.filter((setting) => BATCH_KEYS.has(setting.key)) },
    { title: '其他设置', icon: 'settings', accent: 'teal', settings: globalSettings.filter((setting) => !INTERNAL_GLOBALS.has(setting.key) && !GENERAL_KEYS.has(setting.key) && !START_KEYS.has(setting.key) && !BATCH_KEYS.has(setting.key) && !(deviceTools && DEVICE_TOOL_KEYS.has(setting.key))) },
  ];
  return <>
    <div className="navigation-row"><div className="tabs" role="tablist" aria-label="设置页面">{[{ label: '主页设置', icon: 'home' }, { label: '账号设置', icon: 'users' }].map((item, index) => <button type="button" key={item.label} id={`${tabsId}-tab-${index}`} role="tab" aria-selected={tab === index} aria-controls={`${tabsId}-panel-${index}`} tabIndex={tab === index ? 0 : -1} onClick={() => setTab(index)} onKeyDown={(event) => { if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) { event.preventDefault(); const next = event.key === 'Home' ? 0 : event.key === 'End' ? 1 : 1 - tab; setTab(next); document.getElementById(`${tabsId}-tab-${next}`)?.focus(); } }}><Icon name={item.icon} size={18}/>{item.label}</button>)}</div><label className="search setting-search"><Icon name="search" size={18}/><input value={search} aria-label="搜索设置项" placeholder="搜索设置项" onChange={(event) => setSearch(event.target.value)}/>{search && <button type="button" aria-label="清空设置搜索" className="icon-button" onClick={() => setSearch('')}><Icon name="close" size={14}/></button>}</label></div>
    <div role="tabpanel" id={`${tabsId}-panel-${tab}`} aria-labelledby={`${tabsId}-tab-${tab}`} className="tab-panel">
      {tab === 0 ? <>{homeContent}<div className="home-grid">{groups.filter((group) => group.settings.some((setting) => matches(setting, search))).map((group) => <Card key={group.title} title={group.title} icon={group.icon} accent={group.accent}><FieldGrid settings={group.settings} config={config} onChange={onChange} disabled={disabled} search={search}/></Card>)}</div>{search && !groups.some((group) => group.settings.some((setting) => matches(setting, search))) && <div className="empty-state"><p>没有匹配的主页设置。账号内设置可以在账号详情中搜索。</p></div>}</> : <Accounts config={config} onChange={onChange} disabled={disabled} onMessage={onMessage} search={search}/>}
    </div>
  </>;
}

export default function SettingsEditor({ catalog = schema, ...props }) {
  return <SchemaContext.Provider value={catalog}><SettingsEditorContent {...props}/></SchemaContext.Provider>;
}
