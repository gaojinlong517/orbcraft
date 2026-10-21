#!/usr/bin/env node
/**
 * 卡表生成器 —— 族定义 → 450 条词条
 *
 * 设计要点（见 docs §29.1）：
 *   手写 450 条词条不可行（ID 会拼错、数值会漂移、文案会不一致）。
 *   所以手写的是「约 50 个族」，用「族 × 载体」展开成 450 条。
 *   笔数从加法变成乘法，人手成本回到 50 个族的量级。
 *
 * 展开策略：round-robin（按轮次每个族各取 1 条）。
 *   理由：如果按「族序优先」截断，靠后的族会被整族砍光，
 *   轮转能保证每个族至少留下 1 条，裁掉的只是尾部名额。
 *
 * 用法：node tools/gen-card-data.mjs
 */

import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = join(__dirname, '..', 'core', 'src', 'main', 'resources', 'data');
const DATA_VERSION = '1.7.0';

/* ------------------------------------------------------------------ */
/* 1. 载体与元素                                                        */
/* ------------------------------------------------------------------ */

const CARRIERS = [
  ['gun', '枪', 'EQUIPMENT'], ['sword', '剑', 'EQUIPMENT'], ['bow', '弓', 'EQUIPMENT'],
  ['axe', '斧', 'EQUIPMENT'], ['staff', '法杖', 'EQUIPMENT'], ['armor', '护甲', 'EQUIPMENT'],
  ['apple', '苹果', 'CONSUMABLE'], ['potion', '药水', 'CONSUMABLE'], ['scroll', '卷轴', 'CONSUMABLE'],
  ['bomb', '炸弹', 'CONSUMABLE'], ['feast', '盛宴', 'CONSUMABLE'],
  ['dragon', '龙', 'SUMMON'], ['beast', '兽', 'SUMMON'], ['spirit', '精灵', 'SUMMON'],
  ['golem', '傀儡', 'SUMMON'], ['wraith', '幽灵', 'SUMMON'], ['swarm', '虫群', 'SUMMON'],
  ['crystal', '晶簇', 'SUMMON'],
];

const ELEMENTS = [
  ['lightning', '雷'], ['fire', '火'], ['ice', '冰'], ['poison', '毒'], ['light', '光'],
  ['dark', '暗'], ['rock', '岩'], ['wind', '风'], ['physical', '物理'],
];

const CARRIER_TYPE = Object.fromEntries(CARRIERS.map(([id, , t]) => [id, t]));
const CARRIER_NAME = Object.fromEntries(CARRIERS.map(([id, n]) => [id, n]));
const ELEMENT_NAME = Object.fromEntries(ELEMENTS);

/* ------------------------------------------------------------------ */
/* 2. 属性族（24 通用 + 12 召唤 + 11 消耗 + 6 元素特化）                */
/*    字段：family, 名称, 单位, 基础值, 精度, 载体优先级, 元素维度?      */
/* ------------------------------------------------------------------ */

const EQUIP = ['gun', 'sword', 'bow', 'axe', 'staff', 'armor'];
const SUMMON = ['dragon', 'beast', 'spirit', 'golem', 'wraith', 'swarm', 'crystal'];
const CONS = ['apple', 'potion', 'scroll', 'bomb', 'feast'];

const ATTRIBUTE_FAMILIES = [
  // --- 装备通用 ---
  ['damage', '伤害', '', 2, 0, ['gun', 'sword', 'bow', 'axe', 'staff', 'bomb', 'scroll']],
  ['critChance', '暴击率', '%', 4, 0, ['gun', 'sword', 'bow', 'axe', 'bomb']],
  ['critDamage', '暴击伤害', '%', 8, 0, ['gun', 'sword', 'bow', 'axe', 'bomb']],
  ['lifesteal', '吸血', '%', 3, 0, ['gun', 'sword', 'axe', 'staff']],
  ['durability', '耐久', '%', 15, 0, EQUIP],
  ['statusChance', '状态触发率', '%', 5, 0, ['gun', 'sword', 'bow', 'staff', 'bomb', 'scroll']],
  ['statusDuration', '状态持续', 's', 0.5, 1, ['gun', 'sword', 'bow', 'staff', 'bomb', 'scroll']],
  ['elementPower', '元素强度', '%', 6, 0, ['staff', 'gun', 'bow', 'scroll']],
  ['fireRate', '射速', '%', 6, 0, ['gun', 'bow']],
  ['pierce', '穿透', '', 1, 0, ['gun', 'bow']],
  ['range', '射程', '', 1.5, 1, ['gun', 'bow', 'staff', 'bomb', 'scroll']],
  ['accuracy', '精度', '%', 5, 0, ['gun', 'bow']],
  ['attackSpeed', '攻速', '%', 5, 0, ['sword', 'axe', 'staff']],
  ['knockback', '击退', '', 0.4, 1, ['sword', 'axe', 'staff']],
  ['aoeRadius', '范围', '', 0.5, 1, ['staff', 'bomb', 'scroll']],
  ['cooldownReduction', '冷却缩减', '%', 5, 0, ['staff', 'scroll']],
  ['armor', '护甲', '', 1, 0, ['armor']],
  ['maxHealth', '生命上限', '', 2, 0, ['armor', 'feast']],
  ['moveSpeed', '移速', '%', 3, 0, ['armor', 'apple', 'feast']],
  ['thorns', '荆棘', '', 2, 0, ['armor']],
  ['ammoCapacity', '弹匣容量', '', 2, 0, ['gun']],
  ['drawSpeed', '拉弓速度', '%', 6, 0, ['bow']],
  ['cleave', '顺劈', '', 1, 0, ['axe']],
  ['blockChance', '格挡率', '%', 4, 0, ['sword', 'armor']],

  // --- 召唤通用 ---
  ['summonHealth', '召唤生命', '%', 10, 0, SUMMON],
  ['summonDamage', '召唤攻击', '%', 8, 0, SUMMON],
  ['summonSpeed', '召唤移速', '%', 4, 0, SUMMON],
  ['summonStability', '稳定性', '%', 6, 0, SUMMON],

  // --- 召唤专属 ---
  ['summonCount', '召唤数量', '', 1, 0, ['dragon', 'golem', 'swarm', 'beast']],
  ['summonLifespan', '存续时间', 's', 15, 0, ['beast', 'spirit', 'wraith', 'swarm']],
  ['summonArmor', '召唤护甲', '', 2, 0, ['golem', 'dragon', 'beast']],
  ['summonRegen', '召唤自愈', '%', 1, 0, ['spirit', 'crystal']],
  ['summonElement', '召唤元素强度', '%', 6, 0, ['dragon', 'spirit', 'wraith', 'crystal']],
  ['summonAoe', '召唤攻击范围', '', 0.5, 1, ['swarm', 'crystal', 'dragon']],

  // --- 消耗品通用 ---
  ['duration', '持续时间', 's', 5, 0, CONS],
  ['potency', '效果强度', '%', 10, 0, CONS],
  ['charges', '使用次数', '', 1, 0, ['potion', 'scroll', 'bomb', 'feast', 'apple']],

  // --- 消耗品专属 ---
  ['healing', '治疗', '', 3, 0, ['apple', 'potion', 'feast']],
  ['hunger', '饱食', '', 3, 0, ['apple', 'feast']],
  ['haste', '疾跑', '%', 10, 0, ['apple']],
  ['xpBonus', '经验获取', '%', 8, 0, ['potion']],
  ['lootBonus', '掉落加成', '%', 5, 0, ['potion']],
  ['blastRadius', '爆炸半径', '', 0.5, 1, ['bomb']],
  ['blastDamage', '爆炸伤害', '', 3, 0, ['bomb']],
  ['shieldAmount', '护盾值', '', 3, 0, ['potion']],

  // --- 补充族：专为冷门载体（消耗品 / 部分召唤物）扩池 ---
  // 起因：元素特化族（elemResist 等）虽然产生几十条数据，
  // 但「去同族」后只算 1 个族，对池子多样性几乎没有贡献。
  // 所以这些载体需要真正的基础族，否则抽 5 张会把池子掏空。
  ['regen', '生命回复', '%', 1, 0, ['armor', 'apple', 'potion', 'feast', 'spirit', 'golem', 'beast']],
  ['resist', '抗性', '%', 4, 0, ['armor', 'potion', 'feast', 'golem', 'dragon', 'scroll']],
  ['summonCrit', '召唤暴击', '%', 5, 0, ['dragon', 'wraith', 'spirit', 'crystal', 'swarm', 'golem', 'beast']],
  ['summonStatus', '召唤状态触发', '%', 4, 0, ['dragon', 'wraith', 'spirit', 'crystal', 'swarm']],
  ['summonAggro', '召唤仇恨', '%', 10, 0, ['golem', 'beast', 'dragon', 'wraith', 'swarm']],
  ['stackLimit', '叠加上限', '', 1, 0, ['apple', 'potion', 'scroll', 'bomb', 'feast']],
  ['triggerRadius', '触发范围', '', 0.5, 1, ['bomb', 'scroll', 'apple', 'feast']],
  ['useSpeed', '使用速度', '%', 8, 0, ['apple', 'potion', 'feast', 'scroll']],
  ['agility', '灵活', '%', 3, 0, ['armor', 'apple', 'beast', 'wraith', 'swarm']],
  ['focus', '专注', '%', 4, 0, ['staff', 'scroll', 'potion', 'sword']],

  // --- 元素特化（element 维度）---
  ['elemDamage', '元素伤害', '%', 6, 0, ['gun', 'sword', 'bow', 'staff'], ELEMENTS.map((e) => e[0])],
  ['elemResist', '元素抗性', '%', 5, 0, ['armor'], ELEMENTS.map((e) => e[0])],
  ['elemAffinity', '元素亲和', '%', 5, 0, ['staff'], ELEMENTS.map((e) => e[0])],
  ['elemAura', '元素光环', '%', 4, 0, ['spirit', 'crystal'], ELEMENTS.map((e) => e[0])],
  ['elemSummonDamage', '召唤元素伤害', '%', 7, 0, ['dragon', 'wraith'], ELEMENTS.map((e) => e[0])],
  ['elemSummonResist', '召唤元素抗性', '%', 4, 0, ['golem', 'crystal'], ELEMENTS.map((e) => e[0])],
];

const TARGET_ATTRIBUTE = 243;

/* ------------------------------------------------------------------ */
/* 3. 效果族（30 × 6 载体 = 180）                                       */
/*    字段：family, 名称, 载体优先级, 元素限定?, 与哪些族冲突            */
/* ------------------------------------------------------------------ */

const EFFECT_FAMILIES = [
  ['ignite', '燃烧', ['gun', 'sword', 'bow', 'axe', 'staff', 'bomb'], ['fire'], []],
  ['shock', '感电', ['gun', 'bow', 'staff', 'dragon', 'spirit', 'bomb'], ['lightning'], []],
  ['freeze', '冰冻', ['sword', 'staff', 'bow', 'armor', 'crystal', 'bomb'], ['ice'], []],
  ['poisonStack', '淬毒', ['sword', 'axe', 'bow', 'swarm', 'spirit', 'potion'], ['poison'], []],
  ['blind', '致盲', ['staff', 'bomb', 'gun', 'wraith', 'scroll', 'apple'], null, []],
  ['stun', '眩晕', ['axe', 'staff', 'bomb', 'golem', 'dragon', 'armor', 'bow'], null, []],
  ['explosion', '爆裂', ['bomb', 'staff', 'gun', 'axe', 'crystal', 'swarm', 'bow'], null, ['freeze']],
  ['chain', '连锁', ['staff', 'gun', 'bow', 'spirit', 'wraith', 'crystal'], null, ['execute']],
  ['lifestealAura', '吸血光环', ['sword', 'axe', 'armor', 'wraith', 'dragon', 'spirit'], null, ['execute']],
  ['thornsBurst', '荆棘反伤', ['armor', 'sword', 'golem', 'beast', 'crystal', 'staff'], null, []],
  ['shield', '护盾', ['armor', 'potion', 'golem', 'spirit', 'scroll', 'apple', 'feast'], null, []],
  ['sprint', '疾跑', ['apple', 'potion', 'armor', 'beast', 'wraith', 'scroll', 'feast', 'swarm'], null, ['slowField']],
  ['invisibility', '隐身', ['potion', 'wraith', 'scroll', 'armor', 'spirit', 'feast', 'bow'], null, ['auraBuff']],
  ['jumpBoost', '跃升', ['armor', 'apple', 'beast', 'swarm', 'scroll', 'potion', 'feast'], null, []],
  ['nightVision', '夜视', ['potion', 'scroll', 'armor', 'apple', 'wraith', 'spirit'], null, []],
  ['waterBreathing', '水下呼吸', ['potion', 'armor', 'scroll', 'apple', 'spirit', 'beast'], null, []],
  ['fireResist', '火焰抗性', ['armor', 'potion', 'apple', 'golem', 'dragon', 'scroll'], null, []],
  ['summonSplit', '分裂', ['swarm', 'crystal', 'spirit', 'wraith', 'beast', 'golem'], null, ['summonEnrage']],
  ['summonEnrage', '狂暴', ['beast', 'dragon', 'wraith', 'swarm', 'golem', 'crystal'], null, ['summonSplit']],
  ['deathBoom', '亡语爆炸', ['bomb', 'golem', 'crystal', 'swarm', 'dragon', 'wraith'], null, []],
  ['magnet', '磁吸', ['armor', 'gun', 'apple', 'scroll', 'potion', 'sword', 'feast'], null, []],
  ['counter', '反击', ['sword', 'armor', 'axe', 'golem', 'beast', 'dragon', 'feast', 'swarm'], null, []],
  ['reflect', '反弹', ['armor', 'staff', 'sword', 'crystal', 'spirit', 'golem'], null, []],
  ['armorPierce', '破甲', ['gun', 'bow', 'axe', 'wraith', 'staff', 'sword'], null, []],
  ['combo', '连击', ['gun', 'sword', 'bow', 'axe', 'staff', 'swarm'], null, []],
  ['execute', '处决', ['sword', 'axe', 'gun', 'bow', 'dragon', 'wraith'], null, ['chain', 'lifestealAura']],
  ['slowField', '缓速场', ['staff', 'crystal', 'spirit', 'armor', 'scroll', 'bomb'], null, ['sprint']],
  ['blink', '闪现', ['wraith', 'spirit', 'scroll', 'potion', 'armor', 'apple'], null, []],
  ['auraBuff', '增益光环', ['spirit', 'dragon', 'crystal', 'golem', 'beast', 'armor', 'feast'], null, ['invisibility']],
  ['lastStand', '濒死爆发', ['armor', 'apple', 'potion', 'sword', 'beast', 'scroll', 'feast'], null, []],
];

const TARGET_EFFECT = 180;

/* ------------------------------------------------------------------ */
/* 3.5 派生协同表（140 条）                                             */
/*     全部是语义成立的组合，作用是把「协同覆盖率」补到 200 条，          */
/*     避免玩家抽到两个明显该有联动的词条却毫无反馈。                    */
/* ------------------------------------------------------------------ */

// 45 条：9 元素 × 5 个自然搭档效果
const DERIVED_ELEM_EFFECT = [
  ['lightning', 'stun', 0.10, '感电眩晕 +10%'],
  ['lightning', 'chain', 1, '连锁次数 +1'],
  ['lightning', 'combo', 0.15, '连击窗口 +15%'],
  ['lightning', 'sprint', 0.10, '冲刺速度 +10%'],
  ['lightning', 'counter', 0.15, '反击触发率 +15%'],
  ['fire', 'ignite', 0.20, '燃烧伤害 +20%'],
  ['fire', 'explosion', 0.15, '爆炸附带燃烧 +15%'],
  ['fire', 'deathBoom', 0.20, '亡语爆炸伤害 +20%'],
  ['fire', 'fireResist', 0.20, '火焰抗性 +20%'],
  ['fire', 'lastStand', 0.15, '濒死爆发伤害 +15%'],
  ['ice', 'freeze', 1, '冻结时长 +1s'],
  ['ice', 'slowField', 0.20, '缓速场强度 +20%'],
  ['ice', 'shield', 0.15, '冰盾值 +15%'],
  ['ice', 'thornsBurst', 0.15, '反伤附带冰冻 +15%'],
  ['ice', 'reflect', 0.10, '反弹附带减速 +10%'],
  ['poison', 'poisonStack', 2, '中毒层数 +2'],
  ['poison', 'lifestealAura', 0.10, '对中毒目标吸血 +10%'],
  ['poison', 'blind', 0.15, '致盲概率 +15%'],
  ['poison', 'summonSplit', 0.10, '分裂体带毒 +10%'],
  ['poison', 'magnet', 0.10, '磁吸范围 +10%'],
  ['light', 'auraBuff', 0.15, '光环强度 +15%'],
  ['light', 'shield', 0.20, '护盾值 +20%'],
  ['light', 'nightVision', 0.15, '夜视范围 +15%'],
  ['light', 'lastStand', 0.15, '濒死爆发持续 +15%'],
  ['light', 'counter', 0.10, '反击触发率 +10%'],
  ['dark', 'execute', 0.05, '处决阈值 +5%'],
  ['dark', 'invisibility', 0.20, '隐身时长 +20%'],
  ['dark', 'blink', 0.15, '闪现距离 +15%'],
  ['dark', 'lifestealAura', 0.10, '吸血光环强度 +10%'],
  ['dark', 'summonEnrage', 0.10, '狂暴期攻击 +10%'],
  ['rock', 'armorPierce', 0.15, '破甲效果 +15%'],
  ['rock', 'thornsBurst', 0.15, '反伤 +15%'],
  ['rock', 'reflect', 0.15, '反弹比例 +15%'],
  ['rock', 'counter', 0.15, '反击伤害 +15%'],
  ['rock', 'stun', 0.10, '眩晕概率 +10%'],
  ['wind', 'jumpBoost', 0.20, '跃升高度 +20%'],
  ['wind', 'sprint', 0.15, '疾跑速度 +15%'],
  ['wind', 'blink', 0.15, '闪现距离 +15%'],
  ['wind', 'magnet', 0.10, '磁吸范围 +10%'],
  ['wind', 'combo', 0.10, '连击窗口 +10%'],
  ['physical', 'combo', 0.10, '连击窗口 +10%'],
  ['physical', 'armorPierce', 0.15, '破甲效果 +15%'],
  ['physical', 'counter', 0.10, '反击伤害 +10%'],
  ['physical', 'execute', 0.05, '处决阈值 +5%'],
  ['physical', 'deathBoom', 0.10, '亡语爆炸伤害 +10%'],
];

// 60 条：属性 × 效果
const DERIVED_ATTR_EFFECT = [
  ['damage', 'execute', 0.05, '处决阈值 +5%'],
  ['critChance', 'execute', 0.05, '处决阈值 +5%'],
  ['critDamage', 'combo', 0.10, '连击伤害 +10%'],
  ['lifesteal', 'lifestealAura', 0.15, '吸血光环强度 +15%'],
  ['durability', 'lastStand', 0.15, '濒死爆发持续 +15%'],
  ['statusChance', 'ignite', 0.15, '点燃概率 +15%'],
  ['statusDuration', 'freeze', 0.5, '冻结时长 +0.5s'],
  ['elementPower', 'ignite', 0.15, '燃烧伤害 +15%'],
  ['fireRate', 'combo', 0.15, '连击判定 +15%'],
  ['pierce', 'armorPierce', 0.15, '破甲穿透 +15%'],
  ['range', 'chain', 0.20, '连锁距离 +20%'],
  ['accuracy', 'execute', 0.05, '精准处决 +5%'],
  ['attackSpeed', 'counter', 0.15, '反击触发率 +15%'],
  ['knockback', 'stun', 0.10, '击退眩晕 +10%'],
  ['aoeRadius', 'explosion', 0.25, '爆炸范围 +25%'],
  ['cooldownReduction', 'blink', 0.15, '闪现冷却 −15%'],
  ['armor', 'reflect', 0.20, '反弹比例 +20%'],
  ['maxHealth', 'lastStand', 0.20, '濒死爆发持续 +20%'],
  ['moveSpeed', 'sprint', 0.15, '疾跑速度 +15%'],
  ['thorns', 'thornsBurst', 0.20, '荆棘反伤 +20%'],
  ['ammoCapacity', 'combo', 0.10, '连击上限 +10%'],
  ['drawSpeed', 'armorPierce', 0.10, '破甲效果 +10%'],
  ['cleave', 'stun', 0.15, '顺劈眩晕 +15%'],
  ['blockChance', 'counter', 0.15, '格挡反击 +15%'],
  ['summonHealth', 'summonSplit', 0.15, '分裂体生命 +15%'],
  ['summonDamage', 'summonEnrage', 0.15, '狂暴攻击 +15%'],
  ['summonSpeed', 'blink', 0.15, '召唤闪现 +15%'],
  ['summonStability', 'auraBuff', 0.15, '光环稳定 +15%'],
  ['summonCount', 'summonSplit', 0.10, '分裂体数量 +10%'],
  ['summonLifespan', 'lastStand', 0.20, '濒死续命 +20%'],
  ['summonArmor', 'shield', 0.20, '召唤护盾 +20%'],
  ['summonRegen', 'summonSplit', 0.15, '分裂体自愈 +15%'],
  ['summonElement', 'auraBuff', 0.15, '元素光环 +15%'],
  ['summonAoe', 'deathBoom', 0.20, '亡语范围 +20%'],
  ['duration', 'auraBuff', 0.20, '光环持续 +20%'],
  ['potency', 'shield', 0.20, '护盾值 +20%'],
  ['charges', 'magnet', 0.10, '磁吸次数 +10%'],
  ['healing', 'shield', 0.15, '护盾吸收 +15%'],
  ['hunger', 'lastStand', 0.10, '濒死回复 +10%'],
  ['haste', 'sprint', 0.20, '疾跑速度 +20%'],
  ['xpBonus', 'auraBuff', 0.10, '光环经验 +10%'],
  ['lootBonus', 'magnet', 0.15, '磁吸掉落 +15%'],
  ['blastRadius', 'explosion', 0.30, '爆炸范围 +30%'],
  ['blastDamage', 'deathBoom', 0.25, '亡语爆炸 +25%'],
  ['shieldAmount', 'reflect', 0.10, '护盾反弹 +10%'],
  // 第二搭档
  ['damage', 'deathBoom', 0.10, '亡语爆炸伤害 +10%'],
  ['critChance', 'combo', 0.10, '连击暴击 +10%'],
  ['lifesteal', 'execute', 0.05, '处决回复 +5%'],
  ['statusChance', 'poisonStack', 0.15, '中毒概率 +15%'],
  ['statusDuration', 'slowField', 0.5, '缓速持续 +0.5s'],
  ['range', 'blind', 0.10, '远程致盲 +10%'],
  ['attackSpeed', 'combo', 0.15, '连击上限 +15%'],
  ['knockback', 'freeze', 0.10, '冰冻击退 +10%'],
  ['aoeRadius', 'slowField', 0.15, '缓速场范围 +15%'],
  ['armor', 'counter', 0.15, '反击伤害 +15%'],
  ['maxHealth', 'thornsBurst', 0.15, '反伤 +15%'],
  ['moveSpeed', 'blink', 0.10, '闪现距离 +10%'],
  ['summonDamage', 'summonSplit', 0.10, '分裂体攻击 +10%'],
  ['potency', 'explosion', 0.20, '爆炸伤害 +20%'],
  ['duration', 'shield', 0.15, '护盾持续 +15%'],
];

// 20 条：属性 × 属性（同层互斥之外的正向联动）
const DERIVED_ATTR_ATTR = [
  ['damage', 'critChance', 0.10, '暴击伤害 +10%'],
  ['critChance', 'critDamage', 0.20, '暴击伤害 +20%'],
  ['lifesteal', 'attackSpeed', 0.10, '吸血触发 +10%'],
  ['fireRate', 'ammoCapacity', 0.10, '持续输出 +10%'],
  ['range', 'accuracy', 0.10, '远距离精度 +10%'],
  ['attackSpeed', 'cleave', 0.10, '顺劈频率 +10%'],
  ['knockback', 'cleave', 0.10, '顺劈击退 +10%'],
  ['armor', 'maxHealth', 0.10, '生命上限 +10%'],
  ['armor', 'blockChance', 0.10, '格挡率 +10%'],
  ['maxHealth', 'thorns', 0.15, '荆棘反伤 +15%'],
  ['summonHealth', 'summonArmor', 0.10, '召唤护甲 +10%'],
  ['summonDamage', 'summonSpeed', 0.10, '召唤机动 +10%'],
  ['summonCount', 'summonLifespan', 0.10, '存续时间 +10%'],
  ['duration', 'potency', 0.15, '效果强度 +15%'],
  ['potency', 'charges', 0.10, '使用次数 +10%'],
  ['healing', 'hunger', 0.10, '回复量 +10%'],
  ['haste', 'moveSpeed', 0.10, '移速 +10%'],
  ['blastRadius', 'blastDamage', 0.20, '爆炸伤害 +20%'],
  ['shieldAmount', 'healing', 0.10, '护盾回复 +10%'],
  ['elementPower', 'summonElement', 0.15, '召唤元素强度 +15%'],
];

// 15 条：元素 × 属性
const DERIVED_ELEM_ATTR = [
  ['lightning', 'fireRate', 0.10, '射速 +10%'],
  ['fire', 'damage', 0.10, '伤害 +10%'],
  ['ice', 'statusDuration', 0.20, '状态持续 +20%'],
  ['poison', 'statusChance', 0.15, '状态触发 +15%'],
  ['light', 'healing', 0.15, '治疗量 +15%'],
  ['dark', 'critDamage', 0.15, '暴击伤害 +15%'],
  ['rock', 'armor', 0.30, '护甲 +30%'],
  ['wind', 'moveSpeed', 0.15, '移速 +15%'],
  ['physical', 'damage', 0.15, '伤害 +15%'],
  ['lightning', 'statusChance', 0.10, '状态触发 +10%'],
  ['fire', 'aoeRadius', 0.15, '范围 +15%'],
  ['ice', 'elementPower', 0.15, '元素强度 +15%'],
  ['poison', 'potency', 0.10, '效果强度 +10%'],
  ['rock', 'maxHealth', 0.15, '生命上限 +15%'],
  ['wind', 'attackSpeed', 0.10, '攻速 +10%'],
];

/* ------------------------------------------------------------------ */
/* 4. 品质档位（全局唯一，见 docs §27.3）                               */
/* ------------------------------------------------------------------ */

const TIERS = [
  { tier: 'WHITE', points: 1, magnitude: 1.0 },
  { tier: 'BLUE', points: 2, magnitude: 1.6 },
  { tier: 'GOLD', points: 4, magnitude: 2.5 },
  { tier: 'RED', points: 7, magnitude: 4.0 },
];

// 稳定的字符串散列，用于「同样的输入永远得到同样的红卡代价」
function hash(str) {
  let h = 2166136261;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0);
}

function goldDrawback(baseType) {
  // 金卡取「小的」代价：装备扣耐久，非装备多要 1 份材料
  return baseType === 'EQUIPMENT'
    ? { type: 'DURABILITY_PCT', value: -0.20 }
    : { type: 'MATERIAL_EXTRA', value: 1 };
}

function redDrawback(baseType, family) {
  // 红卡取「真的」代价，两种交替出现，避免所有红卡手感一致
  if (hash(family) % 3 === 0) return { type: 'MOVE_SPEED_PCT', value: -0.10 };
  if (hash(family) % 3 === 1) return { type: 'HATCH_TIME_PCT', value: 0.50 };
  return baseType === 'EQUIPMENT'
    ? { type: 'UNREPAIRABLE', value: 1 }
    : { type: 'HATCH_TIME_PCT', value: 0.50 };
}

function fmtValue(v, precision) {
  const r = precision === 0 ? Math.round(v) : Math.round(v * 10) / 10;
  return Number.isInteger(r) ? String(r) : r.toFixed(1);
}

function buildTiers(baseValue, precision, unit, name, baseType, family) {
  return TIERS.map(({ tier, points, magnitude }) => {
    const value = baseValue * magnitude;
    const sign = unit === '%' ? `${fmtValue(value, precision)}%` : `${fmtValue(value, precision)}${unit}`;
    return {
      tier,
      points,
      magnitude,
      value: Number(value.toFixed(precision === 0 ? 0 : 2)),
      drawback:
        tier === 'GOLD' ? goldDrawback(baseType)
          : tier === 'RED' ? redDrawback(baseType, family)
            : null,
      desc: `${name} +${sign}`,
    };
  });
}

/* ------------------------------------------------------------------ */
/* 5. 展开：round-robin                                                 */
/* ------------------------------------------------------------------ */

function roundRobin(families, target) {
  // 按「载体」而非「族」做轮转。
  //
  // 这是一个踩过的坑：最初按族轮转，每个族取第 k 个候选。
  // 结果任何被追加到族尾部的载体（如 feast 在第 7 位）永远轮不到，
  // 因为 30 族 × 6 候选正好等于 180，第 7 轮一个名额都没有 ——
  // feast 的效果池因此只剩 1 条，玩家抽效果层只有一张牌。
  //
  // 改成载体优先后，每个载体每轮各取 1 条，先保证人人有份，
  // 候选少的载体（斧只有 8 条）提前耗尽，剩余名额回流给其它载体。
  const byCarrier = new Map();
  for (const f of families) {
    for (const c of f.candidates) {
      const carrier = c.carrier_allow[0];
      if (!byCarrier.has(carrier)) byCarrier.set(carrier, []);
      byCarrier.get(carrier).push(c);
    }
  }
  const order = [...byCarrier.keys()];
  const out = [];
  let round = 0;
  while (out.length < target) {
    let added = 0;
    for (const c of order) {
      if (out.length >= target) break;
      const list = byCarrier.get(c);
      if (round < list.length) { out.push(list[round]); added++; }
    }
    if (added === 0) break; // 所有载体的候选都取完了
    round++;
  }
  return out;
}

function expandAttributes() {
  const families = ATTRIBUTE_FAMILIES.map(([family, name, unit, base, precision, carriers, elements]) => {
    const candidates = [];
    if (elements) {
      // 元素特化：元素在外层，载体在内层 —— 保证同一轮次能覆盖所有元素
      for (const el of elements) {
        for (const c of carriers) {
          candidates.push(makeAffix({
            id: `attr.${family}.${el}.${c}`,
            family, name: `${ELEMENT_NAME[el]}${name}`, unit, base, precision,
            carrier: c, element: el, layer: 'ATTRIBUTE',
          }));
        }
      }
    } else {
      for (const c of carriers) {
        candidates.push(makeAffix({
          id: `attr.${family}.${c}`,
          family, name, unit, base, precision,
          carrier: c, element: null, layer: 'ATTRIBUTE',
        }));
      }
    }
    return { family, candidates };
  });
  const picked = roundRobin(families, TARGET_ATTRIBUTE);
  assert(picked.length === TARGET_ATTRIBUTE,
    `属性候选不足：只有 ${picked.length} 条，目标 ${TARGET_ATTRIBUTE}（请增加族或载体）`);
  return picked;
}

function expandEffects() {
  const families = EFFECT_FAMILIES.map(([family, name, carriers, elements, conflicts]) => {
    const list = elements ?? [null];
    const candidates = [];
    for (const el of list) {
      for (const c of carriers) {
        const affix = makeAffix({
          id: el ? `effect.${family}.${el}.${c}` : `effect.${family}.${c}`,
          family, name, unit: '', base: 0, precision: 0,
          carrier: c, element: el, layer: 'EFFECT',
        });
        // 效果不带数值增减，带的是「触发强度百分比」
        affix.tiers = TIERS.map(({ tier, points, magnitude }) => ({
          tier,
          points,
          magnitude,
          value: Math.round(magnitude * 100),
          drawback:
            tier === 'GOLD' ? goldDrawback(CARRIER_TYPE[c])
              : tier === 'RED' ? redDrawback(CARRIER_TYPE[c], family)
                : null,
          desc: `${name} ${Math.round(magnitude * 100)}%`,
        }));
        affix.excludes = conflicts.map((f) => `effect.${f}`);
        candidates.push(affix);
      }
    }
    return { family, candidates };
  });
  const picked = roundRobin(families, TARGET_EFFECT);
  assert(picked.length === TARGET_EFFECT,
    `效果候选不足：只有 ${picked.length} 条，目标 ${TARGET_EFFECT}`);
  return picked;
}

function makeAffix({ id, family, name, unit, base, precision, carrier, element, layer }) {
  // family 必须带层级前缀：`carrier.armor`（护甲载体）和 `attr.armor`（护甲属性）
  // 如果不带前缀，两者 family 都叫 "armor"，跨层去重时会互相误杀，
  // 表现为「选了护甲载体，层 3 的护甲属性凭空消失」（模拟器实测到过）。
  // 加前缀后 family 与 SynergyLink 的引用格式完全一致，校验器也不用再剥前缀。
  const prefix = layer === 'ATTRIBUTE' ? 'attr' : layer === 'EFFECT' ? 'effect' : 'carrier';
  return {
    id,
    family: `${prefix}.${family}`,
    layer,
    name,
    base_type: CARRIER_TYPE[carrier],
    carrier_allow: [carrier],
    carrier_deny: [],
    element_allow: element ? [element] : [],
    excludes: [],
    weight: element ? 110 : 100,
    appearance_key: `fx.${family}`,
    tiers: buildTiers(base, precision, unit, element ? `${ELEMENT_NAME[element]}${name}` : name,
      CARRIER_TYPE[carrier], family),
  };
}

/* ------------------------------------------------------------------ */
/* 6. 载体与元素词条                                                    */
/* ------------------------------------------------------------------ */

function expandCarriers() {
  // 载体层必须有品质档，否则预算表塌了：
  // 如果只有属性层和效果层带品质，4 层全红最多 7×2 = 14 点，
  // 「17–28 点 = 传说」这一档永远达不到，传说级会变成不可达稀有度。
  // 载体品质 = 做工（基础属性倍率），元素品质 = 元素浓度。
  return CARRIERS.map(([id, name, type]) => ({
    id: `carrier.${id}`,
    family: `carrier.${id}`,
    layer: 'CARRIER',
    name,
    base_type: type,
    carrier_allow: [],
    carrier_deny: [],
    element_allow: [],
    excludes: [],
    weight: 100,
    appearance_key: `model.${id}`,
    tiers: buildTiers(5, 0, '%', '基础属性', type, id),
  }));
}

function expandElements() {
  return ELEMENTS.map(([id, name]) => ({
    id: `elem.${id}`,
    family: `elem.${id}`,
    layer: 'ELEMENT',
    name,
    base_type: 'ANY',
    carrier_allow: [],
    carrier_deny: [],
    element_allow: [],
    excludes: id === 'fire' ? ['elem.ice'] : id === 'ice' ? ['elem.fire'] : [],
    weight: 100,
    appearance_key: `elem.${id}`,
    tiers: buildTiers(5, 0, '%', '元素浓度', 'ANY', id),
  }));
}

/* ------------------------------------------------------------------ */
/* 7. 协同：60 手工 + 140 派生                                          */
/* ------------------------------------------------------------------ */

function buildSynergy(attributes, effects) {
  const links = [];

  // --- CURATED 60 条：玩家第一次抽卡就会撞上的组合 ---
  const curated = [
    ['elem.lightning', 'attr.fireRate', 'SYNERGY', 0.15, '高射速感电：触发概率 +15%'],
    ['elem.lightning', 'effect.chain', 'SYNERGY', 1, '连锁次数 +1'],
    ['elem.fire', 'effect.ignite', 'SYNERGY', 0.20, '燃烧伤害 +20%'],
    ['elem.ice', 'effect.freeze', 'SYNERGY', 1, '冻结时长 +1s'],
    ['elem.poison', 'effect.poisonStack', 'SYNERGY', 2, '中毒层数上限 +2'],
    ['elem.dark', 'effect.execute', 'SYNERGY', 0.05, '处决阈值 +5%'],
    ['elem.light', 'effect.shield', 'SYNERGY', 0.20, '护盾值 +20%'],
    ['elem.rock', 'attr.armor', 'SYNERGY', 1, '护甲 +1'],
    ['elem.wind', 'attr.moveSpeed', 'SYNERGY', 0.10, '移速 +10%'],
    ['attr.lifesteal', 'attr.critChance', 'SYNERGY', 0.15, '暴击时吸血翻倍概率 +15%'],
    ['attr.lifesteal', 'effect.lifestealAura', 'SYNERGY', 0.25, '吸血光环效果 +25%'],
    ['attr.critChance', 'attr.critDamage', 'SYNERGY', 0.20, '暴击伤害 +20%'],
    ['attr.critChance', 'effect.execute', 'SYNERGY', 0.05, '处决阈值 +5%'],
    ['attr.fireRate', 'effect.combo', 'SYNERGY', 0.15, '连击判定窗口 +15%'],
    ['attr.fireRate', 'attr.pierce', 'CONFLICT', -0.20, '单发伤害 −20%（射速与穿透争夺弹道）'],
    ['attr.pierce', 'effect.armorPierce', 'SYNERGY', 0.20, '破甲效果 +20%'],
    ['attr.attackSpeed', 'effect.combo', 'SYNERGY', 0.20, '连击上限 +20%'],
    ['attr.attackSpeed', 'effect.counter', 'SYNERGY', 0.15, '反击触发率 +15%'],
    ['attr.knockback', 'effect.freeze', 'SYNERGY', 1, '冻结期间击退距离 +1'],
    ['attr.knockback', 'effect.stun', 'SYNERGY', 0.5, '眩晕时长 +0.5s'],
    ['attr.aoeRadius', 'effect.explosion', 'SYNERGY', 0.25, '爆裂范围 +25%'],
    ['attr.aoeRadius', 'effect.deathBoom', 'SYNERGY', 0.25, '亡语爆炸范围 +25%'],
    ['attr.statusChance', 'effect.ignite', 'SYNERGY', 0.20, '点燃概率 +20%'],
    ['attr.statusChance', 'effect.poisonStack', 'SYNERGY', 0.20, '中毒概率 +20%'],
    ['attr.statusDuration', 'effect.slowField', 'SYNERGY', 1, '缓速场持续 +1s'],
    ['attr.summonDamage', 'effect.summonEnrage', 'SYNERGY', 0.25, '狂暴期攻击 +25%'],
    ['attr.summonHealth', 'effect.summonSplit', 'SYNERGY', 0.20, '分裂体生命 +20%'],
    ['attr.summonStability', 'effect.lastStand', 'SYNERGY', 0.30, '濒死爆发窗口 +30%'],
    ['attr.summonCount', 'effect.summonEnrage', 'CONFLICT', -0.15, '单体强度 −15%（数量与质量互斥）'],
    ['attr.summonSpeed', 'effect.blink', 'SYNERGY', 0.20, '闪现距离 +20%'],
    ['attr.duration', 'effect.auraBuff', 'SYNERGY', 0.25, '光环持续 +25%'],
    ['attr.potency', 'effect.explosion', 'SYNERGY', 0.20, '爆炸伤害 +20%'],
    ['attr.potency', 'effect.shield', 'SYNERGY', 0.20, '护盾值 +20%'],
    ['attr.healing', 'effect.shield', 'SYNERGY', 0.15, '护盾吸收 +15%'],
    ['attr.haste', 'effect.sprint', 'SYNERGY', 0.25, '疾跑速度 +25%'],
    ['attr.haste', 'effect.slowField', 'CONFLICT', -0.20, '疾跑效果 −20%（互相抵消）'],
    ['attr.maxHealth', 'effect.thornsBurst', 'SYNERGY', 0.15, '反伤 +15%'],
    ['attr.armor', 'effect.counter', 'SYNERGY', 0.20, '反击伤害 +20%'],
    ['attr.armor', 'effect.reflect', 'SYNERGY', 0.20, '反弹比例 +20%'],
    ['attr.armor', 'attr.moveSpeed', 'CONFLICT', -0.10, '移速 −10%（重甲代价）'],
    ['attr.thorns', 'effect.thornsBurst', 'SYNERGY', 0.20, '荆棘反伤 +20%'],
    ['attr.elementPower', 'attr.elemDamage', 'SYNERGY', 0.20, '元素伤害 +20%'],
    ['attr.elementPower', 'effect.ignite', 'SYNERGY', 0.15, '燃烧伤害 +15%'],
    ['attr.range', 'effect.chain', 'SYNERGY', 0.20, '连锁距离 +20%'],
    ['attr.range', 'attr.accuracy', 'SYNERGY', 0.10, '远距离精度 +10%'],
    ['attr.blockChance', 'effect.counter', 'SYNERGY', 0.15, '格挡后反击 +15%'],
    ['attr.cleave', 'effect.stun', 'SYNERGY', 0.15, '顺劈眩晕概率 +15%'],
    ['attr.shieldAmount', 'effect.shield', 'SYNERGY', 0.20, '护盾值 +20%'],
    ['attr.blastRadius', 'effect.explosion', 'SYNERGY', 0.30, '爆炸范围 +30%'],
    ['attr.blastDamage', 'effect.deathBoom', 'SYNERGY', 0.25, '亡语爆炸伤害 +25%'],
    ['attr.summonAoe', 'effect.deathBoom', 'SYNERGY', 0.20, '亡语范围 +20%'],
    ['attr.summonRegen', 'effect.summonSplit', 'SYNERGY', 0.15, '分裂体自愈 +15%'],
    ['attr.summonArmor', 'effect.shield', 'SYNERGY', 0.20, '召唤护盾 +20%'],
    ['attr.summonLifespan', 'effect.lastStand', 'SYNERGY', 0.25, '濒死续命 +25%'],
    ['effect.invisibility', 'effect.execute', 'SYNERGY', 0.10, '隐身首击处决阈值 +10%'],
    ['effect.sprint', 'effect.blink', 'SYNERGY', 0.15, '闪现后移速 +15%'],
    ['effect.nightVision', 'effect.auraBuff', 'SYNERGY', 0.10, '光环范围 +10%'],
    ['effect.fireResist', 'effect.shield', 'SYNERGY', 0.10, '抗性转护盾 +10%'],
    ['effect.magnet', 'effect.combo', 'CONFLICT', -0.10, '连击判定 −10%（拾取打断节奏）'],
    ['attr.summonElement', 'effect.summonEnrage', 'SYNERGY', 0.15, '狂暴期元素伤害 +15%'],
  ];

  for (const [from, to, type, delta, desc] of curated) {
    links.push({ from: familyRef(from), to: familyRef(to), type, delta, desc, source: 'CURATED' });
  }

  // --- DERIVED：语义成立但对白不惊艳的组合，按矩阵派生 ---
  // 关键：必须对 curated 去重。实测手工那 60 条与矩阵表有 45 条撞车，
  // 直接拼接会产出 45 条「同一对词条出现两行提示」的冗余数据。
  // 去重后真实成立的组合约 155 条 —— 这个数比 200 诚实，不灌水。
  const taken = new Set(links.map(pairKey));
  let skipped = 0;
  const push = (from, to, delta, desc) => {
    const key = [from, to].sort().join('|');
    if (taken.has(key)) { skipped++; return false; }
    taken.add(key);
    links.push({ from, to, type: 'SYNERGY', delta, desc, source: 'DERIVED' });
    return true;
  };

  for (const [from, to, delta, desc] of DERIVED_ELEM_EFFECT) push(`elem.${from}`, `effect.${to}`, delta, desc);
  for (const [from, to, delta, desc] of DERIVED_ATTR_EFFECT) push(`attr.${from}`, `effect.${to}`, delta, desc);
  for (const [from, to, delta, desc] of DERIVED_ATTR_ATTR) push(`attr.${from}`, `attr.${to}`, delta, desc);
  for (const [from, to, delta, desc] of DERIVED_ELEM_ATTR) push(`elem.${from}`, `attr.${to}`, delta, desc);

  console.log(`  协同去重：跳过 ${skipped} 条与手工表重复的组合`);
  return links;
}

// 无向去重键：A→B 与 B→A 是同一对
function pairKey(l) {
  return [l.from, l.to].sort().join('|');
}

// family 引用统一成 `attr.xxx` / `effect.xxx` / `elem.xxx` 形态，校验器按 family 匹配
function familyRef(ref) {
  if (ref.startsWith('attr.') || ref.startsWith('effect.') || ref.startsWith('elem.')) return ref;
  return ref;
}

function hasPair(list, family, carrier) {
  return list.some((a) => a.family === family && a.carrier_allow.includes(carrier));
}

/* ------------------------------------------------------------------ */
/* 8. 定向卡（27 张）                                                   */
/* ------------------------------------------------------------------ */

function buildTargetCards() {
  const cards = [];
  for (const [id] of CARRIERS) {
    cards.push({
      id: `target.carrier.${id}`,
      target_layer: 'CARRIER',
      target_affix: `carrier.${id}`,
      quality_floor: 'NONE',
      name: `定向·${CARRIER_NAME[id]}`,
      base_type: CARRIER_TYPE[id],
    });
  }
  for (const [id, name] of ELEMENTS) {
    cards.push({
      id: `target.elem.${id}`,
      target_layer: 'ELEMENT',
      target_affix: `elem.${id}`,
      quality_floor: 'NONE',
      name: `定向·${name}`,
      base_type: 'ANY',
    });
  }
  return cards;
}

/* ------------------------------------------------------------------ */
/* 9. 输出                                                              */
/* ------------------------------------------------------------------ */

function assert(cond, msg) {
  if (!cond) { console.error('✗ ' + msg); process.exit(1); }
}

function writeJson(name, obj) {
  const path = join(OUT_DIR, name);
  writeFileSync(path, JSON.stringify(obj, null, 2) + '\n', 'utf8');
  const count = Array.isArray(obj.affixes) ? obj.affixes.length : (obj.links ?? obj.cards ?? []).length;
  console.log(`  ${name.padEnd(28)} ${String(count).padStart(4)} 条`);
}

function main() {
  mkdirSync(OUT_DIR, { recursive: true });

  const carriers = expandCarriers();
  const elements = expandElements();
  const attributes = expandAttributes();
  const effects = expandEffects();
  const links = buildSynergy(attributes, effects);
  const cards = buildTargetCards();

  const total = carriers.length + elements.length + attributes.length + effects.length;

  console.log('生成卡表 → ' + OUT_DIR);
  writeJson('affixes.carrier.json', { version: DATA_VERSION, layer: 'CARRIER', affixes: carriers });
  writeJson('affixes.element.json', { version: DATA_VERSION, layer: 'ELEMENT', affixes: elements });
  writeJson('affixes.attribute.json', { version: DATA_VERSION, layer: 'ATTRIBUTE', affixes: attributes });
  writeJson('affixes.effect.json', { version: DATA_VERSION, layer: 'EFFECT', affixes: effects });
  writeJson('synergy.json', { version: DATA_VERSION, links });
  writeJson('target_cards.json', { version: DATA_VERSION, cards });
  console.log(`\n词条合计：${total}（载体 ${carriers.length} / 元素 ${elements.length} / 属性 ${attributes.length} / 效果 ${effects.length}）`);
  const curated = links.filter((l) => l.source === 'CURATED').length;
  console.log(`协同合计：${links.length}（手工 ${curated} / 派生 ${links.length - curated}）`);

  assert(total === 450, `词条总数应为 450，实际 ${total}`);
  // 协同定为 155 而不是原计划的 200：矩阵派生与手工表去重后有 45 条撞车，
  // 硬凑 200 只能靠加冗余组合。155 是真实成立的组合数，不灌水。
  assert(links.length === 155, `协同应为 155 条（去重后），实际 ${links.length}`);
  console.log('✓ 目标数量达标');
}

main();
