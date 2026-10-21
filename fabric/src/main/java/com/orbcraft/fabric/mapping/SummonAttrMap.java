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
 * <p><b>另一处数据/文档冲突</b>：卡表 desc 把 {@code summonHealth} 写成
 * 「召唤生命 +10%」（百分比），但派单书的验收式子是
 * {@code 血量 = maxHealth + summonHealth}（绝对相加）。两者不能同时成立，
 * 本轮按<b>派单书的绝对相加</b>实现 —— 已结算的 attrs 本来就是绝对量
 * （{@code damage} 的 desc 是「伤害 +2」），混进一个百分比会让同一个 map
 * 里出现两种单位。这条要由数据侧统一，不要在这里悄悄乘一个 0.01。
 *
 * <p>线程安全：全静态不可变表，可并发读。
 */
public final class SummonAttrMap {

    /** 效果层键的前缀，与 {@code SpecSettler.EFFECT_PREFIX} 一致。 */
    public static final String EFFECT_PREFIX = "effect.";

    /** 「没有这个值」的哨兵。用 NaN 而不是 0：0 血量/0 伤害是合法输入。 */
    public static final double NOT_SET = Double.NaN;

    /** 一条属性键最终落到哪个实体属性上，以及乘多少。 */
    public enum Target {
        HEALTH_FLAT,
        DAMAGE_FLAT,
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
            Map.entry("summonDamage", Rule.flat(Target.DAMAGE_FLAT)),
            Map.entry("maxHealth", Rule.flat(Target.HEALTH_FLAT)),
            Map.entry("summonHealth", Rule.flat(Target.HEALTH_FLAT,
                    "派单书验收要求绝对相加，不按卡表 desc 的百分比解释")),
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
            "statusDuration", "summonAggro", "summonAoe", "summonCount", "summonCrit", "summonElement",
            "summonFlySpeed", "summonLifespan", "summonRegen", "summonRevive", "summonSacrifice", "summonStability",
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
     */
    public record Resolution(SummonStats stats,
                             List<String> applied,
                             List<String> unwired,
                             List<String> unknown) {
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
                acc.getOrDefault(Target.DAMAGE_FLAT, NOT_SET),
                acc.getOrDefault(Target.ARMOR_FLAT, 0.0),
                acc.getOrDefault(Target.MOVE_SPEED_PCT, 0.0),
                acc.getOrDefault(Target.ATTACK_SPEED_PCT, 0.0),
                acc.getOrDefault(Target.ATTACK_KNOCKBACK_FLAT, 0.0),
                acc.getOrDefault(Target.KNOCKBACK_RESIST_FLAT, 0.0),
                acc.getOrDefault(Target.FOLLOW_RANGE_FLAT, 0.0),
                acc.getOrDefault(Target.ELEMENT_DAMAGE_PCT, 0.0));
        return new Resolution(stats, List.copyOf(applied), List.copyOf(unwired), List.copyOf(unknown));
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
