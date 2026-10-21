package com.orbcraft.fabric.consume;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.orbcraft.core.spec.ProductType;
import com.orbcraft.core.spec.Spec;
import com.orbcraft.fabric.OrbcraftMod;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 消耗品落地：在落点结算一次范围效果。
 *
 * <p><b>为什么现在才做。</b> 之前消耗品球在投掷前就被拦回手里 —— 宁可扔不出去，
 * 也不让球飞一段然后凭空消失。这条链路补上之后，三种产物就都有落地实现了。
 *
 * <p><b>本轮的行为口径（v1，待策划确认）</b>：消耗品是「投掷型一次效果」，
 * 半径 4 格，按 {@code attrs} 里的键分别结算：
 * <ul>
 *   <li>{@code damage} → 对范围内<b>非主人</b>的生物造成等量伤害</li>
 *   <li>{@code maxHealth} → 治疗<b>主人</b> {@code maxHealth / 4}</li>
 *   <li>{@code moveSpeed} → 给范围内非主人 5 秒缓慢，等级按 {@code moveSpeed / 10} 取整</li>
 * </ul>
 * 其余键忽略，但会记 debug 日志 —— 数据里有值却看不到效果，是最难排查的一类问题。
 *
 * <p>这里的数值换算（除 4、除 10、5 秒）是工程侧的第一次落地，
 * <b>不是策划结论</b>：换数值只需要改这三个常量，不需要改结构。
 */
public final class ConsumableDeployer {

    /** 效果半径（格）。 */
    private static final double RADIUS = 4.0;
    /** 治疗效果 = maxHealth 的比例分之一。 */
    private static final double HEAL_DIVISOR = 4.0;
    /** 移动速度键换算成药水等级的除数（moveSpeed 20 → 2 级 → 放大器 1）。 */
    private static final double SLOW_DIVISOR = 10.0;
    /** 缓慢持续 tick（5 秒）。 */
    private static final int SLOW_TICKS = 100;

    /** 落地结果。{@code reason} 是给玩家看的中文，失败时才非空。 */
    public record Result(boolean deployed, String reason) {

        static Result ok() {
            return new Result(true, null);
        }

        static Result fail(String reason) {
            return new Result(false, reason);
        }
    }

    private ConsumableDeployer() {
    }

    /**
     * 在 {@code pos} 结算一次消耗品效果。
     *
     * <p>幂等由调用方保证（{@code OrbProjectile} 的 {@code landed} 标记）：
     * 命中与超时兜底可能在同 tick 触发，这里再判一次会漏掉「先落地、后超时」的顺序。
     */
    public static Result deploy(ServerLevel level, Vec3 pos, Spec spec, String ownerKey) {
        if (spec == null) {
            return Result.fail("记录里没有消耗品信息");
        }
        if (spec.type() != ProductType.CONSUMABLE) {
            return Result.fail("这不是消耗品");
        }

        AABB area = new AABB(pos.x - RADIUS, pos.y - RADIUS, pos.z - RADIUS,
                pos.x + RADIUS, pos.y + RADIUS, pos.z + RADIUS);
        List<LivingEntity> inRange = level.getEntitiesOfClass(LivingEntity.class, area,
                entity -> entity.isAlive());

        UUID ownerUuid = parse(ownerKey);
        List<String> ignored = new ArrayList<>();
        boolean didAnything = false;

        for (Map.Entry<String, Double> entry : spec.attrs().entrySet()) {
            double value = entry.getValue();
            if (value == 0.0) {
                continue;
            }
            switch (entry.getKey()) {
                case "damage" -> {
                    int hurt = damage(level, pos, inRange, ownerUuid, value);
                    if (hurt > 0) {
                        didAnything = true;
                    }
                }
                case "maxHealth" -> {
                    int healed = heal(level, pos, inRange, ownerUuid, value);
                    if (healed > 0) {
                        didAnything = true;
                    }
                }
                case "moveSpeed" -> {
                    int slowed = slow(inRange, ownerUuid, value);
                    if (slowed > 0) {
                        didAnything = true;
                    }
                }
                default -> ignored.add(entry.getKey());
            }
        }

        if (!ignored.isEmpty()) {
            // 不静默：玩家看到「没效果」时，日志里至少查得到原因。
            OrbcraftMod.LOG.debug("[orbcraft] 消耗品 {} 有未落地的属性键：{}", spec.id(), ignored);
        }
        if (!didAnything) {
            return Result.fail("这颗消耗品没有任何可结算的效果");
        }
        return Result.ok();
    }

    /** 对范围内非主人造成伤害，返回实际打到的目标数。 */
    private static int damage(ServerLevel level, Vec3 pos, List<LivingEntity> inRange,
                              UUID ownerUuid, double amount) {
        if (amount <= 0) {
            return 0;
        }
        int hits = 0;
        for (LivingEntity entity : inRange) {
            if (isOwner(entity, ownerUuid)) {
                continue;
            }
            // magic 伤害：不依赖攻击者实体（投掷物结算时可能已被移除），也不计护甲穿透之外的因素
            if (entity.hurtServer(level, level.damageSources().magic(), (float) amount)) {
                hits++;
            }
        }
        if (hits > 0) {
            level.sendParticles(ParticleTypes.ENCHANTED_HIT, true, true, pos.x, pos.y + 0.3, pos.z,
                    12, RADIUS / 2, 0.4, RADIUS / 2, 0.0);
        }
        return hits;
    }

    /** 治疗主人，返回实际治疗到的人数。 */
    private static int heal(ServerLevel level, Vec3 pos, List<LivingEntity> inRange,
                            UUID ownerUuid, double maxHealth) {
        float amount = (float) (maxHealth / HEAL_DIVISOR);
        if (amount <= 0) {
            return 0;
        }
        int healed = 0;
        for (LivingEntity entity : inRange) {
            if (!isOwner(entity, ownerUuid)) {
                continue;
            }
            if (entity.getHealth() >= entity.getMaxHealth()) {
                continue;
            }
            entity.heal(amount);
            healed++;
        }
        if (healed > 0) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, true, true, pos.x, pos.y + 0.3, pos.z,
                    10, RADIUS / 2, 0.4, RADIUS / 2, 0.0);
        }
        return healed;
    }

    /** 给范围内非主人缓慢，返回施加成功的目标数。 */
    private static int slow(List<LivingEntity> inRange, UUID ownerUuid, double moveSpeed) {
        int amplifier = (int) Math.floor(moveSpeed / SLOW_DIVISOR) - 1;
        if (amplifier < 0) {
            amplifier = 0;
        }
        int slowed = 0;
        for (LivingEntity entity : inRange) {
            if (isOwner(entity, ownerUuid)) {
                continue;
            }
            entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SLOW_TICKS, amplifier));
            slowed++;
        }
        return slowed;
    }

    /** 主人判定：只认 UUID，不用玩家名。 */
    private static boolean isOwner(LivingEntity entity, UUID ownerUuid) {
        return ownerUuid != null && entity.getUUID().equals(ownerUuid);
    }

    private static UUID parse(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(ownerKey);
        } catch (IllegalArgumentException e) {
            OrbcraftMod.LOG.warn("[orbcraft] 消耗品落地时 ownerKey 不是 UUID：{}", ownerKey);
            return null;
        }
    }
}
