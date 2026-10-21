package com.orbcraft.fabric.spawn;

import java.util.List;
import java.util.function.Predicate;

import com.orbcraft.core.spec.BehaviorMode;
import com.orbcraft.core.spec.Spec;
import com.orbcraft.fabric.OrbcraftMod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 行为绑定 —— 把 {@link BehaviorMode} 落到原版 AI 上。
 *
 * <p><b>在链路里的位置</b>：{@code VanillaSummonSpawner} 在实体进世界之前调本类。
 * 它只管「跟谁打、守在哪」，不管属性（那是 {@code SummonAttrMap}）、
 * 也不管外观（那是 {@code SummonAppearance}）。
 *
 * <p><b>诚实说明：这里做的是「一次性指定初始目标」，不是持续指挥。</b>
 * 原版生物每 tick 都会用自己的目标选择器重新挑目标，所以：
 * <ul>
 *   <li>ATTACK：以主人最近打过的生物为初始目标；主人没打过就挑最近的敌对生物。
 *       之后目标会被原版选择器改写 —— 真正的「玩家指定目标」需要一条指令通道
 *       （右键宠物 / 命令），那是 S2/S5 的事，不在 S3 里伪造。</li>
 *   <li>GUARD：设一个家（原版 {@code setHomeTo}）+ 选最近的敌对生物。
 *       原版只有部分 Goal 会读家的位置，所以「守得住」目前是概率性的。</li>
 *   <li>CHAOS：选最近的任意生物（含主人）。之后原版会按自己的规则继续打，
 *       不做「互相攻击同类召唤物」的额外规则。</li>
 * </ul>
 * 这四条都写在这里而不是分散在 spawn 流程里，改行为的人只需要看这一个文件。
 */
public final class SummonBehaviorBinder {

    private static final double ATTACK_RADIUS = 16.0;
    private static final double GUARD_RADIUS = 12.0;
    private static final double CHAOS_RADIUS = 16.0;

    private SummonBehaviorBinder() {
    }

    /**
     * @return {@code null} 表示绑定成功；否则是给玩家看的中文原因（调用方据此回滚并返回失败）
     */
    public static String bind(Mob mob, Spec spec, Vec3 pos, ServerPlayer owner, ServerLevel level) {
        BehaviorMode mode = spec.behavior();
        switch (mode) {
            case ATTACK -> {
                mob.setPersistenceRequired();
                LivingEntity target = owner == null ? null : owner.getLastHurtMob();
                if (!usable(target, mob)) {
                    target = nearest(level, pos, ATTACK_RADIUS, mob, e -> e instanceof Monster);
                }
                if (target != null) {
                    mob.setTarget(target);
                }
            }
            case GUARD -> {
                mob.setPersistenceRequired();
                mob.setHomeTo(BlockPos.containing(pos), (int) GUARD_RADIUS);
                LivingEntity target = nearest(level, pos, GUARD_RADIUS, mob, e -> e instanceof Monster);
                if (target != null) {
                    mob.setTarget(target);
                }
            }
            case CHAOS -> {
                mob.setPersistenceRequired();
                // 含主人：这是 CHAOS 的定义（BehaviorMode.canHarmOwner），不是漏判
                LivingEntity target = nearest(level, pos, CHAOS_RADIUS, mob, e -> true);
                if (target != null) {
                    mob.setTarget(target);
                }
            }
            case NONE -> {
                return "「" + spec.displayName() + "」没有行为模式，不能作为召唤物落地";
            }
        }
        OrbcraftMod.LOG.debug("[orbcraft] 召唤物 {} 行为绑定完成：{}", spec.displayName(), mode.zh());
        return null;
    }

    private static boolean usable(LivingEntity target, Mob self) {
        return target != null && target != self && target.isAlive();
    }

    private static LivingEntity nearest(ServerLevel level, Vec3 pos, double radius, Mob self,
                                        Predicate<LivingEntity> filter) {
        AABB box = new AABB(pos, pos).inflate(radius);
        List<LivingEntity> found = level.getEntities(
                EntityTypeTest.forClass(LivingEntity.class), box,
                e -> e != self && e.isAlive() && filter.test(e));
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity candidate : found) {
            double d = candidate.distanceToSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        return best;
    }
}
