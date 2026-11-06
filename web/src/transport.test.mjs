import assert from 'node:assert/strict';
import test from 'node:test';

test('native bridge correlates parallel replies and preserves conflict status without networking', async () => {
  const sent = [];
  globalThis.window = { SunSettings: { postMessage: (body) => sent.push(JSON.parse(body)) } };
  const { isLocal, nativeRequest } = await import('./transport.js');
  assert.equal(isLocal(), true);
  const state = nativeRequest('getState');
  const version = nativeRequest('getVersion');
  window.SunSettings.onmessage({ data: JSON.stringify({ id: sent[1].id, result: { version: 'digest-2', readOnly: true } }) });
  window.SunSettings.onmessage({ data: '{invalid' });
  window.SunSettings.onmessage({ data: JSON.stringify({ id: sent[0].id, result: { version: 'digest-1', config: { account_count: '2' } } }) });
  assert.equal((await state).version, 'digest-1');
  assert.equal((await version).readOnly, true);
  assert.deepEqual(sent.map((item) => item.method), ['getState', 'getVersion']);
  const save = nativeRequest('saveConfig', { config: {}, version: 'digest-1' });
  window.SunSettings.onmessage({ data: JSON.stringify({ id: sent[2].id, error: { message: '版本已更新', status: 409 } }) });
  await assert.rejects(save, (error) => error.status === 409 && error.message === '版本已更新');
});
