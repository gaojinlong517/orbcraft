package com.orbcraft.fabric.mapping;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 属性映射表 —— {@code Spec.attrs()} 的键 → 召唤物实体属性。
 *
 * <p><b>在链路里的位置</b>：{@code SpecSettler} 把卡表 id 折算成
 * {@code attrs[key] = 已结算数值}，本类负责把那些键翻译成 MC 的属性
 * （{@code MAX_HEALTH} / {@code ATTACK_DAMAGE} / …），结果交给
 * {@link SummonStats#applyTo} 落到实体上。S3 之外没有人读这张表。
 *
 * <p><b>为什么必须显式列表。</b> 数据里现在有 93 个属性键 + 64 个效果族，
 * 而 S3 只接得动其中十几个。如果对没接的键写
 * {@code attrs.getOrDefault(k, 0.0)}，玩家的「我堆了暴击但没伤害」就变成一个
 * 没有人能查的问题：键既没报错，也没生效。所以这里把键分成三档：
 * <ul>
 *   <li>{@link #WIRED} —— 真的落到实体属性上的键，一条一条写清「加到哪、乘几」。</li>
 *   <li>{@link #KNOWN_UNWIRED} / {@link #EFFECT_FAMILIES} —— <b>数据里确实存在</b>、
 *       但 S3 明确还没接的键。它们只记日志不算 bug，但可枚举、可查、可排期。</li>
 *   <li>其余 —— {@code default} 分支：数据换了名字或者新增了键。这一档必须打
 *       WARN，因为它意味着「卡表与落地器已经不同步」。</li>
 * </ul>
 *
 * <p><b>单位模型（最容易搞错的地方，改表前必读）</b>：卡表里混着两套单位，
 * 靠 desc 里有没有 {@code %} 区分，混用会让数值差好几倍：
 * <pre>
 * 最终值 = (代理实体基础值 + 绝对值加成) × (1 + 百分比加成 / 100)
 * </pre>
 * <ul>
 *   <li><b>加法组</b>（desc 不带 %）：{@code damage}「伤害 +2」、{@code maxHealth}「生命上限 +2」、
 *       {@code armor}「护甲 +1」、{@code summonArmor}「召唤护甲 +2」、{@code knockback}、{@code reach}。</li>
 *   <li><b>乘法组</b>（desc 带 %）：{@code summonHealth}「召唤生命 +10%」、
 *       {@code summonDamage}「召唤攻击 +8%」、{@code moveSpeed} / {@code summonSpeed} /
 *       {@code attackSpeed} / {@code elemDamage} / {@code elemSummonDamage}。</li>
 *   <li><b>先加后乘</b>：先给底子加固定值，再按比例放大。反过来会把「+2 血量」
 *       在小体量代理（蝙蝠、鹦鹉）上稀释掉，而那正是它最该起作用的地方。</li>
 * </ul>
 * 之所以以代理实体的基础值为基，是因为乘法组的键是<b>强化比例</b>：
 * 没有「被强化的对象」时，一条 +10% 就是在凭空造数值（僵尸 20×1.4=28 才是本意）。
 * 本模型是 team-lead 在 2026-10-21 纠正派单书之后定下的；派单书原句
 * 「血量 = maxHealth + summonHealth」把两套单位混成了一个加法，已作废。
 *
 * <p><b>已知数据限制（S3 不修，记录在此）</b>：{@code elemDamage} /
 * {@code elemResist} / {@code elemSummonDamage} 这类元素键由
 * {@code SpecSettler} 取 id 的第二段得到，而元素维度写在第三段
 * （{@code attr.elemDamage.lightning.bow}）。多元素时它们会被合并成一个数，
 * 键里<b>不带元素维度</b>，元素信息只剩 {@code Spec.tags()}。
 * 所以本类只能在「这个 Spec 有元素标签」时把 {@code elemDamage} 当成
 * 该元素的增伤整体加上去，做不到「火 +10%、雷 +20%」分开算。
 * 真正的修法在 {@code SpecSettler} 的键规则（需要主线的数据层决策），
 * 不要在 S3 里给 attrs 补一个假的元素维度。
 *
 * <p>线程安全：全静态不可变表，可并发读。
 */
public final class SummonAttrMap {

    /** 效果层键的前缀，与 {@code SpecSettler.EFFECT_PREFIX} 一致。 */
    public static final String EFFECT_PREFIX = "effect.";

    /** 「没有这个值」的哨兵。用 NaN 而不是 0：0 血量/0 伤害是合法输入。 */
    public static final double NOT_SET = Double.NaN;

    /**
     * 单次召唤的只数上限。卡面「召唤数量 +N」是加法词条，多张卡叠上去理论上无界；
     * 不设上限的话一张满级 swarm 卡能把一屏塞满实体，客户端先卡死的是打开界面的人。
     */
    public static final int MAX_SUMMON_COUNT = 8;

    /** {@code Resolution#lifespanTicks()} 的「不限时」哨兵。 */
    public static final long NO_LIFESPAN = -1L;

    // 生成参数（不落在实体属性上，由 VanillaSummonSpawner 在 spawn 时消费）：
    //   summonCount     —— 单位「只」，desc「召唤数量 +1」，加法词条；
    //                      基础 1 只，最终 1 + N（夹在 [1, MAX_SUMMON_COUNT]）
    //   summonLifespan  —— 单位「秒」，desc「存续时间 +15s」，加法词条。
    //                      【语义裁决，2026-10-22】数据层没有「基础寿命」字段（Spec 里查无 lifespan），
    //                      而召唤物默认是永久的，「+15s」字面上对永久物无意义。
    //                      只有不改数据层就能实现的解释是：堆了该词条的召唤物变成限时召唤，
    //                      时长 = 各档位之和；没堆的照旧永久。这个解释改变了词条的性价比
    //                      （限时不一定是增益），要不要改成「全召唤物统一基础寿命 + N 秒」
    //                      需要数据层（派单书）决策，S3 无权定。

    /** 一条属性键最终落到哪个实体属性上，以及乘多少。 */
    public enum Target {
        /** 绝对值加成：加到代理实体自带的基础上。 */
        HEALTH_FLAT,
        /** 强化比例：乘在「基础值 + 绝对值加成」之上。 */
        HEALTH_PCT,
        DAMAGE_FLAT,
        DAMAGE_PCT,
        /** 按当前伤害的百分比加，只在该 Spec 有元素标签时才生效（见类注释的元素限制）。 */
        ELEMENT_DAMAGE_PCT,
        ARMOR_FLAT,
        MOVE_SPEED_PCT,
        ATTACK_SPEED_PCT,
        ATTACK_KNOCKBACK_FLAT,
        KNOCKBACK_RESIST_FLAT,
        FOLLOW_RANGE_FLAT
    }

    /**
     * @param target 落到哪个属性
     * @param factor 数值换算系数（百分比键 0.01，其余 1.0）
     * @param note   为什么这么换算，给后来改表的人看
     */
    public record Rule(Target target, double factor, String note) {

        static Rule flat(Target target) {
            return new Rule(target, 1.0, "");
        }

        static Rule flat(Target target, String note) {
            return new Rule(target, 1.0, note);
        }

        static Rule pct(Target target, String note) {
            return new Rule(target, 0.01, note);
        }
    }

    /**
     * 真正接上的键。改这里必须同步改 {@link #KNOWN_UNWIRED}（同一个键不能两边都有）。
     */
    private static final Map<String, Rule> WIRED = Map.ofEntries(
            Map.entry("damage", Rule.flat(Target.DAMAGE_FLAT)),
            Map.entry("summonDamage", Rule.pct(Target.DAMAGE_PCT, "desc 为「召唤攻击 +8%」")),
            Map.entry("maxHealth", Rule.flat(Target.HEALTH_FLAT)),
            Map.entry("summonHealth", Rule.pct(Target.HEALTH_PCT, "desc 为「召唤生命 +10%」")),
            Map.entry("armor", Rule.flat(Target.ARMOR_FLAT)),
            Map.entry("summonArmor", Rule.flat(Target.ARMOR_FLAT)),
            Map.entry("moveSpeed", Rule.pct(Target.MOVE_SPEED_PCT, "desc 为「移速 +3%」")),
            Map.entry("summonSpeed", Rule.pct(Target.MOVE_SPEED_PCT, "desc 为「召唤移速 +4%」")),
            Map.entry("attackSpeed", Rule.pct(Target.ATTACK_SPEED_PCT, "desc 为「攻速 +5%」")),
            Map.entry("knockback", Rule.flat(Target.ATTACK_KNOCKBACK_FLAT)),
            Map.entry("reach", Rule.flat(Target.FOLLOW_RANGE_FLAT,
                    "1.21 没有「触及距离」属性，用跟随范围近似：够得着才谈得上够到")),
            Map.entry("staggerResist", Rule.pct(Target.KNOCKBACK_RESIST_FLAT,
                    "百分比 → 击退抗性 0~1 的绝对值")),
            Map.entry("elemDamage", Rule.pct(Target.ELEMENT_DAMAGE_PCT,
                    "元素维度已丢失，只能整体加到伤害上")),
            Map.entry("elemSummonDamage", Rule.pct(Target.ELEMENT_DAMAGE_PCT,
                    "同上；两个键会叠乘，数据侧目前也确实会同时命中")));

    /**
     * 数据里存在、S3 明确没接的属性键。列出来的目的是「可查」：
     * 玩家问「我堆了暴击」，答案必须能从这张表里读出来 —— 要么已接，要么在这里。
     */
    private static final Set<String> KNOWN_UNWIRED = Set.of(
            "accuracy", "agility", "ammoCapacity", "aoeRadius", "armTime", "armorBreak",
            "auraPower", "auraRadius", "backstab", "blastDamage", "blastRadius", "bleedDamage",
            "blockChance", "blockPower", "censerRate", "chaosBias", "charges", "cleave",
            "comboStage", "cooldownReduction", "critChance", "critDamage", "deploySpeed", "diveDamage",
            "drawSpeed", "durability", "duration", "elemAffinity", "elemAura", "elemResist",
            "elemSummonResist", "elementPower", "fireRate", "firstShotDamage", "focus", "growTime",
            "growthRate", "haste", "healPower", "healing", "hunger", "impactPower",
            "lifesteal", "lightRadius", "lingering", "lootBonus", "parryChance", "pierce",
            "poisonPotency", "potency", "range", "regen", "reloadSpeed", "resist",
            "rootStrength", "runePower", "shieldAmount", "splashRadius", "stackLimit", "statusChance",
            "statusDuration", "summonAggro", "summonAoe", "summonCrit", "summonElement",
            "summonFlySpeed", "summonRegen", "summonRevive", "summonSacrifice", "summonStability",
            "summonStatus", "thorns", "thrustDamage", "triggerRadius", "useSpeed", "xpBonus",
            "yieldBonus"
    );

    /** 效果族的全集，与 {@code affixes.effect.json} 的 {@code family} 字段一致。 */
    private static final Set<String> EFFECT_FAMILIES = Set.of(
            "effect.armorPierce", "effect.auraBuff", "effect.bleed", "effect.blind",
            "effect.blink", "effect.bonePile", "effect.bulwark", "effect.burstRoot",
            "effect.chain", "effect.combo", "effect.constrict", "effect.counter",
            "effect.deathBoom", "effect.dive", "effect.execute", "effect.explosion",
            "effect.fireResist", "effect.flurry", "effect.freeze", "effect.harvest",
            "effect.holyLight", "effect.ignite", "effect.impale", "effect.incense",
            "effect.inscribe", "effect.invisibility", "effect.ironFist", "effect.jumpBoost",
            "effect.lastStand", "effect.lifestealAura", "effect.magnet", "effect.mend",
            "effect.mutate", "effect.nightVision", "effect.photosynth", "effect.pierceLine",
            "effect.poisonStack", "effect.pulse", "effect.reflect", "effect.riposte",
            "effect.rootSnare", "effect.scout", "effect.shadowStep", "effect.shatter",
            "effect.shield", "effect.shock", "effect.shockwave", "effect.slowField",
            "effect.soulHarvest", "effect.splash", "effect.spore", "effect.sprint",
            "effect.sprout", "effect.steady", "effect.stun", "effect.summonEnrage",
            "effect.summonSplit", "effect.thornsBurst", "effect.trap", "effect.venom",
            "effect.volatile", "effect.volley", "effect.wardAura", "effect.waterBreathing"
    );

    private SummonAttrMap() {
    }

    /**
     * 一次分类的结果。
     *
     * @param stats   可以直接落到实体上的数值
     * @param applied 真的生效了的键
     * @param unwired 已知但本轮不接的键（玩家能感受到「没生效」，但不该当成 bug 查）
     * @param unknown 表里没有的键 —— 数据漂移，调用方必须打 WARN
     * @param summonCount 该次召唤要生成几只（基础 1 只 + 卡面「召唤数量 +N」，下限 1）
     * @param lifespanTicks 限时召唤的存活时长（tick）；&le;0 表示不限时
     */
    public record Resolution(SummonStats stats,
                             List<String> applied,
                             List<String> unwired,
                             List<String> unknown,
                             int summonCount,
                             long lifespanTicks) {
    }

    /**
     * @param attrs         {@code Spec.attrs()}
     * @param hasElementTag 该 Spec 的 tags 里是否有元素 id；没有时元素增伤无从归属，记为未接
     */
    public static Resolution resolve(Map<String, Double> attrs, boolean hasElementTag) {
        Map<Target, Double> acc = new EnumMap<>(Target.class);
        List<String> applied = new ArrayList<>();
        List<String> unwired = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        int countExtra = 0;
        double lifespanSeconds = 0.0;

        if (attrs != null) {
            for (Map.Entry<String, Double> e : attrs.entrySet()) {
                String key = e.getKey();
                Double raw = e.getValue();
                double value = raw == null ? 0.0 : raw;

                if (key.startsWith(EFFECT_PREFIX)) {
                    // 效果是触发器不是数值，落地要靠效果解析器（core 里还没有消费方）
                    if (EFFECT_FAMILIES.contains(key)) {
                        unwired.add(key);
                    } else {
                        unknown.add(key);
                    }
                    continue;
                }

                // —— 生成参数不落在实体属性上，单独汇出（单位与语义见 SUMMON_KEYS 注释）
                if (key.equals("summonCount")) {
                    countExtra += (int) Math.round(value);
                    applied.add(key);
                    continue;
                }
                if (key.equals("summonLifespan")) {
                    lifespanSeconds += value;
                    applied.add(key);
                    continue;
                }

                Rule rule = WIRED.get(key);
                if (rule == null) {
                    if (KNOWN_UNWIRED.contains(key)) {
                        unwired.add(key);
                    } else {
                        unknown.add(key);
                    }
                    continue;
                }
                if (rule.target() == Target.ELEMENT_DAMAGE_PCT && !hasElementTag) {
                    // 没有元素标签就没有可归属的元素，硬加会变成「凭空增伤」
                    unwired.add(key);
                    continue;
                }
                acc.merge(rule.target(), value * rule.factor(), Double::sum);
                applied.add(key);
            }
        }

        SummonStats stats = new SummonStats(
                acc.getOrDefault(Target.HEALTH_FLAT, NOT_SET),
                acc.getOrDefault(Target.HEALTH_PCT, 0.0),
                acc.getOrDefault(Target.DAMAGE_FLAT, NOT_SET),
                acc.getOrDefault(Target.DAMAGE_PCT, 0.0),
                acc.getOrDefault(Target.ARMOR_FLAT, 0.0),
                acc.getOrDefault(Target.MOVE_SPEED_PCT, 0.0),
                acc.getOrDefault(Target.ATTACK_SPEED_PCT, 0.0),
                acc.getOrDefault(Target.ATTACK_KNOCKBACK_FLAT, 0.0),
                acc.getOrDefault(Target.KNOCKBACK_RESIST_FLAT, 0.0),
                acc.getOrDefault(Target.FOLLOW_RANGE_FLAT, 0.0),
                acc.getOrDefault(Target.ELEMENT_DAMAGE_PCT, 0.0));
        int count = Math.max(1, Math.min(MAX_SUMMON_COUNT, 1 + countExtra));
        // 卡面单位是秒（desc「存续时间 +15s」），落地换算成 tick；只在实际堆了该词条时才限时
        long lifespanTicks = lifespanSeconds > 0.0
                ? Math.round(lifespanSeconds * 20.0)
                : NO_LIFESPAN;
        return new Resolution(stats, List.copyOf(applied), List.copyOf(unwired), List.copyOf(unknown),
                count, lifespanTicks);
    }

    /** 表里接了多少个键，给诊断与自检用（汇报里要报这个数）。 */
    public static int wiredKeyCount() {
        return WIRED.size();
    }

    /** 已知但未接的属性键数量。 */
    public static int knownUnwiredKeyCount() {
        return KNOWN_UNWIRED.size();
    }

    public static int effectFamilyCount() {
        return EFFECT_FAMILIES.size();
    }
}
