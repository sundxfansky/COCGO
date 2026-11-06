import { readFileSync, readdirSync, writeFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = resolve(web, '../app/src/main/java/com/coc/suncode/jar/ui/schema');
const output = resolve(web, 'src/schema.json');
const check = process.argv.includes('--check');

// Docker builds only receive web/. Repository builds always regenerate from Kotlin.
if (!existsSync(source)) {
  if (check || !existsSync(output)) throw Error('Kotlin schema source is required to verify the schema snapshot.');
  console.log('Using the checked-in Kotlin schema snapshot.');
  process.exit(0);
}

// Preserve quoted text while removing comments, including commented-out settings.
const stripComments = (text) => text.replace(/"(?:\\.|[^"\\])*"|\/\/[^\n]*|\/\*[\s\S]*?\*\//g,
  (match) => match.startsWith('"') ? match : '');
const read = (path) => stripComments(readFileSync(resolve(source, path), 'utf8'));
const aliases = Object.fromEntries([...read('Schema.kt').matchAll(/val\s+(\w+)\s*=\s*(\w+)\s*(?:\r?\n|$)/g)]
  .map((match) => [match[1], match[2]]));
const objects = new Map();
const definitions = new Map();
const literal = '"(?:\\\\.|[^"\\\\])*"';
const definition = new RegExp(`val\\s+(\\w+)\\s*=\\s*SettingDef\\(\\s*(${literal})\\s*,\\s*(${literal})\\s*,\\s*(${literal}|-?\\d+)\\s*,\\s*(${literal})\\s*\\)`, 'g');

for (const file of readdirSync(resolve(source, 'details')).filter((name) => name.endsWith('.kt')).sort()) {
  const text = read(`details/${file}`);
  const starts = [...text.matchAll(/\bobject\s+(\w+)\s*\{/g)];
  starts.forEach((start, index) => {
    const body = text.slice(start.index, starts[index + 1]?.index ?? text.length);
    objects.set(start[1], body);
    for (const match of body.matchAll(definition)) {
      definitions.set(`${start[1]}.${match[1]}`, {
        key: JSON.parse(match[2]), displayName: JSON.parse(match[3]),
        defaultValue: String(JSON.parse(match[4])), numericLiteral: !match[4].startsWith('"'),
      });
    }
  });
}

function settingsFor(name) {
  const body = objects.get(name);
  if (!body) throw Error(`Unknown Kotlin schema object: ${name}`);
  const generated = body.match(/val\s+all\s*=\s*prioritiesFor\((\w+)\.all,/);
  if (generated) return settingsFor(generated[1]).map((item, index) => ({
    key: `${item.key}_priority`, displayName: item.displayName, defaultValue: String(index + 1), numericLiteral: true,
  }));
  const list = body.match(/val\s+all\s*=\s*listOf\(([\s\S]*?)\)/);
  if (!list) throw Error(`Unsupported schema list in ${name}; update the exporter instead of silently omitting fields.`);
  return list[1].split(',').map((entry) => entry.trim()).filter(Boolean).map((entry) => {
    const item = definitions.get(`${name}.${entry}`);
    if (!item) throw Error(`Unsupported setting definition: ${name}.${entry}`);
    return item;
  });
}

// Presentation metadata does not duplicate the canonical labels or defaults.
const options = {
  auto_update: ['关闭自动更新', '仅启动时更新', '实时自动更新'],
  after_kick_option: ['立刻重连', '切号并冷却被顶账号', '原地等待'],
  game_version: ['国服', '国际服'], extract_version: ['国服', '国际服'],
  start_method: ['游戏存档', '直接启动', 'QQ 授权登录'],
  tactics_mode: ['一马当先', '两面夹击', '四面楚歌'],
  research_level: ['满级', '满级减一', '满级减二'],
};
const numeric = new Set(['account_count', 'config_count', 'create_start_id', 'create_end_id', 'consecutive_count',
  'switch_account_after_battles', 'switch_account_after_battles_with_tasks']);
const textFields = new Set(['cn_path', 'global_path', 'data_content', 'clan_tag', 'clan_name', 'clan_join_message',
  'device_remark', 'remark', 'create_prefix', 'email']);
const help = {
  account_count: '每个账号拥有独立的主世界和夜世界配置。减少数量不会删除已保存的其他账号配置。',
  delay_multiplier: '低性能设备建议 1.5–2.5；数值越大，操作之间等待越长。',
  after_kick_cooldown_minutes: '选择切号并冷却时，被顶号的账号在此时间内不会再次登录。',
  trophy_pushing_mode: '上分模式与刷圣水车互斥，开启其中一项会关闭另一项。',
  elixir_cart_farming: '开启后优先获取圣水，不能与上分模式同时开启。',
  speed_up_battle: '主世界部署结束后开启 4 倍速；与部署后重启游戏同时开启时不生效。',
  manual_training: '开启后请手动配兵，辅助不会进入配兵页面，也不会自动开启超级兵。',
  remove_obstacles: '可能移除稀有障碍物，请根据账号需要选择。',
  builder_base_remove_obstacles: '可能移除野蛮人雕像，请根据账号需要选择。',
  sync_include_builder_base: '同步只复制世界配置，不复制账号备注和登录资料。',
  qq_authorization_slot: '账号 N 固定使用设备上的授权库序号 N，授权内容仅由本机管理。',
};

const modules = [...read('SchemaRegistry.kt').matchAll(/ConfigModule\(\s*"(\w+)"\s*,\s*Scope\.(\w+)\s*,\s*Schema\.(\w+)\.all\s*\)/g)].map((match) => ({
  name: match[1], scope: match[2], settings: settingsFor(aliases[match[3]]).map(({ numericLiteral, ...field }) => {
    const kind = options[field.key] ? 'select' : field.key === 'password' ? 'password'
      : field.key.endsWith('_priority') || numeric.has(field.key) ? 'number'
        : numericLiteral && ['0', '1'].includes(field.defaultValue) ? 'toggle'
          : !textFields.has(field.key) && /^\d+(\.\d+)?$/.test(field.defaultValue) ? 'number' : 'text';
    return { ...field, kind, ...(options[field.key] ? { options: options[field.key] } : {}), ...(help[field.key] ? { help: help[field.key] } : {}) };
  }),
}));
if (!modules.length) throw Error('No registered schema modules found.');
const version = createHash('sha256').update(JSON.stringify(modules)).digest('hex').slice(0, 16);
const json = `${JSON.stringify({ schemaVersion: version, modules }, null, 2)}\n`;
if (check) {
  if (!existsSync(output) || readFileSync(output, 'utf8') !== json) throw Error('Schema snapshot is stale; run npm run schema.');
} else writeFileSync(output, json);
console.log(`Schema ${version}: ${modules.length} modules, ${modules.reduce((count, module) => count + module.settings.length, 0)} fields.`);
