package com.orbcraft.fabric.mapping;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 一个 Spec 折算出来的、可以直接写到实体上的数值。
 *
 * <p>在链路里的位置：{@link SummonAttrMap#resolve} 产出本对象，
 * {@code VanillaSummonSpawner} 在实体还没进世界时调 {@link #applyTo}。
 * 把「算」和「写」分开，是因为算的部分可以离线跑，写的部分只能靠实体。
 *
 * <h2>单位模型（改映射表之前先读这段）</h2>
 * 卡表里有两套单位，靠 desc 区分，混用会让数值差好几倍：
 * <pre>
 * 最终值 = (代理实体基础值 + 绝对值加成) × (1 + 百分比加成 / 100)
 * </pre>
 * <ul>
 *   <li><b>加法组</b>（desc 不带 %）：{@code damage}「伤害 +2」、{@code maxHealth}「生命上限 +2」、
 *       {@code armor}「护甲 +1」、{@code summonArmor}「召唤护甲 +2」、{@code knockback}、
 *       {@code reach}。它们加到代理实体自带的底子上。</li>
 *   <li><b>乘法组</b>（desc 带 %）：{@code summonHealth}「召唤生命 +10%」、
 *       {@code summonDamage}「召唤攻击 +8%」、{@code moveSpeed} / {@code summonSpeed} /
 *       {@code attackSpeed} / {@code elemDamage} / {@code elemSummonDamage}。
 *       它们是<b>强化比例</b>，必须有一个被强化的对象 —— 那个对象只能是代理实体本身。</li>
 *   <li><b>顺序是先加后乘</b>：先给底子加固定值，再按比例放大。
 *       反过来（先乘后加）会让「+2 血量」在小体量代理上被稀释，
 *       而卡表里小体量代理（蝙蝠、鹦鹉）恰恰是最需要那 +2 的地方。</li>
 * </ul>
 *
 * <p>为什么以代理实体的基础值为基（而不是从 0 起算）：{@code summonHealth} 是百分比。
 * 如果不存在「基础血量」这个概念，一条 +10% 就是在凭空造数值 ——
 * 僵尸 20 血 × 1.4 = 28 是数据本意；「从 0 起算再 +10%」只能得到 0。
 *
 * <p><b>同一张表里两类键混用时的示例</b>：僵尸（基础 20 血 / 3 攻）+ {@code maxHealth 60}
 * + {@code summonHealth 40} → (20+60)×1.4 = 112 血；{@code damage 12} + {@code summonDamage 8}
 * → (3+12)×1.08 = 16.2 攻。
 *
 * <p>不可变。
 */
public record SummonStats(
        double healthFlat,
        double healthPct,
        double damageFlat,
        double damagePct,
        double armor,
        double moveSpeedPct,
        double attackSpeedPct,
        double attackKnockback,
        double knockbackResist,
        double followRange,
        double elementDamagePct) {

    /** 单次落地后的一行体检报告，用于日志与「为什么这只血这么多」的追查。 */
    public record ApplyReport(boolean healthSet,
                              boolean damageSet,
                              int health,
                              int damage,
                              List<String> skipped) {
    }

    /**
     * 写到实体上。**不抛异常**：代理实体可能根本没有某个属性
     * （例如鹦鹉没有 {@code ATTACK_DAMAGE}），那一条就记进 skipped 跳过。
     */
    public ApplyReport applyTo(LivingEntity entity) {
        List<String> skipped = new ArrayList<>();

        boolean healthSet = false;
        int healthValue = 0;
        if (!Double.isNaN(healthFlat) || healthPct != 0.0) {
            AttributeInstance hp = entity.getAttribute(Attributes.MAX_HEALTH);
            if (hp == null) {
                skipped.add("maxHealth");
            } else {
                double flat = Double.isNaN(healthFlat) ? 0.0 : healthFlat;
                double v = (hp.getBaseValue() + flat) * (1.0 + healthPct);
                hp.setBaseValue(clamp(v, 1.0, 2048.0));
                healthValue = Math.round(entity.getMaxHealth());
                // 必须补满：实体是在 setBaseValue 之前构造的，血量还是旧上限
                entity.setHealth(entity.getMaxHealth());
                healthSet = true;
            }
        }

        boolean damageSet = false;
        int damageValue = 0;
        if (!Double.isNaN(damageFlat) || damagePct != 0.0 || elementDamagePct != 0.0) {
            AttributeInstance dmg = entity.getAttribute(Attributes.ATTACK_DAMAGE);
            if (dmg == null) {
                skipped.add("damage");
            } else {
                double flat = Double.isNaN(damageFlat) ? 0.0 : damageFlat;
                // 元素增伤与通用增伤各乘一次：它们是两个独立的百分比来源
                double v = (dmg.getBaseValue() + flat) * (1.0 + damagePct) * (1.0 + elementDamagePct);
                dmg.setBaseValue(clamp(v, 0.0, 2048.0));
                damageValue = Math.round((float) dmg.getValue());
                damageSet = true;
            }
        }

        if (armor != 0.0) {
            addBase(entity, Attributes.ARMOR, armor, 0.0, 128.0, "armor", skipped);
        }
        if (moveSpeedPct != 0.0) {
            scaleBase(entity, Attributes.MOVEMENT_SPEED, moveSpeedPct, 0.001, 2.0, "moveSpeed", skipped);
        }
        if (attackSpeedPct != 0.0) {
            scaleBase(entity, Attributes.ATTACK_SPEED, attackSpeedPct, 0.1, 16.0, "attackSpeed", skipped);
        }
        if (attackKnockback != 0.0) {
            addBase(entity, Attributes.ATTACK_KNOCKBACK, attackKnockback, 0.0, 20.0, "knockback", skipped);
        }
        if (knockbackResist != 0.0) {
            addBase(entity, Attributes.KNOCKBACK_RESISTANCE, knockbackResist, 0.0, 1.0, "staggerResist", skipped);
        }
        if (followRange != 0.0) {
            addBase(entity, Attributes.FOLLOW_RANGE, followRange, 0.0, 128.0, "reach", skipped);
        }

        return new ApplyReport(healthSet, damageSet, healthValue, damageValue, List.copyOf(skipped));
    }

    private static void addBase(LivingEntity entity, Holder<Attribute> attribute, double delta,
                                double min, double max, String name, List<String> skipped) {
        AttributeInstance inst = entity.getAttribute(attribute);
        if (inst == null) {
            skipped.add(name);
            return;
        }
        inst.setBaseValue(clamp(inst.getBaseValue() + delta, min, max));
    }

    private static void scaleBase(LivingEntity entity, Holder<Attribute> attribute, double pct,
                                  double min, double max, String name, List<String> skipped) {
        AttributeInstance inst = entity.getAttribute(attribute);
        if (inst == null) {
            skipped.add(name);
            return;
        }
        inst.setBaseValue(clamp(inst.getBaseValue() * (1.0 + pct), min, max));
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }
}
