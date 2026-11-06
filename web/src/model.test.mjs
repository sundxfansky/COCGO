import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { canApplySnapshot, copyProfiles, countAccounts, fetchChangedConfig, movePriority, normalizeConfig, settingValue, updateSetting, validateConfig } from './model.js';

const schema = JSON.parse(await readFile(new URL('./schema.json', import.meta.url), 'utf8'));
const find = (key) => schema.modules.flatMap((module) => module.settings).find((setting) => setting.key === key);

test('legacy shared profiles migrate from immutable source with account bindings and unknown data retained', () => {
  const input = { account_count: '2', account_config1: '2', account_config2: '1', gold_requirement_c1: '75', gold_requirement_c2: '120', no_builder_base_c2: '1', future_custom_key: 'kept', qq_authorization_slot2: '8' };
  const result = normalizeConfig(input, schema);
  assert.equal(result.gold_requirement_c1, '120');
  assert.equal(result.gold_requirement_c2, '75');
  assert.equal(result.builder_base_enabled_c1, '0');
  assert.equal(result.account_config1, '1');
  assert.equal(result.account_config2, '2');
  assert.equal(result.qq_authorization_slot2, '2');
  assert.equal(result.future_custom_key, 'kept');
  assert.equal(result.account_profiles_version, '1');
  assert.equal(input.gold_requirement_c1, '75');
  assert.deepEqual(normalizeConfig(result, schema), result);
});

test('independent account count changes keep hidden account profiles and numbered defaults', () => {
  const result = normalizeConfig({ account_profiles_version: '1', account_count: '1', gold_requirement_c3: '650', unknown_c3: 'preserved' }, schema);
  const expanded = normalizeConfig({ ...result, account_count: '3' }, schema);
  assert.equal(expanded.gold_requirement_c3, '650');
  assert.equal(expanded.unknown_c3, 'preserved');
  assert.equal(expanded.qq_authorization_slot3, '3');
  assert.equal(settingValue(expanded, find('cn_path'), 'ACCOUNT', 3), '3');
  assert.equal(countAccounts({ account_count: '10001' }, schema), 2);
});

test('copying profiles never copies identity or other device settings and excludes source itself', () => {
  const config = normalizeConfig({ account_profiles_version: '1', account_count: '3', gold_requirement_c1: '200', gold_requirement_c2: '100', trophy_pushing_mode_c1: '1', trophy_pushing_mode_c2: '0', remark1: 'source', remark2: 'target', start_method2: '1', device_remark: 'deviceA', future_setting: 'value' }, schema);
  const main = copyProfiles(config, schema, 1, 1, 2, false);
  assert.equal(main.gold_requirement_c2, '200');
  assert.equal(main.gold_requirement_c1, '200');
  assert.equal(main.trophy_pushing_mode_c2, '0');
  assert.equal(main.remark2, 'target');
  assert.equal(main.start_method2, '1');
  assert.equal(main.device_remark, 'deviceA');
  assert.equal(main.future_setting, 'value');
  assert.equal(config.gold_requirement_c2, '100');
  assert.equal(copyProfiles(config, schema, 1, 2, 3, true).trophy_pushing_mode_c2, '1');
  assert.throws(() => copyProfiles(config, schema, 1, 1, 1, false));
  assert.throws(() => copyProfiles(config, schema, 1, 2, 4, false));
});

test('incompatible modes are isolated to the affected account and international login excludes QQ', () => {
  const config = { trophy_pushing_mode_c1: '1', trophy_pushing_mode_c2: '1', start_method1: '2', start_method2: '2', batch_create_account: '1' };
  const farm = updateSetting(config, 'elixir_cart_farming_c1', '1');
  assert.equal(farm.trophy_pushing_mode_c1, '0');
  assert.equal(farm.trophy_pushing_mode_c2, '1');
  const international = updateSetting(config, 'game_version1', '1');
  assert.equal(international.start_method1, '0');
  assert.equal(international.start_method2, '2');
  assert.equal(updateSetting(config, 'after_kick_option', '1').batch_create_account, '0');
  assert.equal(updateSetting(config, 'batch_create_account', '1').after_kick_option, '0');
});

test('priority reordering uses saved order, assigns unique ranks, and retains toggles', () => {
  const settings = [{ key: 'a_priority', defaultValue: '1' }, { key: 'b_priority', defaultValue: '2' }, { key: 'c_priority', defaultValue: '3' }];
  const config = { a_priority_c2: '3', b_priority_c2: '1', c_priority_c2: '2', a_c2: '0', b_c2: '1', a_priority_c1: '1' };
  const result = movePriority(config, settings, 2, 2, 0);
  assert.equal(result.a_priority_c2, '1');
  assert.equal(result.b_priority_c2, '2');
  assert.equal(result.c_priority_c2, '3');
  assert.equal(result.a_c2, '0');
  assert.equal(result.b_c2, '1');
  assert.equal(result.a_priority_c1, '1');
});

test('steady polls only fetch versions, including already cached conflict versions', async () => {
  const calls = [];
  const request = async (path) => { calls.push(path); return path.endsWith('/version') ? { version: 8 } : { version: 8, config: { account_count: '3' } }; };
  assert.equal(await fetchChangedConfig(request, 8), null);
  assert.deepEqual(calls, ['/config/version']);
  calls.length = 0;
  const changed = await fetchChangedConfig(request, 7);
  assert.equal(changed.version, 8);
  assert.deepEqual(calls, ['/config/version', '/config']);
  calls.length = 0;
  await fetchChangedConfig(request, 8, true);
  assert.deepEqual(calls, ['/config']);
  assert.equal(canApplySnapshot(true, 2, 2), false);
  assert.equal(canApplySnapshot(false, 2, 3), false);
  assert.equal(canApplySnapshot(false, 3, 3), true);
});

test('invalid payloads cannot silently become config, and settings remain strings', () => {
  for (const invalid of [null, [], 'text', { account_count: 3 }, { dangerous: {} }]) assert.throws(() => validateConfig(invalid));
  const config = normalizeConfig({}, schema);
  assert.ok(Object.values(config).every((value) => typeof value === 'string'));
});
