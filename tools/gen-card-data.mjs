#!/usr/bin/env node
/**
 * 卡表生成器 —— 族定义 → 词条表
 *
 * 设计要点（见 docs §29.1）：
 *   手写上千条词条不可行（ID 会拼错、数值会漂移、文案会不一致）。
 *   所以手写的是「族」，用「族 × 载体」展开成词条。
 *   笔数从加法变成乘法，人手成本回到「族」的量级。
 *
 * 展开策略：按「载体 × 类（BASE/SIG/ELEM/SUP）」铺设，每轮每个载体各取 1 条。
 *   理由：① 按「族序优先」截断会把靠后的族整族砍光；
 *        ② 元素族一条就产 9 条，会挤掉签名族，让新载体和旧载体没区别。
 *
 * 用法：node tools/gen-card-data.mjs
 */

import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT_DIR = join(__dirname, '..', 'core', 'src', 'main', 'resources', 'data');
const DATA_VERSION = '2.0.0';

/* ------------------------------------------------------------------ */
/* 1. 载体与元素                                                        */
/* ------------------------------------------------------------------ */

/**
 * 载体表 —— 每个载体是一个「外观 + 玩法轴」的组合，不是同义词。
 *
 * 加新载体的唯一标准：它必须提供一种现有载体给不了的**形态或打法**。
 * 「长剑」和「短剑」这种差异只在数值上的不进来 —— 数值差异由品质档和属性族表达，
 * 载体负责的是玩家一眼能认出的东西（模型、动画、怎么用）。
 *
 * 分类轴（三类各自的「形态维度」）：
 *   装备   — 按「交战距离 × 发力方式」分：远程弹道 / 近战挥击 / 长杆突刺 / 护具格挡
 *   消耗品 — 按「生效方式」分：食用 / 饮下 / 施放 / 投掷 / 布置 / 生长
 *   召唤物 — 按「机动与站位」分：地面跟随 / 空中 / 固定部署 / 群集
 *
 * 字段：id, 中文名, base_type
 */
const CARRIERS = [
  // ---- 装备 12：6 原有 + 6 新增 ----
  ['gun', '枪', 'EQUIPMENT'],        // 远程 · 弹道 · 高射速
  ['sword', '剑', 'EQUIPMENT'],      // 近战 · 挥击 · 均衡
  ['bow', '弓', 'EQUIPMENT'],        // 远程 · 拉弓 · 蓄力持续
  ['axe', '斧', 'EQUIPMENT'],        // 近战 · 劈砍 · 群伤
  ['staff', '法杖', 'EQUIPMENT'],    // 远程 · 法术 · 元素
  ['armor', '护甲', 'EQUIPMENT'],    // 护具 · 被动承受
  ['dagger', '匕首', 'EQUIPMENT'],   // 新增 · 近战极快 · 暴击/背刺/潜行
  ['hammer', '重锤', 'EQUIPMENT'],   // 新增 · 钝击 · 破甲/眩晕/冲击
  ['spear', '长矛', 'EQUIPMENT'],    // 新增 · 长杆 · 触及/贯穿（补近战与远程之间的空档）
  ['shield', '盾', 'EQUIPMENT'],     // 新增 · 护具 · 主动格挡/还击（护甲是被动承受）
  ['crossbow', '弩', 'EQUIPMENT'],   // 新增 · 远程 · 蓄力单发/装填
  ['gauntlet', '拳套', 'EQUIPMENT'], // 新增 · 徒手 · 连段/招架（无弹道、无挥击轨迹）

  // ---- 消耗品 10：5 原有 + 5 新增 ----
  ['apple', '苹果', 'CONSUMABLE'],   // 食用 · 即时
  ['potion', '药水', 'CONSUMABLE'],  // 饮下 · 即时增益
  ['scroll', '卷轴', 'CONSUMABLE'],  // 施放 · 定向法术
  ['bomb', '炸弹', 'CONSUMABLE'],    // 投掷 · 一次性爆裂
  ['feast', '盛宴', 'CONSUMABLE'],   // 放置 · 群体共享
  ['flask', '烧瓶', 'CONSUMABLE'],   // 新增 · 投掷 · 碎裂残留（炸弹是瞬时，烧瓶是持续）
  ['rune', '符文', 'CONSUMABLE'],    // 新增 · 布置 · 触发式（需要敌人踩进来才生效）
  ['mushroom', '蘑菇', 'CONSUMABLE'],// 新增 · 食用 · 随机/混乱（呼应失控度系统）
  ['censer', '香炉', 'CONSUMABLE'],  // 新增 · 放置 · 持续范围光环
  ['seed', '种子', 'CONSUMABLE'],    // 新增 · 生长 · 延时生效（越早种越划算）

  // ---- 召唤物 13：7 原有 + 6 新增 ----
  ['dragon', '龙', 'SUMMON'],        // 地面 · 单体巨兽 · 高元素
  ['beast', '兽', 'SUMMON'],         // 地面 · 追猎 · 机动
  ['spirit', '精灵', 'SUMMON'],      // 地面 · 辅助 · 光环
  ['golem', '傀儡', 'SUMMON'],       // 地面 · 坦克 · 高护甲
  ['wraith', '幽灵', 'SUMMON'],      // 地面 · 穿行 · 状态
  ['swarm', '虫群', 'SUMMON'],       // 群集 · 数量 · 低单体
  ['crystal', '晶簇', 'SUMMON'],     // 固定 · 结晶 · 范围
  ['undead', '骸骨', 'SUMMON'],      // 新增 · 群集 · 死亡再生（越打越多）
  ['bird', '猎鹰', 'SUMMON'],        // 新增 · 空中 · 俯冲/侦察（唯一的三维机动）
  ['treant', '树人', 'SUMMON'],      // 新增 · 固定 · 扎根自愈（高血低移速）
  ['totem', '图腾', 'SUMMON'],       // 新增 · 固定部署 · 纯光环源
  ['wisp', '光灵', 'SUMMON'],        // 新增 · 空中 · 纯辅助（治疗/照明）
  ['serpent', '巨蛇', 'SUMMON'],     // 新增 · 地面 · 绞缠/叠毒
];

const ELEMENTS = [
  ['lightning', '雷'], ['fire', '火'], ['ice', '冰'], ['poison', '毒'], ['light', '光'],
  ['dark', '暗'], ['rock', '岩'], ['wind', '风'], ['physical', '物理'],
];

const CARRIER_TYPE = Object.fromEntries(CARRIERS.map(([id, , t]) => [id, t]));
const CARRIER_NAME = Object.fromEntries(CARRIERS.map(([id, n]) => [id, n]));
const ELEMENT_NAME = Object.fromEntries(ELEMENTS);

/* ------------------------------------------------------------------ */
/* 2. 属性族                                                           */
/*    类：BASE 通用 / SIG 专属签名 / SUP 补充 / ELEM 元素特化            */
/*    字段：family, 名称, 单位, 基础值, 精度, 载体, [元素维度]           */
/* ------------------------------------------------------------------ */

const EQUIP = ['gun', 'sword', 'bow', 'axe', 'staff', 'armor',
  'dagger', 'hammer', 'spear', 'shield', 'crossbow', 'gauntlet'];
const SUMMON = ['dragon', 'beast', 'spirit', 'golem', 'wraith', 'swarm', 'crystal',
  'undead', 'bird', 'treant', 'totem', 'wisp', 'serpent'];
const CONS = ['apple', 'potion', 'scroll', 'bomb', 'feast',
  'flask', 'rune', 'mushroom', 'censer', 'seed'];

const ATTRIBUTE_FAMILIES = [
  // --- 装备通用（BASE）：每个新载体的「底盘」都从这里来 ---
  ['damage', '伤害', '', 2, 0, ['gun', 'sword', 'bow', 'axe', 'staff', 'bomb', 'scroll',
    'dagger', 'hammer', 'spear', 'crossbow', 'gauntlet', 'flask', 'rune']],
  ['critChance', '暴击率', '%', 4, 0, ['gun', 'sword', 'bow', 'axe', 'bomb', 'dagger', 'crossbow', 'gauntlet']],
  ['critDamage', '暴击伤害', '%', 8, 0, ['gun', 'sword', 'bow', 'axe', 'bomb', 'dagger', 'crossbow', 'spear']],
  ['lifesteal', '吸血', '%', 3, 0, ['gun', 'sword', 'axe', 'staff', 'dagger', 'gauntlet', 'serpent']],
  ['durability', '耐久', '%', 15, 0, EQUIP],
  ['statusChance', '状态触发率', '%', 5, 0, ['gun', 'sword', 'bow', 'staff', 'bomb', 'scroll',
    'dagger', 'hammer', 'spear', 'gauntlet', 'serpent', 'mushroom']],
  ['statusDuration', '状态持续', 's', 0.5, 1, ['gun', 'sword', 'bow', 'staff', 'bomb', 'scroll',
    'dagger', 'hammer', 'spear', 'gauntlet', 'serpent']],
  ['elementPower', '元素强度', '%', 6, 0, ['staff', 'gun', 'bow', 'scroll', 'wisp', 'censer', 'totem', 'rune']],
  ['fireRate', '射速', '%', 6, 0, ['gun', 'bow', 'crossbow']],
  ['pierce', '穿透', '', 1, 0, ['gun', 'bow', 'crossbow', 'spear']],
  ['range', '射程', '', 1.5, 1, ['gun', 'bow', 'staff', 'bomb', 'scroll', 'crossbow', 'spear', 'censer', 'wisp']],
  ['accuracy', '精度', '%', 5, 0, ['gun', 'bow', 'crossbow', 'spear']],
  ['attackSpeed', '攻速', '%', 5, 0, ['sword', 'axe', 'staff', 'dagger', 'gauntlet', 'spear', 'hammer']],
  ['knockback', '击退', '', 0.4, 1, ['sword', 'axe', 'staff', 'hammer', 'shield', 'gauntlet']],
  ['aoeRadius', '范围', '', 0.5, 1, ['staff', 'bomb', 'scroll', 'hammer', 'flask', 'censer', 'totem']],
  ['cooldownReduction', '冷却缩减', '%', 5, 0, ['staff', 'scroll', 'rune', 'totem']],
  ['armor', '护甲', '', 1, 0, ['armor', 'shield', 'treant']],
  ['maxHealth', '生命上限', '', 2, 0, ['armor', 'feast', 'shield', 'treant', 'golem']],
  ['moveSpeed', '移速', '%', 3, 0, ['armor', 'apple', 'feast', 'bird', 'wisp']],
  ['thorns', '荆棘', '', 2, 0, ['armor', 'shield', 'treant', 'crystal']],
  ['ammoCapacity', '弹匣容量', '', 2, 0, ['gun', 'crossbow']],
  ['drawSpeed', '拉弓上弦', '%', 6, 0, ['bow', 'crossbow']],
  ['cleave', '顺劈', '', 1, 0, ['axe', 'spear', 'gauntlet', 'hammer']],
  ['blockChance', '格挡率', '%', 4, 0, ['sword', 'armor', 'shield', 'gauntlet']],

  // --- 召唤通用（BASE）---
  ['summonHealth', '召唤生命', '%', 10, 0, SUMMON],
  ['summonDamage', '召唤攻击', '%', 8, 0, SUMMON],
  ['summonSpeed', '召唤移速', '%', 4, 0, SUMMON],
  ['summonStability', '稳定性', '%', 6, 0, SUMMON],

  // --- 召唤专属（BASE）---
  ['summonCount', '召唤数量', '', 1, 0, ['dragon', 'golem', 'swarm', 'beast', 'undead', 'bird', 'serpent']],
  ['summonLifespan', '存续时间', 's', 15, 0, ['beast', 'spirit', 'wraith', 'swarm', 'undead', 'bird', 'wisp']],
  ['summonArmor', '召唤护甲', '', 2, 0, ['golem', 'dragon', 'beast', 'treant', 'totem', 'undead']],
  ['summonRegen', '召唤自愈', '%', 1, 0, ['spirit', 'crystal', 'treant', 'wisp']],
  ['summonElement', '召唤元素强度', '%', 6, 0, ['dragon', 'spirit', 'wraith', 'crystal', 'wisp', 'serpent']],
  ['summonAoe', '召唤攻击范围', '', 0.5, 1, ['swarm', 'crystal', 'dragon', 'totem', 'bird']],

  // --- 消耗品通用（BASE）---
  ['duration', '持续时间', 's', 5, 0, CONS],
  ['potency', '效果强度', '%', 10, 0, CONS],
  ['charges', '使用次数', '', 1, 0, CONS],

  // --- 消耗品专属（BASE）---
  ['healing', '治疗', '', 3, 0, ['apple', 'potion', 'feast', 'wisp', 'mushroom', 'seed']],
  ['hunger', '饱食', '', 3, 0, ['apple', 'feast', 'mushroom']],
  ['haste', '疾跑', '%', 10, 0, ['apple', 'flask']],
  ['xpBonus', '经验获取', '%', 8, 0, ['potion', 'mushroom']],
  ['lootBonus', '掉落加成', '%', 5, 0, ['potion', 'seed']],
  ['blastRadius', '爆炸半径', '', 0.5, 1, ['bomb', 'flask', 'rune']],
  ['blastDamage', '爆炸伤害', '', 3, 0, ['bomb', 'flask', 'rune']],
  ['shieldAmount', '护盾值', '', 3, 0, ['potion', 'censer', 'shield']],

  // --- 补充族（SUP）：专为冷门载体扩池 ---
  // 起因：元素特化族（elemResist 等）虽然产生几十条数据，
  // 但「去同族」后只算 1 个族，对池子多样性几乎没有贡献。
  // 所以这些载体需要真正的基础族，否则抽 5 张会把池子掏空。
  ['regen', '生命回复', '%', 1, 0, ['armor', 'apple', 'potion', 'feast', 'spirit', 'golem', 'beast',
    'treant', 'wisp', 'mushroom', 'seed']],
  ['resist', '抗性', '%', 4, 0, ['armor', 'potion', 'feast', 'golem', 'dragon', 'scroll',
    'shield', 'totem', 'treant']],
  ['summonCrit', '召唤暴击', '%', 5, 0, ['dragon', 'wraith', 'spirit', 'crystal', 'swarm', 'golem',
    'beast', 'undead', 'bird', 'serpent']],
  ['summonStatus', '召唤状态触发', '%', 4, 0, ['dragon', 'wraith', 'spirit', 'crystal', 'swarm',
    'serpent', 'undead']],
  ['summonAggro', '召唤仇恨', '%', 10, 0, ['golem', 'beast', 'dragon', 'wraith', 'swarm',
    'undead', 'totem', 'treant']],
  ['stackLimit', '叠加上限', '', 1, 0, CONS],
  ['triggerRadius', '触发范围', '', 0.5, 1, ['bomb', 'scroll', 'apple', 'feast', 'flask', 'rune', 'seed', 'mushroom']],
  ['useSpeed', '使用速度', '%', 8, 0, ['apple', 'potion', 'feast', 'scroll', 'flask', 'mushroom', 'seed']],
  ['agility', '灵活', '%', 3, 0, ['armor', 'apple', 'beast', 'wraith', 'swarm', 'dagger', 'bird', 'gauntlet']],
  ['focus', '专注', '%', 4, 0, ['staff', 'scroll', 'potion', 'sword', 'crossbow', 'wisp', 'totem']],

  // --- 元素特化（ELEM）---
  ['elemDamage', '元素伤害', '%', 6, 0, ['gun', 'sword', 'bow', 'staff', 'dagger', 'crossbow', 'spear'], ELEMENTS.map((e) => e[0])],
  ['elemResist', '元素抗性', '%', 5, 0, ['armor', 'shield', 'totem'], ELEMENTS.map((e) => e[0])],
  ['elemAffinity', '元素亲和', '%', 5, 0, ['staff', 'wisp', 'censer', 'rune'], ELEMENTS.map((e) => e[0])],
  ['elemAura', '元素光环', '%', 4, 0, ['spirit', 'crystal', 'totem', 'wisp'], ELEMENTS.map((e) => e[0])],
  ['elemSummonDamage', '召唤元素伤害', '%', 7, 0, ['dragon', 'wraith', 'serpent', 'bird', 'undead'], ELEMENTS.map((e) => e[0])],
  ['elemSummonResist', '召唤元素抗性', '%', 4, 0, ['golem', 'crystal', 'treant', 'totem'], ELEMENTS.map((e) => e[0])],

  // --- 装备签名（SIG）：每个新载体 2 条，只给它自己或同轴的载体 ---
  // 这些族存在的意义：让「匕首」和「剑」不只是数值不同，
  // 而是各自有一条只有自己能滚到的属性轴。
  ['backstab', '背刺伤害', '%', 8, 0, ['dagger', 'sword']],
  ['bleedDamage', '流血伤害', '%', 6, 0, ['dagger', 'axe', 'serpent']],
  ['armorBreak', '破甲值', '', 2, 0, ['hammer', 'axe']],
  ['impactPower', '冲击力', '', 1, 0, ['hammer', 'shield']],
  ['reach', '触及', '', 0.5, 1, ['spear', 'staff', 'censer']],
  ['thrustDamage', '突刺伤害', '%', 7, 0, ['spear', 'crossbow']],
  ['blockPower', '格挡强度', '%', 6, 0, ['shield', 'sword']],
  ['staggerResist', '硬直抵抗', '%', 5, 0, ['shield', 'armor', 'golem']],
  ['reloadSpeed', '装填速度', '%', 8, 0, ['crossbow', 'gun']],
  ['firstShotDamage', '首发伤害', '%', 10, 0, ['crossbow', 'gun']],
  ['parryChance', '招架率', '%', 4, 0, ['gauntlet', 'sword', 'shield']],
  ['comboStage', '连段上限', '', 1, 0, ['gauntlet', 'dagger']],

  // --- 召唤签名（SIG）：每个新载体 2 条 ---
  ['summonRevive', '复苏次数', '', 1, 0, ['undead', 'wraith']],
  ['summonSacrifice', '献祭强度', '%', 6, 0, ['undead', 'wraith', 'serpent']],
  ['summonFlySpeed', '飞行速度', '%', 5, 0, ['bird', 'wraith', 'spirit']],
  ['diveDamage', '俯冲伤害', '%', 9, 0, ['bird', 'dragon']],
  ['rootStrength', '扎根强度', '%', 6, 0, ['treant', 'totem']],
  ['growthRate', '成长速率', '%', 5, 0, ['treant', 'seed', 'crystal']],
  ['auraRadius', '光环范围', '', 0.5, 1, ['totem', 'censer', 'spirit']],
  ['auraPower', '光环强度', '%', 5, 0, ['totem', 'censer', 'wisp']],
  ['deploySpeed', '部署速度', '%', 8, 0, ['totem', 'rune']],
  ['healPower', '治疗强度', '%', 8, 0, ['wisp', 'potion', 'apple', 'feast', 'spirit', 'seed']],
  ['lightRadius', '照明范围', '', 1, 0, ['wisp', 'crystal']],
  ['poisonPotency', '毒性强度', '%', 5, 0, ['serpent', 'swarm', 'wraith', 'potion', 'mushroom']],

  // --- 消耗品签名（SIG）：每个新载体 2 条（香炉与种子各 1 条，另靠通用族补足）---
  ['splashRadius', '溅射半径', '', 0.5, 1, ['flask', 'bomb']],
  ['lingering', '残留时间', 's', 3, 0, ['flask', 'censer', 'mushroom']],
  ['runePower', '符文强度', '%', 7, 0, ['rune', 'scroll']],
  ['armTime', '布防时间', 's', 2, 0, ['rune', 'bomb', 'seed']],
  ['chaosBias', '混乱偏向', '%', 8, 0, ['mushroom', 'flask']],
  ['growTime', '生长时间', 's', 8, 0, ['seed', 'treant']],
  ['yieldBonus', '产出加成', '%', 6, 0, ['seed', 'feast', 'apple']],
  ['censerRate', '熏燃速率', '%', 6, 0, ['censer', 'mushroom']],
];

/** 签名族清单：用于「按类交错」铺设时区分对待。 */
const SIG_ATTR = new Set([
  'backstab', 'bleedDamage', 'armorBreak', 'impactPower', 'reach', 'thrustDamage',
  'blockPower', 'staggerResist', 'reloadSpeed', 'firstShotDamage', 'parryChance', 'comboStage',
  'summonRevive', 'summonSacrifice', 'summonFlySpeed', 'diveDamage', 'rootStrength', 'growthRate',
  'auraRadius', 'auraPower', 'deploySpeed', 'healPower', 'lightRadius', 'poisonPotency',
  'splashRadius', 'lingering', 'runePower', 'armTime', 'chaosBias', 'growTime', 'yieldBonus', 'censerRate',
]);

/** 补充族清单：池子宽但语义泛，抽卡时不该挤掉签名族。 */
const SUP_ATTR = new Set([
  'regen', 'resist', 'summonCrit', 'summonStatus', 'summonAggro',
  'stackLimit', 'triggerRadius', 'useSpeed', 'agility', 'focus',
]);

const TARGET_ATTRIBUTE = 700;

/* ------------------------------------------------------------------ */
/* 3. 效果族                                                           */
/*    类：BASE 通用 / SIG 载体签名                                      */
/*    字段：family, 名称, 载体, 元素限定?, 与哪些族冲突                  */
/* ------------------------------------------------------------------ */

const EFFECT_FAMILIES = [
  // --- 通用（BASE）---
  ['ignite', '燃烧', ['gun', 'sword', 'bow', 'axe', 'staff', 'bomb', 'flask', 'censer', 'hammer'], ['fire'], []],
  ['shock', '感电', ['gun', 'bow', 'staff', 'dragon', 'spirit', 'bomb', 'crossbow', 'totem'], ['lightning'], []],
  ['freeze', '冰冻', ['sword', 'staff', 'bow', 'armor', 'crystal', 'bomb', 'flask'], ['ice'], []],
  ['poisonStack', '淬毒', ['sword', 'axe', 'bow', 'swarm', 'spirit', 'potion', 'dagger', 'serpent', 'mushroom', 'flask'], ['poison'], []],
  ['blind', '致盲', ['staff', 'bomb', 'gun', 'wraith', 'scroll', 'apple', 'mushroom', 'censer'], null, []],
  ['stun', '眩晕', ['axe', 'staff', 'bomb', 'golem', 'dragon', 'armor', 'bow', 'hammer', 'shield', 'spear', 'rune'], null, []],
  ['explosion', '爆裂', ['bomb', 'staff', 'gun', 'axe', 'crystal', 'swarm', 'bow', 'flask', 'rune', 'hammer', 'crossbow', 'mushroom'], null, ['freeze']],
  ['chain', '连锁', ['staff', 'gun', 'bow', 'spirit', 'wraith', 'crystal', 'totem', 'rune'], null, ['execute']],
  ['lifestealAura', '吸血光环', ['sword', 'axe', 'armor', 'wraith', 'dragon', 'spirit', 'undead', 'serpent', 'dagger', 'gauntlet'], null, ['execute']],
  ['thornsBurst', '荆棘反伤', ['armor', 'sword', 'golem', 'beast', 'crystal', 'staff', 'shield', 'treant', 'gauntlet'], null, []],
  ['shield', '护盾', ['armor', 'potion', 'golem', 'spirit', 'scroll', 'apple', 'feast', 'shield', 'totem', 'wisp', 'undead', 'rune'], null, []],
  ['sprint', '疾跑', ['apple', 'potion', 'armor', 'beast', 'wraith', 'scroll', 'feast', 'swarm', 'bird', 'gauntlet'], null, ['slowField']],
  ['invisibility', '隐身', ['potion', 'wraith', 'scroll', 'armor', 'spirit', 'feast', 'bow', 'dagger'], null, ['auraBuff']],
  ['jumpBoost', '跃升', ['armor', 'apple', 'beast', 'swarm', 'scroll', 'potion', 'feast', 'bird'], null, []],
  ['nightVision', '夜视', ['potion', 'scroll', 'armor', 'apple', 'wraith', 'spirit', 'wisp', 'bird'], null, []],
  ['waterBreathing', '水下呼吸', ['potion', 'armor', 'scroll', 'apple', 'spirit', 'beast', 'wisp'], null, []],
  ['fireResist', '火焰抗性', ['armor', 'potion', 'apple', 'golem', 'dragon', 'scroll', 'censer'], null, []],
  ['summonSplit', '分裂', ['swarm', 'crystal', 'spirit', 'wraith', 'beast', 'golem', 'undead', 'mushroom'], null, ['summonEnrage']],
  ['summonEnrage', '狂暴', ['beast', 'dragon', 'wraith', 'swarm', 'golem', 'crystal', 'serpent', 'undead'], null, ['summonSplit']],
  ['deathBoom', '亡语爆炸', ['bomb', 'golem', 'crystal', 'swarm', 'dragon', 'wraith', 'undead', 'rune'], null, []],
  ['magnet', '磁吸', ['armor', 'gun', 'apple', 'scroll', 'potion', 'sword', 'feast', 'seed'], null, []],
  ['counter', '反击', ['sword', 'armor', 'axe', 'golem', 'beast', 'dragon', 'feast', 'swarm', 'shield', 'gauntlet', 'hammer', 'spear'], null, []],
  ['reflect', '反弹', ['armor', 'staff', 'sword', 'crystal', 'spirit', 'golem', 'shield', 'totem'], null, []],
  ['armorPierce', '破甲', ['gun', 'bow', 'axe', 'wraith', 'staff', 'sword', 'hammer', 'spear', 'crossbow', 'bird'], null, []],
  ['combo', '连击', ['gun', 'sword', 'bow', 'axe', 'staff', 'swarm', 'dagger', 'gauntlet', 'crossbow', 'hammer', 'spear'], null, []],
  ['execute', '处决', ['sword', 'axe', 'gun', 'bow', 'dragon', 'wraith', 'dagger', 'serpent', 'spear', 'bird'], null, ['chain', 'lifestealAura']],
  ['slowField', '缓速场', ['staff', 'crystal', 'spirit', 'armor', 'scroll', 'bomb', 'treant', 'totem', 'serpent', 'flask', 'rune'], null, ['sprint']],
  ['blink', '闪现', ['wraith', 'spirit', 'scroll', 'potion', 'armor', 'apple', 'dagger', 'bird'], null, []],
  ['auraBuff', '增益光环', ['spirit', 'dragon', 'crystal', 'golem', 'beast', 'armor', 'feast', 'totem', 'censer', 'wisp'], null, ['invisibility']],
  ['lastStand', '濒死爆发', ['armor', 'apple', 'potion', 'sword', 'beast', 'scroll', 'feast', 'shield', 'gauntlet', 'undead'], null, []],

  // --- 装备签名（SIG）---
  // 每个新载体 2 条：一条强化它「本来就会做」的事，一条给它独有的机制。
  ['bleed', '流血', ['dagger', 'sword', 'axe', 'spear', 'serpent'], null, []],
  ['shadowStep', '暗影步', ['dagger', 'wraith', 'bird'], null, []],
  ['shatter', '碎甲', ['hammer', 'axe', 'crossbow'], null, ['armorPierce']],
  ['shockwave', '震荡波', ['hammer', 'golem', 'shield', 'totem'], null, []],
  ['pierceLine', '贯穿', ['spear', 'crossbow', 'gun'], null, []],
  ['impale', '钉刺', ['spear', 'crystal', 'serpent'], null, []],
  ['bulwark', '壁垒', ['shield', 'golem', 'armor', 'totem'], null, []],
  ['riposte', '还击', ['shield', 'sword', 'gauntlet'], null, []],
  ['volley', '齐射', ['crossbow', 'bow', 'gun'], null, []],
  ['steady', '定装', ['crossbow', 'gun', 'bow'], null, []],
  ['flurry', '连打', ['gauntlet', 'dagger', 'swarm'], null, []],
  ['ironFist', '铁拳', ['gauntlet', 'beast', 'hammer'], null, []],

  // --- 召唤签名（SIG）---
  ['bonePile', '骨堆', ['undead', 'swarm'], null, []],
  ['soulHarvest', '收魂', ['undead', 'wraith', 'serpent'], null, []],
  ['dive', '俯冲', ['bird', 'dragon', 'wraith'], null, []],
  ['scout', '侦察', ['bird', 'wisp', 'spirit'], null, []],
  ['rootSnare', '根须缠', ['treant', 'serpent', 'seed'], null, []],
  ['photosynth', '光合', ['treant', 'wisp', 'seed'], null, []],
  ['wardAura', '守护光环', ['totem', 'censer', 'spirit', 'shield'], null, []],
  ['pulse', '脉冲', ['totem', 'crystal', 'wisp'], null, []],
  ['mend', '愈合', ['wisp', 'spirit', 'potion', 'apple', 'seed'], null, []],
  ['holyLight', '圣光', ['wisp', 'crystal', 'scroll', 'shield'], null, []],
  ['venom', '剧毒', ['serpent', 'swarm', 'wraith', 'mushroom'], null, []],
  ['constrict', '绞缠', ['serpent', 'beast', 'treant'], null, []],

  // --- 消耗品签名（SIG）---
  ['splash', '泼溅', ['flask', 'bomb', 'potion'], null, []],
  ['volatile', '挥发', ['flask', 'censer', 'mushroom'], null, []],
  ['inscribe', '铭刻', ['rune', 'scroll', 'censer'], null, []],
  ['trap', '陷阱', ['rune', 'bomb', 'seed'], null, []],
  ['spore', '孢子', ['mushroom', 'treant', 'censer', 'swarm'], null, []],
  ['mutate', '变异', ['mushroom', 'flask', 'potion'], null, []],
  ['incense', '熏香', ['censer', 'feast', 'wisp'], null, []],
  ['sprout', '萌芽', ['seed', 'treant', 'crystal'], null, []],
  ['harvest', '采收', ['seed', 'apple', 'feast'], null, []],
  ['burstRoot', '破土', ['seed', 'golem', 'treant'], null, []],
];

/** 通用效果族清单：SIG 之外的都是 BASE。 */
const BASE_EFFECTS = new Set([
  'ignite', 'shock', 'freeze', 'poisonStack', 'blind', 'stun', 'explosion', 'chain',
  'lifestealAura', 'thornsBurst', 'shield', 'sprint', 'invisibility', 'jumpBoost',
  'nightVision', 'waterBreathing', 'fireResist', 'summonSplit', 'summonEnrage',
  'deathBoom', 'magnet', 'counter', 'reflect', 'armorPierce', 'combo', 'execute',
  'slowField', 'blink', 'auraBuff', 'lastStand',
]);

const TARGET_EFFECT = 380;

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
/* 5. 展开：按「载体 × 类」铺设                                          */
/* ------------------------------------------------------------------ */

/**
 * 类的交错顺序。每个载体拿牌时按 BASE → SIG → ELEM → SUP 循环，
 * 一轮各取 1 条，取完再从头。
 *
 * 为什么要有这一步：元素特化族一条族就产出 9 条（9 个元素），
 * 如果按「族序平铺」，一个载体的候选前 9 位会被元素占满，
 * 名额一截断，先死的一定是排在后面的签名族 ——
 * 表现就是「匕首和剑除了数值没有任何区别」。
 * 交错之后，预算停在哪一轮，每一类都已经有代表。
 */
const CLASS_CYCLE = ['BASE', 'SIG', 'ELEM', 'SUP'];

function stratify(families) {
  const buckets = new Map(); // carrier -> { BASE:[], SIG:[], ELEM:[], SUP:[] }
  for (const f of families) {
    for (const c of f.candidates) {
      const carrier = c.carrier_allow[0];
      if (!buckets.has(carrier)) {
        buckets.set(carrier, { BASE: [], SIG: [], ELEM: [], SUP: [] });
      }
      buckets.get(carrier)[f.cls].push(c);
    }
  }
  const out = new Map();
  for (const [carrier, b] of buckets) {
    const list = [];
    for (let round = 0; ; round++) {
      let added = 0;
      for (const cls of CLASS_CYCLE) {
        if (round < b[cls].length) { list.push(b[cls][round]); added++; }
      }
      if (added === 0) break;
    }
    out.set(carrier, list);
  }
  return out;
}

/**
 * 按「每轮每个载体各取 1 条」平分预算。
 *
 * 这一步的存在理由是历史踩坑：最初按族轮转，每个族取第 k 个候选，
 * 结果任何被追加到族尾部的载体（如 feast 在第 7 位）永远轮不到。
 * 改成载体优先级后，每个载体每轮各取 1 条，先保证人人有份，
 * 候选少的载体提前耗尽，剩余名额自动回流给其它载体。
 */
function pickFair(byCarrier, target, label) {
  const order = [...byCarrier.keys()];
  const out = [];
  for (let round = 0; out.length < target; round++) {
    let added = 0;
    for (const c of order) {
      if (out.length >= target) break;
      const list = byCarrier.get(c);
      if (round < list.length) { out.push(list[round]); added++; }
    }
    if (added === 0) break; // 所有载体的候选都取完了
  }
  assert(out.length === target,
    `${label}候选不足：只铺了 ${out.length} 条，目标 ${target}（请增加族或载体）`);
  return out;
}

function attrClass(family, elements) {
  if (elements) return 'ELEM';
  if (SIG_ATTR.has(family)) return 'SIG';
  if (SUP_ATTR.has(family)) return 'SUP';
  return 'BASE';
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
    return { family, candidates, cls: attrClass(family, elements) };
  });
  return pickFair(stratify(families), TARGET_ATTRIBUTE, '属性');
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
    return {
      family,
      candidates,
      // 效果层没有元素维度（元素限定写在 element_allow 上，不是整族展开），
      // 所以只需分 BASE / SIG 两类
      cls: BASE_EFFECTS.has(family) ? 'BASE' : 'SIG',
    };
  });
  return pickFair(stratify(families), TARGET_EFFECT, '效果');
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
  //
  // 载体品质的语义按类型分开叫名字：装备是「做工」（基础属性倍率），
  // 消耗品是「品级」（效果强度倍率），召唤物是「血统」（召唤强度倍率）。
  // 三者在数值上完全一致，只是文案对齐各自的造物逻辑 ——
  // 玩家看到「血统 +5%」不会疑惑这与召出来的东西有什么关系。
  const QUALITY_LABEL = {
    EQUIPMENT: '做工',
    CONSUMABLE: '品级',
    SUMMON: '血统',
  };
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
    tiers: buildTiers(5, 0, '%', QUALITY_LABEL[type], type, id),
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

    // --- 新增载体的「招牌联动」---
    // 每个新载体至少一条：玩家第一次用这个载体时，就该撞上一个读得懂的化学效应。
    // 否则新载体只是「换了个模型」，构筑深度没有增加。
    ['carrier.dagger', 'attr.backstab', 'SYNERGY', 0.15, '背刺伤害 +15%'],
    ['carrier.dagger', 'effect.bleed', 'SYNERGY', 0.20, '流血伤害 +20%'],
    ['carrier.hammer', 'effect.shatter', 'SYNERGY', 0.20, '碎甲效果 +20%'],
    ['carrier.hammer', 'effect.stun', 'CONFLICT', -0.10, '眩晕概率 −10%（重锤自带硬直，重复投入收益递减）'],
    ['carrier.spear', 'effect.pierceLine', 'SYNERGY', 0.25, '贯穿距离 +25%'],
    ['carrier.spear', 'effect.impale', 'SYNERGY', 0.20, '钉刺定身 +20%'],
    ['carrier.shield', 'effect.bulwark', 'SYNERGY', 0.25, '壁垒护盾 +25%'],
    ['carrier.shield', 'effect.riposte', 'SYNERGY', 0.20, '还击伤害 +20%'],
    ['carrier.crossbow', 'effect.volley', 'SYNERGY', 1, '齐射箭数 +1'],
    ['carrier.crossbow', 'effect.steady', 'SYNERGY', 0.20, '定装增伤 +20%'],
    ['carrier.gauntlet', 'effect.flurry', 'SYNERGY', 1, '连打段数 +1'],
    ['carrier.gauntlet', 'effect.ironFist', 'SYNERGY', 0.25, '铁拳伤害 +25%'],

    ['carrier.undead', 'effect.bonePile', 'SYNERGY', 0.20, '骨堆再生率 +20%'],
    ['carrier.undead', 'effect.soulHarvest', 'SYNERGY', 0.20, '收魂回复 +20%'],
    ['carrier.bird', 'effect.dive', 'SYNERGY', 0.25, '俯冲伤害 +25%'],
    ['carrier.bird', 'effect.scout', 'SYNERGY', 4, '侦察半径 +4'],
    ['carrier.treant', 'effect.rootSnare', 'SYNERGY', 1, '缠绕时长 +1s'],
    ['carrier.treant', 'effect.photosynth', 'SYNERGY', 0.20, '光合回复 +20%'],
    ['carrier.totem', 'effect.wardAura', 'SYNERGY', 0.20, '守护光环强度 +20%'],
    ['carrier.totem', 'effect.pulse', 'SYNERGY', 0.20, '脉冲频率 +20%'],
    ['carrier.wisp', 'effect.mend', 'SYNERGY', 0.20, '愈合量 +20%'],
    ['carrier.wisp', 'effect.holyLight', 'SYNERGY', 0.20, '圣光强度 +20%'],
    ['carrier.serpent', 'effect.venom', 'SYNERGY', 2, '中毒层数 +2'],
    ['carrier.serpent', 'effect.constrict', 'SYNERGY', 0.20, '绞缠强度 +20%'],

    ['carrier.flask', 'effect.splash', 'SYNERGY', 0.25, '泼溅半径 +25%'],
    ['carrier.flask', 'effect.volatile', 'SYNERGY', 0.20, '挥发持续 +20%'],
    ['carrier.rune', 'effect.inscribe', 'SYNERGY', 0.25, '铭刻强度 +25%'],
    ['carrier.rune', 'effect.trap', 'SYNERGY', 0.20, '陷阱触发范围 +20%'],
    ['carrier.mushroom', 'effect.spore', 'SYNERGY', 0.20, '孢子扩散 +20%'],
    ['carrier.censer', 'effect.incense', 'SYNERGY', 0.20, '熏香回复 +20%'],
    ['carrier.seed', 'effect.sprout', 'SYNERGY', 0.25, '萌芽成活率 +25%'],
    ['carrier.seed', 'effect.harvest', 'SYNERGY', 0.15, '采收产出 +15%'],

    // --- 新增属性 × 新增效果的跨层联动 ---
    ['attr.auraPower', 'effect.pulse', 'SYNERGY', 0.15, '脉冲强度 +15%'],
    ['attr.healPower', 'effect.mend', 'SYNERGY', 0.15, '愈合量 +15%'],
    ['attr.poisonPotency', 'effect.venom', 'SYNERGY', 0.15, '毒性强度 +15%'],
    ['attr.blockPower', 'effect.bulwark', 'SYNERGY', 0.20, '壁垒厚度 +20%'],
    ['attr.reach', 'effect.pierceLine', 'SYNERGY', 0.20, '贯穿距离 +20%'],
    ['attr.comboStage', 'effect.flurry', 'SYNERGY', 1, '连打段数 +1'],
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

  // 词条预算：属性 700 + 效果 380 + 载体 35 + 元素 9 = 1124
  // 这个数是「铺设预算」而不是「上限」：候选总数比它多，pickFair 按载体平分，
  // 保证每个载体的属性池 ≥ 19 条、效果池 ≥ 10 条，远高于「抽 5 张不重复」的下限。
  const expectTotal = TARGET_ATTRIBUTE + TARGET_EFFECT + carriers.length + elements.length;
  assert(total === expectTotal, `词条总数应为 ${expectTotal}，实际 ${total}`);
  // 协同数：手工 100 条 + 派生矩阵去重后的结果。硬凑整数只能靠加冗余组合，
  // 所以这里锁的是「实测成立的对数」，而不是一个好看的圆整数。
  assert(links.length === 193, `协同应为 193 条（去重后），实际 ${links.length}`);
  console.log('✓ 目标数量达标');
}

main();
