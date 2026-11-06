// Configuration keys and labels come from the Kotlin schema; values stay strings on every transport.
export function validateConfig(value) {
  if (!value || Array.isArray(value) || typeof value !== 'object' || Object.values(value).some((item) => typeof item !== 'string')) {
    throw Error('配置必须是键和值均为字符串的对象');
  }
  return value;
}

export function countAccounts(config, schema) {
  const fallback = schema.modules.find((module) => module.scope === 'GLOBAL')?.settings.find((setting) => setting.key === 'account_count')?.defaultValue || '2';
  const count = Number(config.account_count ?? fallback);
  return Number.isInteger(count) && count >= 1 && count <= 10000 ? count : Number(fallback);
}

export function settingKey(setting, scope, account) {
  return scope === 'ACCOUNT' ? `${setting.key}${account}` : scope === 'PROFILE' ? `${setting.key}_c${account}` : setting.key;
}

export function settingValue(config, setting, scope = 'GLOBAL', account = 1) {
  const numbered = scope === 'ACCOUNT' && ['global_path', 'cn_path', 'data_content', 'qq_authorization_slot', 'account_config'].includes(setting.key);
  return config[settingKey(setting, scope, account)] ?? (numbered ? String(account) : String(setting.defaultValue));
}

export function normalizeConfig(value, schema) {
  const original = validateConfig(value);
  const config = { ...original };
  const count = countAccounts(config, schema);
  // Copy legacy shared profiles from the immutable source before marking account ownership.
  const independent = original.account_profiles_version === '1';
  const profiles = schema.modules.filter((module) => module.scope === 'PROFILE').flatMap((module) => module.settings);
  for (let account = 1; account <= count; account++) {
    if (!independent) {
      const assigned = Number(original[`account_config${account}`]);
      const source = Number.isInteger(assigned) && assigned > 0 ? assigned : 1;
      for (const setting of profiles) {
        const stored = original[`${setting.key}_c${source}`];
        if (stored !== undefined) config[`${setting.key}_c${account}`] = stored;
        else delete config[`${setting.key}_c${account}`];
      }
      if (original[`builder_base_enabled_c${source}`] === undefined) {
        config[`builder_base_enabled_c${account}`] = original[`no_builder_base_c${source}`] === '1' ? '0' : '1';
      }
    }
    config[`account_config${account}`] = String(account);
    config[`qq_authorization_slot${account}`] = String(account);
    if (config[`game_version${account}`] === '1' && config[`start_method${account}`] === '2') config[`start_method${account}`] = '0';
  }
  if (config.after_kick_cooldown_minutes === undefined && original.after_kick_wait_minutes !== undefined) {
    config.after_kick_cooldown_minutes = original.after_kick_wait_minutes;
  }
  config.account_count = String(count);
  config.config_count = String(count);
  config.account_profiles_version = '1';
  return config;
}

export function updateSetting(config, key, value) {
  const next = { ...config, [key]: String(value) };
  if (key === 'after_kick_option' && value !== '0') next.batch_create_account = '0';
  if (key === 'batch_create_account' && value === '1') next.after_kick_option = '0';
  // These farming modes cannot run at the same time for the same account.
  const match = key.match(/^(trophy_pushing_mode|elixir_cart_farming)(_c\d+)$/);
  if (match && value === '1') next[`${match[1] === 'trophy_pushing_mode' ? 'elixir_cart_farming' : 'trophy_pushing_mode'}${match[2]}`] = '0';
  const gameVersion = key.match(/^game_version(\d+)$/);
  if (gameVersion && value === '1' && next[`start_method${gameVersion[1]}`] === '2') next[`start_method${gameVersion[1]}`] = '0';
  return next;
}

export function copyProfiles(config, schema, source, start, end, includeBuilder) {
  const count = countAccounts(config, schema);
  if (![source, start, end].every((value) => Number.isInteger(value) && value >= 1 && value <= count) || start > end || (start === end && start === source)) {
    throw Error(`请选择 1 至 ${count} 之间的有效账号区间，目标需包含其他账号`);
  }
  const next = { ...config };
  const modules = schema.modules.filter((module) => module.scope === 'PROFILE' && (module.name.startsWith('MAIN_BASE_') || includeBuilder));
  for (let account = start; account <= end; account++) {
    if (account === source) continue;
    for (const module of modules) for (const setting of module.settings) {
      next[settingKey(setting, 'PROFILE', account)] = settingValue(config, setting, 'PROFILE', source);
    }
  }
  return next;
}

export function canApplySnapshot(dirty, startedRevision, currentRevision) {
  return !dirty && startedRevision === currentRevision;
}

export async function fetchChangedConfig(request, currentVersion, force = false) {
  if (!force) {
    const summary = await request('/config/version');
    if (String(summary.version) === String(currentVersion)) return null;
  }
  return request('/config');
}

export function movePriority(config, settings, account, from, to) {
  if (from < 0 || to < 0 || from >= settings.length || to >= settings.length) return config;
  const ordered = [...settings].sort((a, b) => Number(settingValue(config, a, 'PROFILE', account)) - Number(settingValue(config, b, 'PROFILE', account)));
  const [item] = ordered.splice(from, 1);
  ordered.splice(to, 0, item);
  const next = { ...config };
  ordered.forEach((setting, index) => { next[settingKey(setting, 'PROFILE', account)] = String(index + 1); });
  return next;
}
