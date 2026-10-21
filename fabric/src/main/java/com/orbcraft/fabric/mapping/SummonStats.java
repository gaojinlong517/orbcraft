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
 * 把「算」和「写」分开，是因为算的部分可以单测，写的部分只能靠实体。
 *
 * <p><b>两条取舍写在字段上，不要改回去</b>：
 * <ul>
 *   <li>血量与攻击力是<b>绝对量覆盖</b>（不是加在代理实体自己的默认值上）。
 *       派单书的验收式子是 {@code 血量 = maxHealth + summonHealth}，
 *       若再叠加代理实体自带的 20 点血，同一个 Spec 换个代理就是另一个数值，
 *       玩家的「我的构筑数值」就失去了意义。</li>
 *   <li>移速/攻速是<b>百分比</b>（卡表 desc 写的就是百分比），而且乘在代理实体
 *       默认值上 —— 因为 1.21 的速度属性换算成人格化单位没有可读的绝对刻度。</li>
 * </ul>
 *
 * <p>不可变。
 */
public record SummonStats(
        double health,
        double damage,
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
        if (!Double.isNaN(health)) {
            AttributeInstance hp = entity.getAttribute(Attributes.MAX_HEALTH);
            if (hp == null) {
                skipped.add("maxHealth");
            } else {
                double v = clamp(health, 1.0, 2048.0);
                hp.setBaseValue(v);
                healthValue = Math.round(entity.getMaxHealth());
                // 必须补满：实体是在 setBaseValue 之前构造的，血量还是旧上限
                entity.setHealth(entity.getMaxHealth());
                healthSet = true;
            }
        }

        boolean damageSet = false;
        int damageValue = 0;
        if (!Double.isNaN(damage)) {
            AttributeInstance dmg = entity.getAttribute(Attributes.ATTACK_DAMAGE);
            if (dmg == null) {
                skipped.add("damage");
            } else {
                double v = damage * (1.0 + elementDamagePct);
                dmg.setBaseValue(clamp(v, 0.0, 2048.0));
                damageValue = (int) Math.round(dmg.getValue());
                damageSet = true;
            }
        } else if (elementDamagePct != 0.0) {
            // 只有元素增伤、没有基础伤害：加在代理实体自带伤害上
            AttributeInstance dmg = entity.getAttribute(Attributes.ATTACK_DAMAGE);
            if (dmg == null) {
                skipped.add("elemDamage");
            } else {
                dmg.setBaseValue(dmg.getBaseValue() * (1.0 + elementDamagePct));
                damageValue = (int) Math.round(dmg.getValue());
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
