#!/usr/bin/env node
/**
 * 抽卡模拟器 —— 用真实卡表跑 N 次完整抽卡，验证数据是否可用
 *
 * 校验器只能证明「表结构没坏」，证明不了「抽起来好不好玩」。
 * 这个脚本回答四个只有跑起来才知道的问题：
 *   ① 每层真的能发出 5 张不同族的牌吗（去同族之后）
 *   ② 预算约束会不会让玩家在最后一层面对「五张全灰」
 *   ③ 最终稀有度分布落在哪个区间（是不是全挤在史诗）
 *   ④ 红卡实际出现频率与设计值 8–10% 差多少
 *
 * 用法：node tools/simulate-draft.mjs [次数]
 */

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const DATA_DIR = join(__dirname, '..', 'core', 'src', 'main', 'resources', 'data');

const load = (f) => JSON.parse(readFileSync(join(DATA_DIR, f), 'utf8'));

const carriers = load('affixes.carrier.json').affixes;
const elements = load('affixes.element.json').affixes;
const attributes = load('affixes.attribute.json').affixes;
const effects = load('affixes.effect.json').affixes;

const RUNS = Number(process.argv[2] ?? 2000);
const DRAW = 5;
const BUDGET = 28;

// 阈值按「纯随机抽卡」的分布校准过：4 层品质独立的平均点数是 9.84，
// 若沿用 1-4/5-8/9-16/17-28，平均分正好落在史诗，实测普通只占 2%、史诗占 54%，
// 稀有度就失去区分度了。上移后四个「全同品质」对齐点仍然成立：
// 全白 4→普通 / 全蓝 8→稀有 / 全金 16→史诗 / 全红 28→传说
const RARITY = [
  [0, 5, '普通'], [6, 11, '稀有'], [12, 18, '史诗'], [19, 28, '传说'],
];
const rarityOf = (pts) => RARITY.find(([lo, hi]) => pts >= lo && pts <= hi)?.[2] ?? '普通';

// mulberry32：可复现，便于玩家投诉时按 seed 回放
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// 品质权重：红 8% / 金 22% / 蓝 32% / 白 38%
const QUALITY = [['WHITE', 38], ['BLUE', 32], ['GOLD', 22], ['RED', 8]];
const rollQuality = (rand) => {
  let r = rand() * 100;
  for (const [q, w] of QUALITY) { if ((r -= w) < 0) return q; }
  return 'WHITE';
};

const TIER_ORDER = ['WHITE', 'BLUE', 'GOLD', 'RED'];
const downgrade = (q) => TIER_ORDER[Math.max(0, TIER_ORDER.indexOf(q) - 1)];

const stats = {
  shortfall: 0,          // 某层凑不满 5 张不同族
  greyOut: 0,            // 出现「可选不足 2 张」
  perLayerOptions: [],   // 每层平均可选数
  points: [],
  rarity: {},
  redCards: 0,
  dealt: { WHITE: 0, BLUE: 0, GOLD: 0, RED: 0 },
  chosen: { WHITE: 0, BLUE: 0, GOLD: 0, RED: 0 },
  totalCards: 0,
  baseline: {},          // 各 base_type 的分布
};

for (let run = 0; run < RUNS; run++) {
  const rand = rng(0x9E3779B9 ^ (run * 2654435761));
  const baseType = ['EQUIPMENT', 'CONSUMABLE', 'SUMMON'][Math.floor(rand() * 3)];
  const picked = [];
  const usedFamilies = new Set();
  let points = 0;

  const qualityOf = (card) => {
    if (!card.tiers?.length) return null;
    // 必须先掷一次再查表。若把 rollQuality(rand) 写进 find 的回调里，
    // 每比较一个元素都会重新掷一次随机数，几乎永远匹配不上，
    // 结果所有卡都被 ?? 兜底成 tiers[0]（白卡）——实测红卡率从 8% 掉到 2.6%。
    const q = rollQuality(rand);
    return card.tiers.find((t) => t.tier === q) ?? card.tiers[0];
  };

  const drawLayer = (pool) => {
    // 1. 去同族（层内不能出现两张同族卡，否则等于把选项复制多遍）
    const byFamily = new Map();
    for (const c of pool) {
      if (usedFamilies.has(c.family)) continue;
      if (!byFamily.has(c.family)) byFamily.set(c.family, c);
    }
    const avail = [...byFamily.values()];
    stats.perLayerOptions.push(avail.length);

    const take = Math.min(DRAW, avail.length);
    if (take < DRAW) stats.shortfall++;

    // 2. 随机取 n 张不同族的卡
    const shuffled = avail.slice();
    for (let i = shuffled.length - 1; i > 0; i--) {
      const j = Math.floor(rand() * (i + 1));
      [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
    }
    let cards = shuffled.slice(0, take).map((c) => ({ card: c, tier: qualityOf(c) }));

    // 3. 预算约束：至少保证 2 张买得起，否则降档重 roll
    const remaining = BUDGET - points;
    const affordable = () => cards.filter((x) => (x.tier?.points ?? 0) <= remaining).length;
    let guard = 0;
    while (affordable() < 2 && guard++ < 10) {
      cards = cards.map((x) => (x.tier && x.tier.points > remaining
        ? { card: x.card, tier: x.card.tiers.find((t) => t.tier === downgrade(x.tier.tier)) ?? x.tier }
        : x));
      if (affordable() < 2) {
        cards = cards.map((x) => ({ card: x.card, tier: x.card.tiers?.[0] ?? null }));
      }
    }
    if (affordable() < 2) stats.greyOut++;
    for (const x of cards) stats.dealt[x.tier.tier]++;

    return { cards, remaining };
  };

  // ---- 层 1：载体 ----
  const carrierPool = carriers.filter((c) => c.base_type === baseType);
  const l1 = drawLayer(carrierPool);
  const chosen1 = l1.cards[Math.floor(rand() * l1.cards.length)];
  picked.push(chosen1); usedFamilies.add(chosen1.card.family); points += chosen1.tier.points;
  const carrier = chosen1.card.id.replace(/^carrier\./, '');

  // ---- 层 2：元素 ----
  const l2 = drawLayer(elements);
  const chosen2 = l2.cards[Math.floor(rand() * l2.cards.length)];
  picked.push(chosen2); usedFamilies.add(chosen2.card.family); points += chosen2.tier.points;
  const element = chosen2.card.id.replace(/^elem\./, '');

  // ---- 层 3：属性 ----
  const attrPool = attributes.filter(
    (a) => a.carrier_allow.includes(carrier)
      && (a.element_allow.length === 0 || a.element_allow.includes(element)),
  );
  const l3 = drawLayer(attrPool);
  const chosen3 = l3.cards[Math.floor(rand() * l3.cards.length)];
  picked.push(chosen3); usedFamilies.add(chosen3.card.family); points += chosen3.tier.points;

  // ---- 层 4：效果（排除与已选互斥）----
  const effPool = effects.filter((a) => {
    if (!a.carrier_allow.includes(carrier)) return false;
    if (a.element_allow.length && !a.element_allow.includes(element)) return false;
    if (a.excludes.some((x) => picked.some((p) => p.card.family === x))) return false;
    return true;
  });
  const l4 = drawLayer(effPool);
  const chosen4 = l4.cards[Math.floor(rand() * l4.cards.length)];
  picked.push(chosen4); usedFamilies.add(chosen4.card.family); points += chosen4.tier.points;

  // ---- 汇总 ----
  stats.points.push(points);
  stats.totalCards += picked.length;
  stats.redCards += picked.filter((p) => p.tier.tier === 'RED').length;
  for (const p of picked) stats.chosen[p.tier.tier]++;
  const r = rarityOf(points);
  stats.rarity[r] = (stats.rarity[r] ?? 0) + 1;
  stats.baseline[baseType] = (stats.baseline[baseType] ?? 0) + 1;
}

/* ------------------------------------------------------------------ */
/* 报告                                                                */
/* ------------------------------------------------------------------ */

const avg = (arr) => (arr.reduce((a, b) => a + b, 0) / arr.length);
const pct = (n, d) => `${((n / d) * 100).toFixed(1)}%`;

console.log(`模拟 ${RUNS} 次完整抽卡（4 层）\n`);

console.log('【池子】');
console.log(`  每层平均可选卡数      ${avg(stats.perLayerOptions).toFixed(1)} 张`);
console.log(`  凑不满 ${DRAW} 张不同族的层数  ${stats.shortfall} / ${stats.perLayerOptions.length}`);
console.log(`  出现「可选不足 2 张」  ${stats.greyOut} 次`);

console.log('\n【点数】');
console.log(`  平均 ${avg(stats.points).toFixed(1)} 点（预算上限 ${BUDGET}）`);
console.log(`  最低 ${Math.min(...stats.points)} 点 / 最高 ${Math.max(...stats.points)} 点`);

console.log('\n【稀有度分布】');
for (const [, , name] of RARITY) {
  const n = stats.rarity[name] ?? 0;
  const bar = '█'.repeat(Math.round((n / RUNS) * 40));
  console.log(`  ${name.padEnd(3)} ${String(n).padStart(5)}  ${pct(n, RUNS).padStart(6)}  ${bar}`);
}

console.log('\n【红卡】');
const dealtTotal = Object.values(stats.dealt).reduce((a,b)=>a+b,0);
console.log('  发牌分布 ' + Object.entries(stats.dealt).map(([k,v])=>`${k} ${pct(v,dealtTotal)}`).join(' / '));
console.log(`  实际出现率 ${pct(stats.redCards, stats.totalCards)}（设计值 8–10%）`);

console.log('\n【基调分布】');
for (const [k, v] of Object.entries(stats.baseline)) console.log(`  ${k.padEnd(11)} ${pct(v, RUNS)}`);

const bad = stats.shortfall + stats.greyOut;
if (bad > 0) {
  console.error(`\n✗ 有 ${bad} 次抽卡不达标（凑不满 / 五张全灰）`);
  process.exit(1);
}
console.log('\n✓ 每层都能发满 5 张不同族的牌，且始终有 ≥ 2 张买得起');
