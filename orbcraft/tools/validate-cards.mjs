#!/usr/bin/env node
/**
 * 卡表校验器 —— 六条质量护栏 + 池子抽查
 *
 * 为什么需要它：铺满 450 条之后，「族 × 载体」展开会自然产生
 *   ① ID 拼错 / 重复（静默覆盖，卡池凭空变少）
 *   ② 冷门载体只铺了 6 条（池子抽空，玩家看到 3 张牌）
 *   ③ 协同引用了一个不存在的族（永远不触发，且不报错）
 *   ④ 同一层里出现三张「伤害 +2/+3/+4」（同族重复，等于把选项复制三遍）
 * 这四类问题在运行时都不会报错，只会在玩家体验里体现成「这游戏怪怪的」。
 *
 * 用法：node tools/validate-cards.mjs
 */

import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const DATA_DIR = join(__dirname, '..', 'core', 'src', 'main', 'resources', 'data');

const MIN_POOL_PER_CARRIER = 8;   // 层 3 / 层 4 每个载体的候选词条下限
const MIN_FAMILY_PER_CARRIER = 8; // 去同族后仍需 ≥ 8 个可用族（抽 5 张不重复）
const DRAW_SIZE = 5;

const errors = [];
const warnings = [];
const err = (code, msg) => errors.push({ code, msg });
const warn = (code, msg) => warnings.push({ code, msg });

// 引用形如 "elem.lightning" / "attr.fireRate" / "effect.chain"，取最后一段即族名
const stripRef = (ref) => String(ref).split('.').pop();

function load(name) {
  try {
    return JSON.parse(readFileSync(join(DATA_DIR, name), 'utf8'));
  } catch (e) {
    err('LOAD_FAIL', `${name} 读取失败：${e.message}`);
    return { affixes: [], links: [], cards: [] };
  }
}

/* ------------------------------------------------------------------ */
/* 加载                                                                */
/* ------------------------------------------------------------------ */

const files = [
  'affixes.carrier.json',
  'affixes.element.json',
  'affixes.attribute.json',
  'affixes.effect.json',
  'synergy.json',
  'target_cards.json',
];

console.log('校验卡表 → ' + DATA_DIR + '\n');
for (const f of files) {
  if (!readdirSync(DATA_DIR).includes(f)) err('MISSING_FILE', `缺少数据文件 ${f}`);
}

const carrierDoc = load('affixes.carrier.json');
const elementDoc = load('affixes.element.json');
const attributeDoc = load('affixes.attribute.json');
const effectDoc = load('affixes.effect.json');
const synergyDoc = load('synergy.json');
const targetDoc = load('target_cards.json');

const all = [
  ...(carrierDoc.affixes ?? []),
  ...(elementDoc.affixes ?? []),
  ...(attributeDoc.affixes ?? []),
  ...(effectDoc.affixes ?? []),
];

// carrier_allow / element_allow 存的是裸 id（gun / fire），
// 而 family 带层级前缀（carrier.gun / elem.fire），这里要分开取
const carriers = new Set((carrierDoc.affixes ?? []).map((a) => a.id.replace(/^carrier\./, '')));
const elements = new Set((elementDoc.affixes ?? []).map((a) => a.id.replace(/^elem\./, '')));
const families = new Set(all.map((a) => a.family));
const attrFamilies = new Set((attributeDoc.affixes ?? []).map((a) => a.family));
const effectFamilies = new Set((effectDoc.affixes ?? []).map((a) => a.family));

/* ------------------------------------------------------------------ */
/* 护栏 1：id 全局唯一                                                  */
/* ------------------------------------------------------------------ */

const seen = new Map();
for (const a of all) {
  if (!a.id) { err('NO_ID', `存在无 id 的词条：${JSON.stringify(a).slice(0, 80)}`); continue; }
  if (seen.has(a.id)) err('DUP_ID', `id 重复：${a.id}（后者会静默覆盖前者）`);
  seen.set(a.id, a);
}

/* ------------------------------------------------------------------ */
/* 护栏 2：carrier_allow / element_allow 引用完整                        */
/* ------------------------------------------------------------------ */

for (const a of all) {
  for (const c of a.carrier_allow ?? []) {
    if (!carriers.has(c)) err('BAD_CARRIER', `${a.id} 的 carrier_allow 指向不存在的载体 "${c}"`);
  }
  for (const c of a.carrier_deny ?? []) {
    if (!carriers.has(c)) err('BAD_CARRIER', `${a.id} 的 carrier_deny 指向不存在的载体 "${c}"`);
  }
  for (const e of a.element_allow ?? []) {
    if (!elements.has(e)) err('BAD_ELEMENT', `${a.id} 的 element_allow 指向不存在的元素 "${e}"`);
  }
  for (const x of a.excludes ?? []) {
    if (!families.has(x)) err('BAD_EXCLUDE', `${a.id} 的 excludes 引用了不存在的族 "${x}"`);
  }
}

/* ------------------------------------------------------------------ */
/* 护栏 3：SynergyLink 引用完整 + 无自环                                 */
/* ------------------------------------------------------------------ */

const links = synergyDoc.links ?? [];
for (const [i, l] of links.entries()) {
  for (const side of ['from', 'to']) {
    const ref = l[side];
    if (!families.has(ref)) {
      err('BAD_SYNERGY', `synergy[${i}] ${side}="${ref}" 引用了不存在的族（该协同永远不触发）`);
    }
  }
  if (l.from === l.to) err('SELF_SYNERGY', `synergy[${i}] 自我协同：${l.from}`);
}

// 同一对 (from,to) 出现两次是纯冗余，会让 UI 出现两行一样的提示
const pairKey = (l) => [l.from, l.to].sort().join('|');
const pairSeen = new Map();
for (const l of links) {
  const k = pairKey(l);
  if (pairSeen.has(k)) err('DUP_SYNERGY', `协同重复：${k}（出现 ${pairSeen.get(k) + 1} 次）`);
  pairSeen.set(k, (pairSeen.get(k) ?? 0) + 1);
}

/* ------------------------------------------------------------------ */
/* 护栏 4/5：四档点数与强度系数严格                                     */
/* ------------------------------------------------------------------ */

const TIER_SPEC = { WHITE: [1, 1.0], BLUE: [2, 1.6], GOLD: [4, 2.5], RED: [7, 4.0] };
for (const a of all) {
  // 四层全部必须有品质档：载体/元素没有品质的话，4 层全红只能到 14 点，
  // 「17–28 点 = 传说」永远达不到（见 docs §29.9）
  if (!Array.isArray(a.tiers) || a.tiers.length !== 4) {
    err('BAD_TIERS', `${a.id} 的品质档应为 4 个，实际 ${a.tiers?.length ?? 0}`);
    continue;
  }
  for (const t of a.tiers) {
    const spec = TIER_SPEC[t.tier];
    if (!spec) { err('BAD_TIER_NAME', `${a.id} 出现未知品质 "${t.tier}"`); continue; }
    if (t.points !== spec[0]) err('BAD_POINTS', `${a.id}[${t.tier}] 点数应为 ${spec[0]}，实际 ${t.points}`);
    if (t.magnitude !== spec[1]) err('BAD_MAGNITUDE', `${a.id}[${t.tier}] 系数应为 ${spec[1]}，实际 ${t.magnitude}`);
    if ((t.tier === 'GOLD' || t.tier === 'RED') && !t.drawback) {
      err('NO_DRAWBACK', `${a.id}[${t.tier}] 缺代价（金红卡必须带代价，否则抽卡变成无脑点高品）`);
    }
    if ((t.tier === 'WHITE' || t.tier === 'BLUE') && t.drawback) {
      warn('EARLY_DRAWBACK', `${a.id}[${t.tier}] 白蓝卡不应带代价`);
    }
    if (!t.desc) err('NO_DESC', `${a.id}[${t.tier}] 缺文案`);
  }
}

/* ------------------------------------------------------------------ */
/* 护栏 6：池子容量抽查（每个载体 × 每层）                               */
/* ------------------------------------------------------------------ */

const attributes = attributeDoc.affixes ?? [];
const effects = effectDoc.affixes ?? [];

function pool(layerAffixes, carrier, element) {
  return layerAffixes.filter((a) => {
    if (!a.carrier_allow.includes(carrier)) return false;
    if (a.element_allow.length && element && !a.element_allow.includes(element)) return false;
    return true;
  });
}

const rows = [];
let worstPool = Infinity;
let worstFamily = Infinity;

for (const c of carriers) {
  const p3 = pool(attributes, c, null);
  const p4 = pool(effects, c, null);
  const f3 = new Set(p3.map((a) => a.family)).size;
  const f4 = new Set(p4.map((a) => a.family)).size;
  rows.push({ carrier: c, attr: p3.length, attrFam: f3, eff: p4.length, effFam: f4 });
  worstPool = Math.min(worstPool, p3.length, p4.length);
  worstFamily = Math.min(worstFamily, f3, f4);
  if (p3.length < MIN_POOL_PER_CARRIER) err('EMPTY_POOL', `载体 ${c} 的属性池只有 ${p3.length} 条（下限 ${MIN_POOL_PER_CARRIER}）`);
  if (p4.length < MIN_POOL_PER_CARRIER) err('EMPTY_POOL', `载体 ${c} 的效果池只有 ${p4.length} 条（下限 ${MIN_POOL_PER_CARRIER}）`);
  if (f3 < MIN_FAMILY_PER_CARRIER) err('THIN_FAMILY', `载体 ${c} 的属性可用族只有 ${f3} 个（去同族后抽不满 ${DRAW_SIZE} 张）`);
  if (f4 < MIN_FAMILY_PER_CARRIER) err('THIN_FAMILY', `载体 ${c} 的效果可用族只有 ${f4} 个`);
}

// 元素限定下的池子：确认最窄组合也够发 5 张
for (const c of carriers) {
  for (const e of elements) {
    const p3 = new Set(pool(attributes, c, e).map((a) => a.family)).size;
    if (p3 > 0 && p3 < DRAW_SIZE) {
      warn('NARROW_ELEMENT_POOL', `载体 ${c} + 元素 ${e} 的属性族仅 ${p3} 个`);
    }
  }
}

/* ------------------------------------------------------------------ */
/* 定向卡引用                                                           */
/* ------------------------------------------------------------------ */

const targets = targetDoc.cards ?? [];
for (const t of targets) {
  const id = t.target_affix;
  if (id === null) continue; // 通用卡，开卡时再选
  if (!seen.has(id)) err('BAD_TARGET', `定向卡 ${t.id} 指向不存在的词条 "${id}"`);
  const layerOk = { CARRIER: 'CARRIER', ELEMENT: 'ELEMENT' }[t.target_layer];
  if (!layerOk) err('BAD_TARGET_LAYER', `定向卡 ${t.id} 的 target_layer 非法：${t.target_layer}`);
  if (t.quality_floor !== 'NONE') warn('TARGET_FLOOR', `定向卡 ${t.id} 设了品质下限（首版应一律 NONE）`);
}

/* ------------------------------------------------------------------ */
/* 报告                                                                */
/* ------------------------------------------------------------------ */

console.log('【池子容量】每个载体的可用词条 / 去同族后可用族数');
console.log('载体       属性  族   效果  族');
for (const r of rows) {
  console.log(
    `${r.carrier.padEnd(10)} ${String(r.attr).padStart(4)} ${String(r.attrFam).padStart(3)}  ${String(r.eff).padStart(4)} ${String(r.effFam).padStart(3)}`,
  );
}

const counts = {
  载体: carrierDoc.affixes?.length ?? 0,
  元素: elementDoc.affixes?.length ?? 0,
  属性: attributes.length,
  效果: effects.length,
  协同: links.length,
  定向卡: targets.length,
};
console.log('\n【条目数】' + Object.entries(counts).map(([k, v]) => `${k} ${v}`).join(' / '));
console.log(`【最窄池子】单载体单层最少 ${worstPool} 条，去同族后最少 ${worstFamily} 个族`);

if (warnings.length) {
  console.log(`\n【警告 ${warnings.length} 条】`);
  for (const w of warnings.slice(0, 12)) console.log(`  ⚠ [${w.code}] ${w.msg}`);
  if (warnings.length > 12) console.log(`  … 其余 ${warnings.length - 12} 条省略`);
}

if (errors.length) {
  console.error(`\n【错误 ${errors.length} 条】`);
  for (const e of errors.slice(0, 30)) console.error(`  ✗ [${e.code}] ${e.msg}`);
  if (errors.length > 30) console.error(`  … 其余 ${errors.length - 30} 条省略`);
  process.exit(1);
}

console.log('\n✓ 六条护栏全部通过');
