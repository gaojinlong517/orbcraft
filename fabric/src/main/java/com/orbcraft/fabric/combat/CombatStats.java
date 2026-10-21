package com.orbcraft.fabric.combat;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 召唤物的战斗数值（暴击率 / 暴击伤害 / 吸血）。
 *
 * <p><b>为什么存在。</b> 这三个键在 {@code SummonAttrMap} 里登记了很久，但没有任何结算路径：
 * 玩家堆了 30% 暴击，伤害数字一次不变，也不报错 —— 属于「看起来做完了」里最难发现的一类。
 *
 * <p><b>为什么是内存表而不是写进实体 NBT。</b> 召唤物是代理实体（原版僵尸/卫道士），
 * 它的 NBT 字段名和原版行为强耦合；而且这些值只在「活着」期间有意义，
 * 重启后召唤物本来就要重新召唤。写 NBT 会让「读不出来的字段」变成新的静默故障源。
 *
 * <p><b>单位（与 {@code Spec.attrs} 一致）</b>：
 * {@code critChance} 20 = 20%；{@code critDamage} 50 = 暴击时额外 +50%（即 ×1.5）；
 * {@code lifesteal} 20 = 造成伤害的 20% 转为治疗。
 */
public final class CombatStats {

    private static final ConcurrentHashMap<UUID, CombatStats> BY_ENTITY = new ConcurrentHashMap<>();

    private final double critChance;
    private final double critDamage;
    private final double lifesteal;

    private CombatStats(double critChance, double critDamage, double lifesteal) {
        this.critChance = critChance;
        this.critDamage = critDamage;
        this.lifesteal = lifesteal;
    }

    /** 从 spec 的属性表构造；三个键都没有时返回 {@code null}（调用方不需要登记）。 */
    public static CombatStats of(java.util.Map<String, Double> attrs) {
        if (attrs == null) {
            return null;
        }
        double chance = attrs.getOrDefault("critChance", 0.0);
        double damage = attrs.getOrDefault("critDamage", 0.0);
        double lifesteal = attrs.getOrDefault("lifesteal", 0.0);
        if (chance == 0.0 && damage == 0.0 && lifesteal == 0.0) {
            return null;
        }
        return new CombatStats(chance, damage, lifesteal);
    }

    public static void remember(UUID entityId, CombatStats stats) {
        if (entityId == null || stats == null) {
            return;
        }
        BY_ENTITY.put(entityId, stats);
    }

    public static void forget(UUID entityId) {
        if (entityId != null) {
            BY_ENTITY.remove(entityId);
        }
    }

    public static CombatStats of(UUID entityId) {
        return BY_ENTITY.get(entityId);
    }

    public double critChance() {
        return critChance;
    }

    public double critDamage() {
        return critDamage;
    }

    public double lifesteal() {
        return lifesteal;
    }
}
